package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class UsageTrackingStreamingModelTest {
    @TempDir Path root;
    @Test void taskCancellationCancelsUnderlyingModelAndUsageIsActual() {
        var cancelled = new AtomicBoolean();
        StreamingChatModel model = new StreamingChatModel() {
            @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse(new PartialResponse("片段"), new PartialResponseContext(new StreamingHandle() { public void cancel() { cancelled.set(true); } public boolean isCancelled() { return cancelled.get(); } }));
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("结果")).tokenUsage(new TokenUsage(12, 7)).build());
            }
        };
        try (var run = new GenerationExecution("usage", root, Duration.ofSeconds(5), () -> true)) {
            UsageTrackingStreamingModel.wrap(model, new AppChatMemoryId(1L, 1L, run.id())).chat(
                    ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("需求")).build(),
                    new StreamingChatResponseHandler() {
                        @Override public void onCompleteResponse(ChatResponse response) { }
                        @Override public void onError(Throwable error) { fail(error); }
                    });
            run.cancel("停止");
            assertTrue(cancelled.get(), "必须取消实际供应商请求");
            var task = new GenerationTask(); run.copyUsage(task);
            assertEquals(1, task.getModelCalls()); assertEquals(12, task.getInputTokens()); assertEquals(7, task.getOutputTokens());
        }
    }
}
