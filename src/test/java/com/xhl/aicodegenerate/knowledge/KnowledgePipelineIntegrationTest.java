package com.xhl.aicodegenerate.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhl.aicodegenerate.ai.*;
import com.xhl.aicodegenerate.ai.tools.*;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import com.xhl.aicodegenerate.generation.*;
import com.xhl.aicodegenerate.langgraph4j.CodeGenWorkflow;
import com.xhl.aicodegenerate.langgraph4j.ai.*;
import com.xhl.aicodegenerate.langgraph4j.model.*;
import com.xhl.aicodegenerate.langgraph4j.tools.*;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实生成工厂、文件工具、图、Vue 修复；模型与构建/浏览器边界受控，不作为供应商质量验收。 */
class KnowledgePipelineIntegrationTest {
    @TempDir Path root;
    @ParameterizedTest
    @CsvSource({"standard,html", "standard,multi_file", "standard,vue_project", "workflow,html", "workflow,multi_file", "workflow,vue_project"})
    void realGenerationAndRepairKeepCapturedSources(String mode, String type) throws Exception {
        var mapper = new ObjectMapper();
        var model = new ScriptedModel(type, mapper);
        var tools = new ToolManager();
        ReflectionTestUtils.setField(tools, "tools", new BaseTool[]{new FileWriteTool()}); tools.init();
        var factory = new AiCodeGeneratorServiceFactory();
        ReflectionTestUtils.setField(factory, "reasoningStreamingChatModel", model);
        ReflectionTestUtils.setField(factory, "openAiStreamingChatModel", model);
        ReflectionTestUtils.setField(factory, "toolManager", tools);
        var facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
        var verifier = mock(PreviewVerifier.class);
        when(verifier.verify(any(), anyBoolean(), any())).thenReturn(new BuildResult("preview", 0, "", "", BuildResult.Kind.NONE, false));
        var builder = mock(VueProjectBuilder.class);
        when(builder.buildDetailed(any(), any())).thenReturn(new BuildResult("build", 1, "", "App.vue 标签错误", BuildResult.Kind.CODE, false),
                new BuildResult("build", 0, "", "", BuildResult.Kind.NONE, false));
        var delivery = new VueDeliveryService(builder, verifier, new VueRepairService(facade, tools), 2);
        var quality = mock(CodeQualityCheckService.class);
        if (type.equals("vue_project")) when(quality.checkCodeQuality(anyString())).thenReturn(new QualityResult(true, List.of(), List.of()));
        else when(quality.checkCodeQuality(anyString())).thenReturn(new QualityResult(false, List.of("需要重生成"), List.of()), new QualityResult(true, List.of(), List.of()));
        var planner = mock(ImageCollectionPlanService.class);
        when(planner.planImageCollection(anyString())).thenReturn(ImageCollectionPlan.defaultPlan("订单页面"));
        var structured = mock(TaskChatServices.class);
        when(structured.create(eq(CodeQualityCheckService.class), anyString())).thenReturn(quality);
        when(structured.create(eq(ImageCollectionPlanService.class), anyString())).thenReturn(planner);
        var previous = (ApplicationContext) ReflectionTestUtils.getField(SpringContextUtil.class, "applicationContext");
        try (var context = new GenericApplicationContext()) {
            context.registerBean(AiCodeGeneratorFacade.class, () -> facade);
            context.registerBean(TaskChatServices.class, () -> structured);
            context.registerBean(VueDeliveryService.class, () -> delivery);
            context.registerBean(ImageSearchTool.class, () -> mock(ImageSearchTool.class));
            context.registerBean(PlaceholderLogoTool.class, () -> mock(PlaceholderLogoTool.class));
            context.registerBean(KrokiMermaidDiagramTool.class, () -> mock(KrokiMermaidDiagramTool.class));
            context.registerBean(ToolManager.class, () -> tools);
            context.refresh(); new SpringContextUtil().setApplicationContext(context);
            var formal = MessageWindowChatMemory.withMaxMessages(20);
            var task = new GenerationTask(); task.setId(UUID.randomUUID().toString()); task.setAppId(11L); task.setUserId(7L);
            task.setCodeGenType(type); task.setGenerationMode(mode); task.setPrompt("生成订单页面"); task.setUseKnowledge(true);
            var store = mock(KnowledgeStore.class);
            var retrieval = mock(KnowledgeRetrievalService.class);
            var source = new KnowledgeTypes.Source(1, 2, 1, "接口.md", "build", "chunk", "PRIVATE_ORDER_V1: GET /internal/orders", 0.9);
            when(retrieval.search(7, "订单接口")).thenReturn(List.of(source));
            var queryModel = new StreamingChatModel() {
                @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                            .id("search").name("searchKnowledge").arguments("{\"query\":\"订单接口\"}").build())).build());
                }
            };
            var preparation = new KnowledgePreparation(store, retrieval, queryModel, mapper);
            var runner = new DefaultGenerationTaskRunner(ignored -> formal, facade, new CodeGenWorkflow(), delivery, verifier, tools, mock(TaskHistoryRecorder.class));
            ReflectionTestUtils.setField(runner, "knowledgePreparation", preparation);
            try (var run = new GenerationExecution(task.getId(), root, Duration.ofSeconds(30), () -> true)) {
                run.requireKnowledge();
                model.afterFirst = () -> when(retrieval.search(7, "订单接口")).thenThrow(new KnowledgeException("DELETED", "原文档已删除"));
                runner.run(task, run, ignored -> {}, ignored -> {});
                assertTrue(Files.exists(root.resolve(type.equals("vue_project") ? "src/App.vue" : "index.html")));
                assertEquals(type.equals("vue_project") ? 4 : mode.equals("workflow") ? 2 : 1, model.requests.size());
                for (var request : model.requests) {
                    assertTrue(request.messages().toString().contains("PRIVATE_ORDER_V1"));
                    assertFalse(request.toolSpecifications().stream().anyMatch(tool -> tool.name().equals("searchKnowledge")));
                }
                verify(retrieval, times(1)).search(7, "订单接口");
                verify(store).markSubmitted(run.id(), 7, 11);
                assertTrue(formal.messages().stream().noneMatch(m -> m.toString().contains("PRIVATE_ORDER_V1")));
            }
        } finally { new SpringContextUtil().setApplicationContext(previous); }
    }

    static class ScriptedModel implements StreamingChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        final String type;
        final ObjectMapper mapper;
        Runnable afterFirst = () -> {};
        ScriptedModel(String type, ObjectMapper mapper) { this.type = type; this.mapper = mapper; }
        @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            int step = requests.size(); requests.add(request);
            if (step == 0) afterFirst.run();
            try {
                AiMessage answer;
                if (type.equals("vue_project")) {
                    if (step % 2 == 0) answer = AiMessage.from(ToolExecutionRequest.builder().id("write-" + step).name("writeFile")
                            .arguments(mapper.writeValueAsString(Map.of("relativeFilePath", "src/App.vue", "content", "<template><h1>订单页面</h1></template>"))).build());
                    else answer = AiMessage.from("生成完毕");
                } else {
                    String text = "```html\n<!DOCTYPE html><html><head><title>订单</title></head><body><h1>订单页面</h1></body></html>\n```";
                    if (type.equals("multi_file")) text += "\n```css\nh1 { color: blue; }\n```\n```javascript\nconsole.log('orders');\n```";
                    handler.onPartialResponse(text); answer = AiMessage.from(text);
                }
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(answer).build());
            } catch (Exception error) { handler.onError(error); }
        }
    }
}
