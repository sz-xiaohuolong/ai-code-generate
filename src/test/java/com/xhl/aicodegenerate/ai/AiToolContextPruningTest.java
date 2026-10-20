package com.xhl.aicodegenerate.ai;

import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.ai.tools.BaseTool;
import com.xhl.aicodegenerate.ai.tools.FileModifyTool;
import com.xhl.aicodegenerate.ai.tools.FileReadTool;
import com.xhl.aicodegenerate.ai.tools.FileWriteTool;
import com.xhl.aicodegenerate.ai.tools.ToolManager;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 在实际工厂及工具续调边界验证请求裁剪，禁止外部模型与存储调用。 */
class AiToolContextPruningTest {
    @TempDir Path root;

    @Test
    void completedWriteIsPrunedBeforeContinuationWithoutChangingFileCallbackOrMemory() throws Exception {
        String code = "<template>\n" + "<p>完整源码回归标记</p>\n".repeat(200) + "</template>\n";
        var call = ToolExecutionRequest.builder().id("write-1").name("writeFile")
                .arguments(JSONUtil.toJsonStr(Map.of("relativeFilePath", "src/App.vue", "content", code))).build();
        var generated = AiMessage.builder().text("写入首页").thinking("先完整写入首页，再结束生成。")
                .toolExecutionRequests(List.of(call)).attributes(Map.of("trace", "keep-ai-attributes")).build();
        var model = new ScriptedModel(generated, AiMessage.from("生成完成"));
        var memory = MessageWindowChatMemory.withMaxMessages(30);
        var executions = new ArrayList<ToolExecution>();

        try (var run = new GenerationExecution(UUID.randomUUID().toString(), root, Duration.ofSeconds(30), () -> true)) {
            var id = new AppChatMemoryId(1L, 1L, run.id());
            var service = factory(model).createAiCodeGeneratorService(id, CodeGenTypeEnum.VUE_PROJECT, ignored -> memory);
            var completed = new CompletableFuture<ChatResponse>();
            service.generateVueProjectCodeTokenStream(id, "创建一个简洁首页")
                    .onToolExecuted(executions::add).onCompleteResponse(completed::complete)
                    .onError(completed::completeExceptionally).start();
            completed.get(10, TimeUnit.SECONDS);

            assertEquals(code, Files.readString(root.resolve("src/App.vue")), "裁剪不得进入真实写入参数");
            assertEquals(code, JSONUtil.parseObj(executions.getFirst().request().arguments()).getStr("content"));
            assertTrue(executions.getFirst().result().startsWith("文件写入成功:"), "原始工具回调保持兼容");
            var remembered = memory.messages().stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
                    .filter(AiMessage::hasToolExecutionRequests).findFirst().orElseThrow();
            assertEquals(code, JSONUtil.parseObj(remembered.toolExecutionRequests().getFirst().arguments()).getStr("content"));

            assertEquals(2, model.requests.size());
            var continuation = model.requests.get(1);
            var projected = continuation.messages().stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
                    .filter(AiMessage::hasToolExecutionRequests).findFirst().orElseThrow();
            assertFalse(projected.toolExecutionRequests().getFirst().arguments().contains("完整源码回归标记"),
                    "完整代码不应随已成功写入的历史参数再次发送");
            assertEquals(generated.thinking(), projected.thinking());
            assertEquals(generated.attributes(), projected.attributes());
            var result = continuation.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast).findFirst().orElseThrow();
            var receipt = JSONUtil.parseObj(result.text());
            assertEquals("src/App.vue", receipt.getStr("file"));
            assertEquals("success", receipt.getStr("status"));
            assertEquals(202, receipt.getInt("lines"));
            assertEquals("write-1", result.id());
            assertFalse(continuation.toolSpecifications().isEmpty(), "请求转换不能丢失工具定义");
        }
    }

    @Test
    void firstRequestPrunesOldWriteDisplayButKeepsUserCodeAndOriginalHistory() throws Exception {
        String code = "<p>旧任务源码标记</p>\n".repeat(200);
        String history = "原有说明\n\n[工具调用] 写入文件 src/App.vue\n```vue\n" + code + "\n```\n\n已完成交付。";
        String prompt = "请保留这个代码示例：\n```vue\n" + code + "\n```";
        var memory = MessageWindowChatMemory.withMaxMessages(30);
        memory.add(UserMessage.from("上一轮需求"));
        memory.add(AiMessage.from(history));
        var model = new ScriptedModel(AiMessage.from("无需修改"));
        var service = factory(model).createAiCodeGeneratorService(new AppChatMemoryId(1L, 1L),
                CodeGenTypeEnum.VUE_PROJECT, ignored -> memory);
        var completed = new CompletableFuture<ChatResponse>();
        service.generateVueProjectCodeTokenStream(new AppChatMemoryId(1L, 1L), prompt)
                .onCompleteResponse(completed::complete).onError(completed::completeExceptionally).start();
        completed.get(10, TimeUnit.SECONDS);

        var sent = model.requests.getFirst().messages();
        var previous = sent.stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast).findFirst().orElseThrow();
        assertFalse(previous.text().contains("旧任务源码标记"), "旧任务展示正文不能绕过请求裁剪");
        assertTrue(previous.text().contains("原有说明"));
        assertTrue(previous.text().contains("已完成交付。"));
        assertTrue(sent.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .anyMatch(message -> message.singleText().equals(prompt)));
        assertTrue(memory.messages().stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
                .anyMatch(message -> history.equals(message.text())), "完整展示历史不能被投影覆盖");
    }

    @Test
    void repairStyleReadModifyContinuationKeepsExactReadAndPrunesExecutedPatch() throws Exception {
        String original = "<template>\n<h1>旧标题</h1>\n</template>";
        String replacement = "<h1>新标题</h1>\n" + "<p>新增内容标记</p>\n".repeat(200);
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.vue"), original);
        var read = ToolExecutionRequest.builder().id("read").name("readFile")
                .arguments("{\"relativeFilePath\":\"src/App.vue\"}").build();
        var modify = ToolExecutionRequest.builder().id("modify").name("modifyFile")
                .arguments(JSONUtil.toJsonStr(Map.of("relativeFilePath", "src/App.vue",
                        "oldContent", "<h1>旧标题</h1>", "newContent", replacement))).build();
        var model = new ScriptedModel(AiMessage.from(read), AiMessage.from(modify), AiMessage.from("修复完成"));
        var memory = MessageWindowChatMemory.withMaxMessages(20);
        try (var run = new GenerationExecution(UUID.randomUUID().toString(), root, Duration.ofSeconds(30), () -> true)) {
            var id = new AppChatMemoryId(1L, 1L, run.id());
            var service = factory(model).createAiCodeGeneratorService(id, CodeGenTypeEnum.VUE_PROJECT, ignored -> memory);
            var complete = new CompletableFuture<ChatResponse>();
            service.generateVueProjectCodeTokenStream(id, "修复首页标题")
                    .onCompleteResponse(complete::complete).onError(complete::completeExceptionally).start();
            complete.get(10, TimeUnit.SECONDS);
            assertEquals(original.replace("<h1>旧标题</h1>", replacement), Files.readString(root.resolve("src/App.vue")));
            assertEquals(3, model.requests.size());
            var finalMessages = model.requests.get(2).messages();
            var readResult = finalMessages.stream().filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast).filter(message -> "read".equals(message.id())).findFirst().orElseThrow();
            assertEquals(original, readResult.text(), "用于精确修改的最近读取不能丢失");
            var modifyRequest = finalMessages.stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
                    .flatMap(message -> message.toolExecutionRequests().stream())
                    .filter(call -> "modify".equals(call.id())).findFirst().orElseThrow();
            assertFalse(modifyRequest.arguments().contains("新增内容标记"));
            var task = new com.xhl.aicodegenerate.generation.GenerationTask();
            run.copyUsage(task);
            assertEquals(3, task.getModelCalls(), "上下文裁剪不能绕过真实调用计数");
        }
    }

    static AiCodeGeneratorServiceFactory factory(StreamingChatModel model) {
        var factory = new AiCodeGeneratorServiceFactory();
        ReflectionTestUtils.setField(factory, "reasoningStreamingChatModel", model);
        var tools = new ToolManager();
        ReflectionTestUtils.setField(tools, "tools", new BaseTool[]{new FileWriteTool(), new FileReadTool(), new FileModifyTool()});
        tools.init();
        ReflectionTestUtils.setField(factory, "toolManager", tools);
        return factory;
    }

    static final class ScriptedModel implements StreamingChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        private final List<AiMessage> responses;

        ScriptedModel(AiMessage... responses) { this.responses = List.of(responses); }

        @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            int step = requests.size();
            requests.add(request);
            if (step >= responses.size()) {
                handler.onError(new AssertionError("模型调用超过脚本预期"));
                return;
            }
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(responses.get(step)).build());
        }
    }
}
