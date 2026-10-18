package com.xhl.aicodegenerate.generation;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TaskChatModelTest {
    @TempDir Path root;
    @Test void recordsStructuredResponseUsage() {
        StreamingChatModel model = new StreamingChatModel() {
            @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("{\"isValid\":true}"))
                        .tokenUsage(new TokenUsage(9, 4)).build());
            }
        };
        try (var run = new GenerationExecution("结构化用量",root,Duration.ofSeconds(5),()->true)) {
            var response = new TaskChatModel(model,run).chat(ChatRequest.builder().messages(UserMessage.from("质检")).build());
            assertTrue(response.aiMessage().text().contains("isValid"));
            var task = new GenerationTask(); run.copyUsage(task); assertEquals(1,task.getModelCalls()); assertEquals(9,task.getInputTokens());
        }
    }
    @Test void stopsPendingStructuredCallAndActualStream() throws Exception {
        var entered = new CountDownLatch(1); var cancelled = new AtomicBoolean();
        StreamingChatModel model = new StreamingChatModel() {
            @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse(new PartialResponse("{"), new PartialResponseContext(new StreamingHandle() {
                    public void cancel() { cancelled.set(true); } public boolean isCancelled() { return cancelled.get(); }
                })); entered.countDown();
            }
        };
        try (var run = new GenerationExecution("结构化取消",root,Duration.ofSeconds(5),()->true);
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = pool.submit(() -> new TaskChatModel(model,run).chat("质检"));
            assertTrue(entered.await(1,TimeUnit.SECONDS)); run.cancel("用户停止");
            assertThrows(ExecutionException.class,()->result.get(1,TimeUnit.SECONDS)); assertTrue(cancelled.get());
        }
    }
}
