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
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.GenerationTraceStep;
import com.xhl.aicodegenerate.observability.GenerationTraceRecorder;
import static org.junit.jupiter.api.Assertions.*;

class UsageTrackingStreamingModelTest {
    @TempDir Path root;

    @Test void interleavedDuplicateAndLateCallbacksRemainPerCallAndPerTask() {
        List<StreamingChatResponseHandler> callbacks = new ArrayList<>();
        List<GenerationTraceStep> steps = new java.util.concurrent.CopyOnWriteArrayList<>();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) { callbacks.add(handler); }
        };
        try (var recorder = new GenerationTraceRecorder((trace, step) -> {
            if (step != null) { steps.removeIf(old -> old.getSpanId().equals(step.getSpanId())); steps.add(step); }
        }, Clock.systemUTC(), 128);
             var first = new GenerationExecution("usage-first", root.resolve("first"), Duration.ofSeconds(5), () -> true);
             var second = new GenerationExecution("usage-second", root.resolve("second"), Duration.ofSeconds(5), () -> true)) {
            first.attachTrace(recorder.startGeneration(first.id(), 1L, 1L, "standard", "html"));
            second.attachTrace(recorder.startGeneration(second.id(), 2L, 2L, "workflow", "html"));
            var completions = new AtomicInteger();
            var errors = new AtomicInteger();
            var handler = new StreamingChatResponseHandler() {
                @Override public void onCompleteResponse(ChatResponse response) { completions.incrementAndGet(); }
                @Override public void onError(Throwable error) { errors.incrementAndGet(); }
            };
            ChatRequest request = ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("受控需求")).build();
            UsageTrackingStreamingModel.wrap(delegate, new AppChatMemoryId(1L, 1L, first.id())).chat(request, handler);
            UsageTrackingStreamingModel.wrap(delegate, new AppChatMemoryId(2L, 2L, second.id())).chat(request, handler);
            var partial = ChatResponse.builder().aiMessage(AiMessage.from("正文不得入追踪"))
                    .tokenUsage(new TokenUsage(12, null)).build();
            callbacks.get(1).onError(new IllegalStateException("secret-key=/private"));
            callbacks.get(0).onCompleteResponse(partial);
            callbacks.get(0).onCompleteResponse(partial);
            callbacks.get(0).onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("重复终态"))
                    .tokenUsage(new TokenUsage(12, 7)).build());
            callbacks.get(1).onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("迟到用量"))
                    .tokenUsage(new TokenUsage(null, 3)).build());
            callbacks.get(0).onError(new IllegalStateException("迟到错误"));
            assertEquals(1, completions.get());
            assertEquals(1, errors.get());
            var firstTask = new GenerationTask(); first.copyUsage(firstTask);
            var secondTask = new GenerationTask(); second.copyUsage(secondTask);
            assertEquals(12L, firstTask.getInputTokens());
            assertEquals(7L, firstTask.getOutputTokens());
            assertEquals(1, firstTask.getUsageReportedCalls());
            assertNull(secondTask.getInputTokens());
            assertEquals(3L, secondTask.getOutputTokens());
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertEquals(2, steps.size());
            var failed = steps.stream().filter(step -> step.getTraceId().equals(second.trace().traceId())).findFirst().orElseThrow();
            assertEquals("FAILED", failed.getStatus());
            assertEquals(3L, failed.getOutputTokens());
            assertFalse(steps.toString().contains("secret-key"));
        }
    }

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
