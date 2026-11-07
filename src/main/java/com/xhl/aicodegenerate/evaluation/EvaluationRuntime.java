package com.xhl.aicodegenerate.evaluation;

import com.mybatisflex.core.mybatis.FlexConfiguration;
import com.mybatisflex.spring.FlexSqlSessionFactoryBean;
import com.xhl.aicodegenerate.ai.AiCodeGeneratorServiceFactory;
import com.xhl.aicodegenerate.ai.tools.*;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.GenerationTraceStep;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.generation.*;
import com.xhl.aicodegenerate.langgraph4j.CodeGenWorkflow;
import com.xhl.aicodegenerate.langgraph4j.model.ImageResource;
import com.xhl.aicodegenerate.langgraph4j.tools.ImageSearchTool;
import com.xhl.aicodegenerate.langgraph4j.tools.KrokiMermaidDiagramTool;
import com.xhl.aicodegenerate.langgraph4j.tools.PlaceholderLogoTool;
import com.xhl.aicodegenerate.mapper.*;
import com.xhl.aicodegenerate.observability.*;
import com.xhl.aicodegenerate.service.AppScreenshotService;
import com.xhl.aicodegenerate.service.AppService;
import com.xhl.aicodegenerate.service.UserService;
import com.xhl.aicodegenerate.service.impl.AppServiceImpl;
import com.xhl.aicodegenerate.service.impl.ChatHistoryServiceImpl;
import com.xhl.aicodegenerate.service.impl.UserServiceImpl;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.community.store.memory.chat.redis.StoreType;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立命令的最小 Spring 容器；不扫描应用配置，不加载本地凭据，不启动业务 HTTP 服务。 */
public final class EvaluationRuntime implements AutoCloseable {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private final AnnotationConfigApplicationContext context;
    private final EvaluationRedis redis;
    private final User user;
    private final AtomicBoolean closed = new AtomicBoolean();

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    private EvaluationRuntime(AnnotationConfigApplicationContext context, EvaluationRedis redis, User user) {
        this.context = context; this.redis = redis; this.user = user;
    }

    public static EvaluationRuntime open(DataSource dataSource, Path directory, EvaluationModelSet models,
            Duration taskLimit, boolean traceFailure) {
        if (!ACTIVE.compareAndSet(false, true)) {
            throw new EvaluationConfigurationException("独立评测进程内一次仅运行一个隔离环境");
        }
        EvaluationRedis redis = null;
        var context = new AnnotationConfigApplicationContext();
        try {
            redis = EvaluationRedis.start(directory.resolve("redis"));
            register(context, dataSource, directory, models, taskLimit, traceFailure, redis);
            context.refresh();
            User owner = User.builder().userAccount("eval-" + UUID.randomUUID()).userRole("user")
                    .userName("隔离评测用户").userPassword(cn.hutool.crypto.digest.DigestUtil.sha256Hex(UUID.randomUUID().toString())).build();
            if (!context.getBean(UserService.class).save(owner)) throw new IllegalStateException("隔离评测用户未保存");
            return new EvaluationRuntime(context, redis, owner);
        } catch (Exception | LinkageError error) {
            try { context.close(); }
            finally {
                try { if (redis != null) redis.close(); }
                finally { ACTIVE.set(false); new SpringContextUtil().setApplicationContext(null); }
            }
            throw new EvaluationConfigurationException("隔离生成环境初始化失败", error);
        }
    }

    private static void register(AnnotationConfigApplicationContext context, DataSource dataSource, Path directory,
            EvaluationModelSet models, Duration taskLimit, boolean traceFailure, EvaluationRedis redis) throws Exception {
        // 配置值仅用于关闭外部边界；从不读取 application.yml 或 application-local.yml。
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("evaluation", Map.of("pexels.api-key", "")));
        context.register(Transactions.class, SpringContextUtil.class);
        var configuration = new FlexConfiguration();
        List<Class<?>> mappers = List.of(AppMapper.class, UserMapper.class, ChatHistoryMapper.class,
                GenerationTraceMapper.class, GenerationTraceStepMapper.class);
        var factory = new FlexSqlSessionFactoryBean();
        factory.setConfiguration(configuration);
        factory.setDataSource(dataSource);
        var sessions = new SqlSessionTemplate(factory.getObject());
        // Flex 为注解 SQL 注册主键生成器时需要已初始化的 Environment。
        for (Class<?> mapper : mappers) configuration.addMapper(mapper);
        for (Class<?> mapper : mappers) registerMapper(context, sessions, mapper);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> transactionManager);
        context.registerBean("openAiChatModel", ChatModel.class, models::primary);
        context.registerBean("routingChatModel", ChatModel.class, models::routing);
        context.registerBean("openAiStreamingChatModel", StreamingChatModel.class, models::standard);
        context.registerBean("reasoningStreamingChatModel", StreamingChatModel.class, models::reasoning);
        context.registerBean(RedisChatMemoryStore.class, () -> RedisChatMemoryStore.builder().host("127.0.0.1")
                .port(redis.port()).ttl(86400L).storeType(StoreType.STRING).build());
        context.registerBean(RedisTaskStore.class, () -> new RedisTaskStore(redis.template()));
        context.registerBean(GenerationWorkspace.class, () -> new GenerationWorkspace(context.getBean(RedisTaskStore.class),
                directory.resolve("tasks"), directory.resolve("initial")));
        context.registerBean(MybatisTraceStore.class, () -> new MybatisTraceStore(context.getBean(GenerationTraceMapper.class),
                context.getBean(GenerationTraceStepMapper.class), transactionManager));
        context.registerBean(GenerationTraceRecorder.class, () -> {
            var store = context.getBean(MybatisTraceStore.class);
            TraceStore writer = traceFailure ? failingWriter(store) : store;
            return new GenerationTraceRecorder(writer, Clock.systemUTC(), 2048);
        }, definition -> definition.setDestroyMethodName("close"));
        context.registerBean(GenerationTraceMaintenance.class, () -> new GenerationTraceMaintenance(
                context.getBean(MybatisTraceStore.class), context.getBean(GenerationTraceRecorder.class), null, false, 3600));
        context.register(FileWriteTool.class, FileReadTool.class, FileModifyTool.class, FileDeleteTool.class, FileDirReadTool.class,
                ToolManager.class, AiCodeGeneratorServiceFactory.class, AiCodeGeneratorFacade.class, VueProjectBuilder.class,
                CodeGenWorkflow.class, TaskChatServices.class, PreviewVerifier.class, VueRepairService.class,
                UserServiceImpl.class, ChatHistoryServiceImpl.class, AppServiceImpl.class, TaskHistoryRecorder.class);
        context.registerBean(VueDeliveryService.class, () -> new VueDeliveryService(context.getBean(VueProjectBuilder.class),
                context.getBean(PreviewVerifier.class), context.getBean(VueRepairService.class), 2));
        context.registerBean(DefaultGenerationTaskRunner.class, () -> new DefaultGenerationTaskRunner(context.getBean(ChatMemoryProvider.class),
                context.getBean(AiCodeGeneratorFacade.class), context.getBean(CodeGenWorkflow.class), context.getBean(VueDeliveryService.class),
                context.getBean(PreviewVerifier.class), context.getBean(ToolManager.class), context.getBean(TaskHistoryRecorder.class)));
        // 并行知识库功能新增了此依赖；本数据集不启用检索，误调用时明确失败，不连接任何资料库或向量服务。
        context.registerBean(com.xhl.aicodegenerate.knowledge.KnowledgePreparation.class, () ->
                new com.xhl.aicodegenerate.knowledge.KnowledgePreparation(null, null, models.reasoning(), new com.fasterxml.jackson.databind.ObjectMapper()) {
                    @Override public com.xhl.aicodegenerate.knowledge.KnowledgeContext prepare(String taskId, long ownerId,
                            long appId, Long userMessageId, Long aiMessageId, GenerationExecution run) {
                        throw new UnsupportedOperationException("本评测数据集不允许访问知识库");
                    }
                });
        context.registerBean(GenerationTaskService.class, () -> new GenerationTaskService(context.getBean(RedisTaskStore.class),
                context.getBean(GenerationWorkspace.class), context.getBean(AppService.class), context.getBean(DefaultGenerationTaskRunner.class),
                taskLimit, context.getBean(GenerationTraceRecorder.class)), definition -> definition.setDestroyMethodName("close"));
        registerExternalBoundaries(context);
    }

    private static <T> void registerMapper(AnnotationConfigApplicationContext context, SqlSessionTemplate sessions, Class<T> mapper) {
        context.registerBean(mapper, () -> sessions.getMapper(mapper));
    }

    private static TraceStore failingWriter(MybatisTraceStore delegate) {
        return new TraceStore() {
            @Override public void persist(GenerationTrace root, GenerationTraceStep step) {
                if ("GENERATION".equals(root.getTraceKind()) && step != null) {
                    throw new IllegalStateException("评测注入：步骤持久化不可用");
                }
                delegate.persist(root, step);
            }
            @Override public void markIncomplete(String traceId, String instanceId) { delegate.markIncomplete(traceId, instanceId); }
            @Override public void markInstanceIncomplete(String instanceId) { delegate.markInstanceIncomplete(instanceId); }
        };
    }

    private static void registerExternalBoundaries(AnnotationConfigApplicationContext context) {
        // 受控素材返回空集合，保留真实图节点与调用步骤，不写公开素材目录、不请求外部服务。
        context.registerBean(ImageSearchTool.class, () -> new ImageSearchTool() {
            @Override public List<ImageResource> searchContentImages(String query) { return List.of(); }
        });
        context.registerBean(PlaceholderLogoTool.class, () -> new PlaceholderLogoTool(null) {
            @Override public List<ImageResource> generateLogos(String description, Long appId) { return List.of(); }
        });
        context.registerBean(KrokiMermaidDiagramTool.class, () -> new KrokiMermaidDiagramTool(null) {
            @Override public List<ImageResource> generateMermaidDiagram(String code, String description, Long appId) { return List.of(); }
        });
        context.registerBean(AppScreenshotService.class, () -> new AppScreenshotService() {
            @Override public void captureAppCoverAsync(Long appId, String targetUrl) { /* 评测明确关闭封面发布副作用。 */ }
            @Override public String captureAppCover(Long appId, String targetUrl) {
                throw new UnsupportedOperationException("评测不发布应用封面，证据截图由独立验收器保存");
            }
        });
    }

    public User user() { return user; }
    public AppService apps() { return context.getBean(AppService.class); }
    public GenerationTaskService tasks() { return context.getBean(GenerationTaskService.class); }
    public GenerationWorkspace workspace() { return context.getBean(GenerationWorkspace.class); }
    public GenerationTraceRecorder recorder() { return context.getBean(GenerationTraceRecorder.class); }
    public GenerationTraceMapper traces() { return context.getBean(GenerationTraceMapper.class); }
    public GenerationTraceStepMapper steps() { return context.getBean(GenerationTraceStepMapper.class); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { context.close(); }
        finally {
            try { redis.close(); }
            finally { new SpringContextUtil().setApplicationContext(null); ACTIVE.set(false); }
        }
    }
}
