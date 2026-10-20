package com.xhl.aicodegenerate.ai;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import com.xhl.aicodegenerate.ai.tools.FileReadTool;
import com.xhl.aicodegenerate.ai.tools.FileModifyTool;
import com.xhl.aicodegenerate.ai.tools.FileWriteTool;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 OpenAI-compatible 适配器与本地 SSE 服务，不访问真实供应商。 */
class ToolContextPruningProtocolTest {
    @TempDir Path root;

    @Test
    void wireProtocolKeepsReasoningAndToolPairsWhileReducingRepeatedPayload() throws Exception {
        String code = "<template>\n" + "<p>协议验证完整源码标记</p>\n".repeat(400) + "</template>\n";
        List<String> requests = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(body);
                var messages = JSONUtil.parseObj(body).getJSONArray("messages");
                boolean afterTool = "tool".equals(messages.getJSONObject(messages.size() - 1).getStr("role"));
                Map<String, Object> delta = afterTool ? Map.of("content", "生成完成") : Map.of(
                        "role", "assistant", "reasoning_content", "先完整写入首页，再结束生成。",
                        "tool_calls", List.of(Map.of("index", 0, "id", "protocol-write-1", "type", "function",
                                "function", Map.of("name", "writeFile", "arguments", JSONUtil.toJsonStr(
                                        Map.of("relativeFilePath", "src/App.vue", "content", code))))));
                String chunk = JSONUtil.toJsonStr(Map.of("id", "local-response", "object", "chat.completion.chunk",
                        "created", 1, "model", "local-model", "choices", List.of(Map.of("index", 0, "delta", delta,
                                "finish_reason", afterTool ? "stop" : "tool_calls"))));
                byte[] response = ("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var model = OpenAiStreamingChatModel.builder().apiKey("local-test-key").modelName("local-model")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .timeout(Duration.ofSeconds(5)).returnThinking(true).sendThinking(true, "reasoning_content")
                    .accumulateToolCallId(false).build();
            generate(model, code, false);
            generate(model, code, true);
            assertEquals(4, requests.size(), "两组样本各发生一次模型调用和一次工具续调");

            var baseline = JSONUtil.parseObj(requests.get(1));
            var projected = JSONUtil.parseObj(requests.get(3));
            var messages = projected.getJSONArray("messages");
            JSONObject ai = messages.stream().map(value -> (JSONObject) value)
                    .filter(value -> value.containsKey("tool_calls")).findFirst().orElseThrow();
            JSONObject result = messages.getJSONObject(messages.size() - 1);
            assertEquals("先完整写入首页，再结束生成。", ai.getStr("reasoning_content"));
            assertEquals("protocol-write-1", ai.getJSONArray("tool_calls").getJSONObject(0).getStr("id"));
            assertEquals("protocol-write-1", result.getStr("tool_call_id"));
            assertEquals("tool", result.getStr("role"));
            var arguments = JSONUtil.parseObj(ai.getJSONArray("tool_calls").getJSONObject(0)
                    .getJSONObject("function").getStr("arguments"));
            assertFalse(arguments.getStr("content").contains("协议验证完整源码标记"));
            assertTrue(arguments.getStr("content").contains("省略"));
            var receipt = JSONUtil.parseObj(result.getStr("content"));
            assertEquals("success", receipt.getStr("status"));
            assertEquals(402, receipt.getInt("lines"));
            assertEquals(baseline.getJSONArray("tools"), projected.getJSONArray("tools"));
            assertTrue(requests.get(1).contains("协议验证完整源码标记"));
            int before = requests.get(1).length();
            int after = requests.get(3).length();
            assertTrue(before - after > code.length() * 0.8, "固定样本应减少大部分重复代码正文");
            System.out.printf("本地协议固定样本：续调请求字符数 %d -> %d，下降 %.2f%%；不是实际 Token 或耗时测量。%n",
                    before, after, 100.0 * (before - after) / before);
        } finally {
            server.stop(0);
        }
    }

    private void generate(OpenAiStreamingChatModel model, String code, boolean pruning) throws Exception {
        Path taskRoot = root.resolve(pruning ? "pruned" : "baseline");
        try (var run = new GenerationExecution(UUID.randomUUID().toString(), taskRoot, Duration.ofSeconds(20), () -> true)) {
            var id = new AppChatMemoryId(1L, 1L, run.id());
            var memory = MessageWindowChatMemory.withMaxMessages(30);
            var service = pruning
                    ? AiToolContextPruningTest.factory(model).createAiCodeGeneratorService(id, CodeGenTypeEnum.VUE_PROJECT, ignored -> memory)
                    : AiServices.builder(AiCodeGeneratorService.class).streamingChatModel(model).chatMemoryProvider(ignored -> memory)
                            .tools(new FileWriteTool(), new FileReadTool(), new FileModifyTool()).maxToolCallingRoundTrips(20).build();
            var complete = new CompletableFuture<ChatResponse>();
            service.generateVueProjectCodeTokenStream(id, "创建一个简洁首页")
                    .onCompleteResponse(complete::complete).onError(complete::completeExceptionally).start();
            assertEquals("生成完成", complete.get(10, TimeUnit.SECONDS).aiMessage().text());
            assertEquals(code, Files.readString(taskRoot.resolve("src/App.vue")));
        }
    }
}
