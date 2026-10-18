package com.xhl.aicodegenerate.langgraph4j;

import cn.hutool.core.io.FileUtil;
import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.ai.AiCodeGeneratorService;
import com.xhl.aicodegenerate.ai.AiCodeGeneratorServiceFactory;
import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import com.xhl.aicodegenerate.langgraph4j.ai.CodeQualityCheckService;
import com.xhl.aicodegenerate.langgraph4j.ai.ImageCollectionPlanService;
import com.xhl.aicodegenerate.langgraph4j.model.ImageCollectionPlan;
import com.xhl.aicodegenerate.langgraph4j.model.QualityResult;
import com.xhl.aicodegenerate.langgraph4j.node.CodeGeneratorNode;
import com.xhl.aicodegenerate.langgraph4j.node.RouterNode;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.langgraph4j.tools.ImageSearchTool;
import com.xhl.aicodegenerate.langgraph4j.tools.PlaceholderLogoTool;
import com.xhl.aicodegenerate.langgraph4j.tools.KrokiMermaidDiagramTool;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实图编排，模型、图片和构建等外部边界由可控依赖替代。 */
class WorkflowBehaviorTest {
    private GenericApplicationContext context;
    private ApplicationContext previousContext;
    private CodeQualityCheckService quality;
    private Path output;
    private final long appId = System.nanoTime();

    @BeforeEach
    void setUp() throws Exception {
        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringContextUtil.class, "applicationContext");
        output = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "html_" + appId);
        Files.createDirectories(output);
        Files.writeString(output.resolve("index.html"), "<html><body>作品集</body></html>");
        context = new GenericApplicationContext();
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class, invocation -> {
            if (invocation.getMethod().getReturnType() == Flux.class) {
                return Flux.just("```html\n<html>作品集</html>\n```");
            }
            if (invocation.getMethod().getName().equals("generateAndSaveCode")) return output.toFile();
            return RETURNS_DEFAULTS.answer(invocation);
        });
        quality = mock(CodeQualityCheckService.class);
        when(quality.checkCodeQuality(anyString())).thenReturn(new QualityResult(true, List.of(), List.of()));
        ImageCollectionPlanService plan = mock(ImageCollectionPlanService.class);
        when(plan.planImageCollection(anyString())).thenReturn(ImageCollectionPlan.defaultPlan("作品集"));
        context.registerBean(AiCodeGeneratorFacade.class, () -> facade);
        context.registerBean(CodeQualityCheckService.class, () -> quality);
        context.registerBean(ImageCollectionPlanService.class, () -> plan);
        context.registerBean(ImageSearchTool.class, () -> mock(ImageSearchTool.class));
        context.registerBean(PlaceholderLogoTool.class, () -> mock(PlaceholderLogoTool.class));
        context.registerBean(KrokiMermaidDiagramTool.class, () -> mock(KrokiMermaidDiagramTool.class));
        context.refresh();
        new SpringContextUtil().setApplicationContext(context);
    }

    @AfterEach
    void tearDown() {
        new SpringContextUtil().setApplicationContext(previousContext);
        context.close();
        FileUtil.del(output.toFile());
    }

    @Test
    void shouldReuseSavedCodeTypeInsteadOfRoutingEachConversationAgain() throws Exception {
        WorkflowContext initial = WorkflowContext.builder().originalPrompt("把按钮改成红色")
                .generationType(CodeGenTypeEnum.VUE_PROJECT).build();
        var result = RouterNode.create().apply(new MessagesState<>(WorkflowContext.saveContext(initial))).join();
        assertThat(WorkflowContext.getContext(new MessagesState<>(result)).getGenerationType())
                .isEqualTo(CodeGenTypeEnum.VUE_PROJECT);
    }

    @Test
    void shouldCountQualityRetryEvenWhenModelProvidesNoErrorList() {
        WorkflowContext initial = WorkflowContext.builder().originalPrompt("作品集")
                .qualityResult(new QualityResult(false, List.of(), List.of())).build();
        ReflectionTestUtils.invokeMethod(CodeGeneratorNode.class, "buildUserMessage", initial);
        assertThat(initial.getQualityCheckRetryCount()).isEqualTo(1);
    }

    @Test
    void shouldEmitNodeStartedBeforeNodeCompleted() {
        List<ServerSentEvent<String>> events = new CodeGenWorkflow()
                .executeWorkflowWithServerSentEvent("作品集", appId, 1L).collectList().block();
        assertThat(events).extracting(ServerSentEvent::event).contains("step_started", "step_completed");
        assertThat(events.get(events.size() - 1).event()).isEqualTo("workflow_completed");
    }

    @Test
    void shouldReportRetryExhaustionAsFailureInsteadOfSuccessfulCompletion() {
        when(quality.checkCodeQuality(anyString())).thenReturn(new QualityResult(false, List.of("缺少标题"), List.of()));
        List<ServerSentEvent<String>> events = new CodeGenWorkflow()
                .executeWorkflowWithServerSentEvent("作品集", appId, 1L).collectList().block();
        assertThat(events.get(events.size() - 1).event()).isEqualTo("workflow_error");
        assertThat(events.get(events.size() - 1).data()).contains("最大重试次数");
    }

    @Test
    void shouldExposeQualityServiceFailureAsSkippedCheck() {
        when(quality.checkCodeQuality(anyString())).thenThrow(new IllegalStateException("质检暂不可用"));
        List<ServerSentEvent<String>> events = new CodeGenWorkflow()
                .executeWorkflowWithServerSentEvent("作品集", appId, 1L).collectList().block();
        assertThat(events).anySatisfy(event -> assertThat(event.data()).contains("\"status\":\"skipped\""));
        assertThat(events.get(events.size() - 1).event()).isEqualTo("workflow_completed");
    }

    @Test
    void shouldPersistOriginalInputOnceAcrossQualityRetries() {
        when(quality.checkCodeQuality(anyString())).thenReturn(new QualityResult(false, List.of("缺少标题"), List.of()),
                new QualityResult(true, List.of(), List.of()));
        var memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(UserMessage.from("旧需求"));
        memory.add(AiMessage.from("旧回复"));
        List<String> events = new CodeGenWorkflow().generateForApp("作品集", appId, 1L, CodeGenTypeEnum.HTML, memory)
                .collectList().block();
        assertThat(events).anySatisfy(event -> assertThat(event).contains("retrying"));
        assertThat(memory.messages()).hasSize(4);
        assertThat(((UserMessage) memory.messages().get(2)).singleText()).isEqualTo("作品集");
        assertThat(((AiMessage) memory.messages().get(3)).text()).contains("作品集", "工作流已完成");
    }

    @Test
    void shouldCancelActiveGenerationAndKeepInterruptedHistory() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        Sinks.Empty<Void> release = Sinks.empty();
        AiCodeGeneratorFacade facade = context.getBean(AiCodeGeneratorFacade.class);
        when(facade.generateAndSaveCodeStreamForWorkflow(anyString(), any(), any(), any()))
                .thenReturn(Flux.defer(() -> {
                    entered.countDown();
                    return release.asMono().thenMany(Flux.<String>empty());
                }).doOnCancel(cancelled::countDown));
        var memory = MessageWindowChatMemory.withMaxMessages(20);
        var subscription = new CodeGenWorkflow().generateForApp("作品集", appId, 1L, CodeGenTypeEnum.HTML, memory).subscribe();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(((AiMessage) memory.messages().get(1)).text()).contains("已中断");
            verifyNoInteractions(quality);
        } finally {
            release.tryEmitEmpty();
            subscription.dispose();
        }
    }

    @Test
    void shouldBuildVueExactlyOnceAfterQualityCheckWithRealFacade() throws Exception {
        Path vueOutput = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);
        Files.createDirectories(vueOutput);
        Files.writeString(vueOutput.resolve("App.vue"), "<template><h1>作品集</h1></template>");
        try {
            AiCodeGeneratorServiceFactory factory = mock(AiCodeGeneratorServiceFactory.class);
            AiCodeGeneratorService model = mock(AiCodeGeneratorService.class);
            TokenStream stream = mock(TokenStream.class, RETURNS_SELF);
            AtomicReference<Consumer<ChatResponse>> complete = new AtomicReference<>();
            when(stream.onCompleteResponse(any())).thenAnswer(invocation -> {
                complete.set(invocation.getArgument(0));
                return stream;
            });
            doAnswer(invocation -> {
                complete.get().accept(ChatResponse.builder().aiMessage(AiMessage.from("已生成作品集")).build());
                return null;
            }).when(stream).start();
            when(model.generateVueProjectCodeTokenStream(any(), anyString())).thenReturn(stream);
            when(factory.createAiCodeGeneratorService(any(), eq(CodeGenTypeEnum.VUE_PROJECT), any())).thenReturn(model);
            VueProjectBuilder builder = mock(VueProjectBuilder.class);
            when(builder.buildProject(vueOutput.toString())).thenReturn(true);
            AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade();
            ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
            ReflectionTestUtils.setField(facade, "vueProjectBuilder", builder);
            context.registerBean("realFacade", AiCodeGeneratorFacade.class, () -> facade, definition -> definition.setPrimary(true));
            context.registerBean(VueProjectBuilder.class, () -> builder);
            var events = new CodeGenWorkflow().generateForApp("作品集", appId, 1L, CodeGenTypeEnum.VUE_PROJECT,
                    MessageWindowChatMemory.withMaxMessages(20)).collectList().block();
            assertThat(events.get(events.size() - 1)).contains("\"id\":\"workflow\"", "\"status\":\"completed\"");
            var order = inOrder(quality, builder);
            order.verify(quality).checkCodeQuality(anyString());
            order.verify(builder).buildProject(vueOutput.toString());
            verify(builder, times(1)).buildProject(anyString());

            when(builder.buildProject(vueOutput.toString())).thenReturn(false);
            assertThatThrownBy(() -> new CodeGenWorkflow().generateForApp("作品集", appId, 1L, CodeGenTypeEnum.VUE_PROJECT,
                    MessageWindowChatMemory.withMaxMessages(20)).blockLast())
                    .hasMessageContaining("构建失败");
        } finally {
            FileUtil.del(vueOutput.toFile());
        }
    }
}
