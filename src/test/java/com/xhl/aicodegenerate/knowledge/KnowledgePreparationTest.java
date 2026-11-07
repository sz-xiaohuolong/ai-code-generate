package com.xhl.aicodegenerate.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgePreparationTest {
    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final StreamingChatModel model = mock(StreamingChatModel.class);
    private final KnowledgePreparation preparation = new KnowledgePreparation(store, retrieval, model, new ObjectMapper());

    private GenerationExecution run() {
        var run = new GenerationExecution(UUID.randomUUID().toString(), Path.of("target/knowledge-test"), Duration.ofSeconds(5), () -> true);
        run.captureRequirements(List.of(), "生成订单页面");
        return run;
    }
    private AiMessage tool(String name, String arguments) {
        return AiMessage.from(List.of(ToolExecutionRequest.builder().id("call-1").name(name).arguments(arguments).build()));
    }

    @Test void correctsOnceThenFreezesOnlyActualRetrievedSources() {
        var count = new AtomicInteger();
        doAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            assertEquals(List.of("searchKnowledge"), request.toolSpecifications().stream().map(t -> t.name()).toList());
            StreamingChatResponseHandler handler = call.getArgument(1);
            var answer = count.getAndIncrement() == 0 ? AiMessage.from("<html>直接写代码</html>") : tool("searchKnowledge", "{\"query\":\"订单接口\"}");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(answer).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var source = new KnowledgeTypes.Source(1, 2, 1, "私有文档.md", "build", "chunk", "GET /internal/orders", 0.9);
        when(retrieval.search(7, "订单接口")).thenReturn(List.of(source));
        try (var run = run()) {
            var context = preparation.prepare(run.id(), 7, 11, null, null, run);
            assertEquals(2, count.get());
            var original = ChatRequest.builder().messages(UserMessage.from("修复页面")).build();
            var projected = context.project(original);
            assertEquals(1, original.messages().size());
            assertTrue(projected.messages().toString().contains("GET /internal/orders"));
            assertEquals(projected, context.project(original));
            verify(store).saveReference(run.id(), 7, 11, List.of(source));
            context.submitted(); context.submitted();
            verify(store, times(1)).markSubmitted(run.id(), 7, 11);
        }
    }

    @Test void rejectsMixedToolsAndNeverUsesModelBodyAsCode() {
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(tool("writeFile", "{\"query\":\"订单\"}")).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        try (var run = run()) {
            assertEquals("KNOWLEDGE_PROTOCOL_FAILED", assertThrows(KnowledgeException.class,
                    () -> preparation.prepare(run.id(), 7, 11, null, null, run)).getReasonCode());
        }
        verify(retrieval, never()).search(anyLong(), anyString());
        verify(model, times(2)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @Test void unavailableReferenceHeaderPreventsModelCall() {
        doThrow(new IllegalStateException("数据库不可用")).when(store).beginReference(anyString(), anyLong(), anyLong(), isNull(), isNull());
        try (var run = run()) {
            assertEquals("KNOWLEDGE_RECORD_UNAVAILABLE", assertThrows(KnowledgeException.class,
                    () -> preparation.prepare(run.id(), 7, 11, null, null, run)).getReasonCode());
        }
        verifyNoInteractions(model);
    }

    @Test void noMatchFailsImmediatelyWithoutProtocolRetry() {
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(tool("searchKnowledge", "{\"query\":\"订单\"}")).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(retrieval.search(7, "订单")).thenThrow(new KnowledgeException("KNOWLEDGE_NO_MATCH", "无命中"));
        try (var run = run()) {
            assertEquals("KNOWLEDGE_NO_MATCH", assertThrows(KnowledgeException.class,
                    () -> preparation.prepare(run.id(), 7, 11, null, null, run)).getReasonCode());
        }
        verify(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }
    @Test void rejectsExtraScopeAndMixedToolsButCoalescesIdenticalQueries() {
        var source = new KnowledgeTypes.Source(1, 2, 1, "文档.md", "build", "chunk", "订单规则", 0.9);
        for (AiMessage answer : List.of(tool("searchKnowledge", "{\"query\":\"订单\",\"ownerId\":8}"),
                AiMessage.from(List.of(ToolExecutionRequest.builder().name("searchKnowledge").arguments("{\"query\":\"订单\"}").build(),
                        ToolExecutionRequest.builder().name("writeFile").arguments("{}").build())))) {
            doAnswer(call -> { ((StreamingChatResponseHandler)call.getArgument(1)).onCompleteResponse(ChatResponse.builder().aiMessage(answer).build()); return null; })
                    .when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            try (var run = run()) { assertThrows(KnowledgeException.class, () -> preparation.prepare(run.id(), 7, 11, null, null, run)); }
        }
        verify(retrieval, never()).search(anyLong(), anyString());
        var duplicate = ToolExecutionRequest.builder().name("searchKnowledge").arguments("{\"query\":\"订单\"}").build();
        doAnswer(call -> { ((StreamingChatResponseHandler)call.getArgument(1)).onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(List.of(duplicate, duplicate))).build()); return null; })
                .when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(retrieval.search(7, "订单")).thenReturn(List.of(source));
        try (var run = run()) { assertNotNull(preparation.prepare(run.id(), 7, 11, null, null, run)); }
        verify(retrieval, times(1)).search(7, "订单");
    }

    @Test void unsafeModelLoggingPreventsPrivateRetrieval() {
        var environment = new org.springframework.mock.env.MockEnvironment().withProperty("langchain4j.open-ai.streaming-chat-model.log-requests", "true");
        org.springframework.test.util.ReflectionTestUtils.setField(preparation, "environment", environment);
        try (var run = run()) {
            assertEquals("KNOWLEDGE_LOGGING_UNSAFE", assertThrows(KnowledgeException.class,
                    () -> preparation.prepare(run.id(), 7, 11, null, null, run)).getReasonCode());
        }
        verifyNoInteractions(model, retrieval);
    }

    @Test void cancelledPlanningCannotAcceptLateToolResponse() {
        try (var run = run()) {
            doAnswer(call -> {
                run.cancel("用户取消");
                ((StreamingChatResponseHandler)call.getArgument(1)).onCompleteResponse(ChatResponse.builder()
                        .aiMessage(tool("searchKnowledge", "{\"query\":\"订单\"}")).build());
                return null;
            }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            assertThrows(RuntimeException.class, () -> preparation.prepare(run.id(), 7, 11, null, null, run));
            verify(retrieval, never()).search(anyLong(), anyString());
            verify(store, never()).saveReference(anyString(), anyLong(), anyLong(), anyList());
        }
    }

}
