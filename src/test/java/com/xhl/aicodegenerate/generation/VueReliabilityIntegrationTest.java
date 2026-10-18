package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.*;
import com.xhl.aicodegenerate.ai.tools.ToolManager;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import com.xhl.aicodegenerate.entity.*;
import com.xhl.aicodegenerate.langgraph4j.CodeGenWorkflow;
import com.xhl.aicodegenerate.langgraph4j.ai.*;
import com.xhl.aicodegenerate.langgraph4j.model.*;
import com.xhl.aicodegenerate.langgraph4j.tools.*;
import com.xhl.aicodegenerate.service.AppService;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 模型边界可控，真实 Redis、工作流图、Vue 编译器、构建进程和 Chrome 共同验收。 */
class VueReliabilityIntegrationTest {
    @TempDir Path root;
    @BeforeAll static void redis() throws Exception { RedisTaskStoreTest.openRedis(); }
    @AfterAll static void closeRedis() { RedisTaskStoreTest.closeRedis(); }
    @ParameterizedTest @ValueSource(strings={"standard","workflow"})
    void bothModesRepairRealVueCompilerErrorAndPublishOnlyVerifiedVersion(String mode) throws Exception {
        ApplicationContext previous = (ApplicationContext)ReflectionTestUtils.getField(SpringContextUtil.class,"applicationContext");
        try (var context = new GenericApplicationContext()) {
            var store = new RedisTaskStore(RedisTaskStoreTest.template,Duration.ofSeconds(30),Duration.ofHours(24));
            var workspace = new GenerationWorkspace(store,root.resolve("tasks"),root.resolve("legacy"));
            var app = App.builder().id(Math.abs((long)mode.hashCode())).userId(91L).codeGenType("vue_project").generationMode(mode).build();
            var apps = mock(AppService.class); when(apps.getById(app.getId())).thenReturn(app);
            var user = new User(); user.setId(91L);
            var repairs = new AtomicInteger();
            var delivery = new VueDeliveryService(new VueProjectBuilder(),new PreviewVerifier(),(id,diagnostic,output)->{
                assertTrue(diagnostic.contains("App.vue")); repairs.incrementAndGet();
                try { GenerationExecution.require(id.getTaskId()).files(()->{
                    Files.writeString(GenerationExecution.require(id.getTaskId()).root().resolve("src/App.vue"),"<template><h1>可靠 Vue</h1></template>");return null;
                }); } catch (Exception e) { throw new IllegalStateException(e); }
            },2);
            var facade = mock(AiCodeGeneratorFacade.class);
            when(facade.generateAndSaveCodeStreamForWorkflow(anyString(),any(),any(),any())).thenAnswer(invocation->{
                AppChatMemoryId id=invocation.getArgument(2);
                return Flux.defer(()->{
                    try { var run=GenerationExecution.require(id.getTaskId()); run.files(()->{ fixture(run.root());return null; }); }
                    catch(Exception e) {return Flux.error(e);}
                    return Flux.just("{\"type\":\"ai_response\",\"data\":\"Vue 源码已生成\"}");
                });
            });
            var quality=mock(CodeQualityCheckService.class); when(quality.checkCodeQuality(anyString())).thenReturn(new QualityResult(true,List.of(),List.of()));
            var planner=mock(ImageCollectionPlanService.class); when(planner.planImageCollection(anyString())).thenReturn(ImageCollectionPlan.defaultPlan("简单页面"));
            var services=mock(TaskChatServices.class);
            when(services.create(eq(CodeQualityCheckService.class),anyString())).thenReturn(quality);
            when(services.create(eq(ImageCollectionPlanService.class),anyString())).thenReturn(planner);
            context.registerBean(AiCodeGeneratorFacade.class,()->facade);
            context.registerBean(TaskChatServices.class,()->services);
            context.registerBean(VueDeliveryService.class,()->delivery);
            context.registerBean(ImageSearchTool.class,()->mock(ImageSearchTool.class));
            context.registerBean(PlaceholderLogoTool.class,()->mock(PlaceholderLogoTool.class));
            context.registerBean(KrokiMermaidDiagramTool.class,()->mock(KrokiMermaidDiagramTool.class));
            context.refresh();new SpringContextUtil().setApplicationContext(context);
            var memory=MessageWindowChatMemory.withMaxMessages(20);
            var history=mock(TaskHistoryRecorder.class);
            doAnswer(invocation->{var ended=(GenerationTask)invocation.getArgument(0);memory.add(java.util.List.of(dev.langchain4j.data.message.UserMessage.from(ended.getPrompt()),dev.langchain4j.data.message.AiMessage.from(ended.getOutput())));return null;}).when(history).record(any());
            var runner=new DefaultGenerationTaskRunner(ignored->memory,facade,new CodeGenWorkflow(),delivery,new PreviewVerifier(),mock(ToolManager.class),history);
            try(var tasks=new GenerationTaskService(store,workspace,apps,runner,Duration.ofMinutes(2))) {
                var task=tasks.start(app.getId(),"生成可运行的 Vue 页面",UUID.randomUUID().toString(),user);
                // 首次订阅退出后，后台仍要经历编译失败、修复、构建及浏览器验证。
                tasks.events(task.getId(),user).take(1).blockLast(Duration.ofSeconds(2));
                GenerationTask result=tasks.events(task.getId(),user).blockLast(Duration.ofMinutes(2));
                assertNotNull(result);assertEquals(TaskStatus.SUCCEEDED,result.getStatus(),result.getMessage());
                assertEquals(1,repairs.get());assertEquals(task.getId(),store.published(app.getId()));
                assertTrue(Files.isRegularFile(workspace.published(app.getId(),"vue_project").resolve("dist/index.html")));
                for(int i=0;i<100&&memory.messages().size()<2;i++)Thread.sleep(10);
                assertEquals(2,memory.messages().size());
                assertTrue(result.getSteps().stream().anyMatch(step->"preview_check".equals(step.get("id"))&&"completed".equals(step.get("status"))));
            }
        } finally {new SpringContextUtil().setApplicationContext(previous);}
    }
    private void fixture(Path output) throws Exception {
        Path modules=Path.of(System.getProperty("user.dir"),"ai-code-frontend/node_modules").toAbsolutePath();
        assertTrue(Files.exists(modules.resolve("vite/dist/node/index.js")),"请先安装现有前端依赖");
        Files.createDirectories(output.resolve("src"));
        Files.writeString(output.resolve("package.json"),"{\"name\":\"vue-reliability-fixture\",\"version\":\"1.0.0\",\"scripts\":{\"build\":\"node build.mjs\"}}");
        Files.writeString(output.resolve("index.html"),"<!doctype html><html><body><div id='app'></div><script type='module' src='./src/main.js'></script></body></html>");
        Files.writeString(output.resolve("src/main.js"),"import {createApp} from 'vue';import App from './App.vue';createApp(App).mount('#app');");
        Files.writeString(output.resolve("src/App.vue"),"<template><h1>可靠 Vue</h2></template>");
        String script="import {build} from '"+modules.resolve("vite/dist/node/index.js").toUri()+"';import vue from '"+modules.resolve("@vitejs/plugin-vue/dist/index.mjs").toUri()+"';await build({root:process.cwd(),base:'./',plugins:[vue()],resolve:{alias:{vue:'"+modules.resolve("vue/dist/vue.runtime.esm-bundler.js")+"'}}});";
        Files.writeString(output.resolve("build.mjs"),script);
    }
}
