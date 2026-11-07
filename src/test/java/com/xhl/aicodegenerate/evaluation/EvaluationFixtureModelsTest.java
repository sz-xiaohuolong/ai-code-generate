package com.xhl.aicodegenerate.evaluation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationFixtureModelsTest {
    @Test void fixtureEmitsRealToolProtocolAndRepairReplacesOnlyFaultyBatch() {
        var dataset = EvaluationDataset.load(Path.of("evaluation"));
        var sample = dataset.cases().stream().filter(item -> "E03".equals(item.caseId())).findFirst().orElseThrow();
        var provider = new EvaluationFixtureModels(sample, dataset.fixture(sample));
        var budget = new EvaluationBudget(10, Duration.ofMinutes(1));
        var models = provider.models(budget);
        var first = response(models.reasoning(), request("Vue 代码生成"));
        var broken = first.aiMessage().toolExecutionRequests().stream().filter(tool -> tool.arguments().contains("src/App.vue")).findFirst().orElseThrow();
        assertTrue(broken.arguments().contains("故意的编译错误"));
        var ended = response(models.reasoning(), ChatRequest.builder().messages(List.of(SystemMessage.from("Vue 代码生成"),
                UserMessage.from("生成页面"), first.aiMessage(), ToolExecutionResultMessage.from(broken, "文件写入成功"))).build());
        assertFalse(ended.aiMessage().hasToolExecutionRequests());
        var repair = response(models.reasoning(), request("依据真实诊断修复"));
        assertTrue(repair.aiMessage().hasToolExecutionRequests());
        assertFalse(repair.aiMessage().toolExecutionRequests().stream().anyMatch(tool -> tool.arguments().contains("故意的编译错误")));
        assertEquals(3, budget.modelCalls());
    }

    @Test void cancellationExposesCancelableStreamInsteadOfFabricatedCompletion() {
        var dataset = EvaluationDataset.load(Path.of("evaluation"));
        var sample = dataset.cases().stream().filter(item -> "E07".equals(item.caseId())).findFirst().orElseThrow();
        var provider = new EvaluationFixtureModels(sample, dataset.fixture(sample));
        var models = provider.models(new EvaluationBudget(10, Duration.ofMinutes(1)));
        var handle = new AtomicReference<StreamingHandle>();
        var completed = new AtomicReference<ChatResponse>();
        models.standard().chat(request("HTML 代码生成"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(PartialResponse value, PartialResponseContext context) { handle.set(context.streamingHandle()); }
            @Override public void onCompleteResponse(ChatResponse value) { completed.set(value); }
            @Override public void onError(Throwable error) { fail(error); }
        });
        assertTrue(provider.waitingForCancellation());
        assertNull(completed.get());
        assertNotNull(handle.get());
        handle.get().cancel();
        assertTrue(provider.streamCancelled());
    }

    private static ChatRequest request(String system) {
        return ChatRequest.builder().messages(SystemMessage.from(system), UserMessage.from("生成页面")).build();
    }
    private static ChatResponse response(dev.langchain4j.model.chat.StreamingChatModel model, ChatRequest request) {
        var result = new AtomicReference<ChatResponse>();
        model.chat(request, new StreamingChatResponseHandler() {
            @Override public void onCompleteResponse(ChatResponse response) { result.set(response); }
            @Override public void onError(Throwable error) { fail(error); }
        });
        assertNotNull(result.get()); return result.get();
    }
}
