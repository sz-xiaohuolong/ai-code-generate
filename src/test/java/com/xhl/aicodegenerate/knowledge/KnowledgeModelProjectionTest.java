package com.xhl.aicodegenerate.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import com.xhl.aicodegenerate.generation.UsageTrackingStreamingModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeModelProjectionTest {
    @Test void everyGenerationAndRepairRequestReceivesFixedSourcesOutsideMemory() {
        var model = mock(StreamingChatModel.class);
        when(model.defaultRequestParameters()).thenReturn(DefaultChatRequestParameters.builder().build());
        var store = mock(KnowledgeStore.class);
        try (var run = new GenerationExecution(UUID.randomUUID().toString(), Path.of("target/projection"), Duration.ofSeconds(5), () -> true)) {
            run.requireKnowledge();
            var wrapped = UsageTrackingStreamingModel.wrap(model, new AppChatMemoryId(1L, 7L, run.id()));
            var handler = mock(StreamingChatResponseHandler.class);
            var first = ChatRequest.builder().messages(UserMessage.from("生成页面")).build();
            assertThrows(KnowledgeException.class, () -> wrapped.chat(first, handler));
            verify(model, never()).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            var source = new KnowledgeTypes.Source(1, 2, 1, "资料.md", "build", "chunk", "POST /orders/cancel", 0.9);
            run.attachKnowledge(new KnowledgeContext(store, run.id(), 7, 1, List.of(source), new ObjectMapper()));
            wrapped.chat(first, handler);
            wrapped.chat(ChatRequest.builder().messages(UserMessage.from("修复构建错误")).build(), handler);
            var requests = ArgumentCaptor.forClass(ChatRequest.class);
            var handlers = ArgumentCaptor.forClass(StreamingChatResponseHandler.class);
            verify(model, times(2)).chat(requests.capture(), handlers.capture());
            assertEquals(1, first.messages().size());
            for (var request : requests.getAllValues()) assertTrue(request.messages().toString().contains("POST /orders/cancel"));
            handlers.getAllValues().getFirst().onPartialResponse("代码");
            verify(store).markSubmitted(run.id(), 7, 1);
            verify(store).saveReference(run.id(), 7, 1, List.of(source));
        }
    }
    @Test void imagePlanningNeverReceivesPrivateFragmentsOrMarksCodeSubmission() {
        var model = mock(StreamingChatModel.class);
        when(model.defaultRequestParameters()).thenReturn(DefaultChatRequestParameters.builder().build());
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("{\"contentImageTasks\":[],\"logoTasks\":[],\"diagramTasks\":[]}")).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var store = mock(KnowledgeStore.class);
        var services = new com.xhl.aicodegenerate.generation.TaskChatServices();
        org.springframework.test.util.ReflectionTestUtils.setField(services, "model", model);
        try (var run = new GenerationExecution(UUID.randomUUID().toString(), Path.of("target/image-projection"), Duration.ofSeconds(5), () -> true)) {
            run.requireKnowledge();
            var source = new KnowledgeTypes.Source(1, 2, 1, "私有.md", "build", "chunk", "PRIVATE_ORDER_2026", 0.9);
            run.attachKnowledge(new KnowledgeContext(store, run.id(), 7, 1, List.of(source), new ObjectMapper()));
            services.create(com.xhl.aicodegenerate.langgraph4j.ai.ImageCollectionPlanService.class, run.id()).planImageCollection("订单页面");
            var request = ArgumentCaptor.forClass(ChatRequest.class);
            verify(model).chat(request.capture(), any(StreamingChatResponseHandler.class));
            assertFalse(request.getValue().messages().toString().contains("PRIVATE_ORDER_2026"));
            verifyNoInteractions(store);
        }
    }

}
