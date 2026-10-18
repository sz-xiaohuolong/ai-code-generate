package com.xhl.aicodegenerate.core;

import com.xhl.aicodegenerate.ai.AiCodeGeneratorService;
import com.xhl.aicodegenerate.ai.AiCodeGeneratorServiceFactory;
import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowTokenStreamCancellationTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cancellationStopsRealAdapterAndIgnoresLateCompletion() {
        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade();
        AiCodeGeneratorServiceFactory factory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService service = mock(AiCodeGeneratorService.class);
        TokenStream tokenStream = mock(TokenStream.class, RETURNS_SELF);
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
        ReflectionTestUtils.setField(facade, "vueProjectBuilder", builder);
        when(factory.createAiCodeGeneratorService(any(), any())).thenReturn(service);
        when(service.generateVueProjectCodeTokenStream(any(), any())).thenReturn(tokenStream);

        var subscription = facade.generateAndSaveCodeStream("测试", CodeGenTypeEnum.VUE_PROJECT,
                new AppChatMemoryId(123L, 1L)).subscribe();
        ArgumentCaptor<BiConsumer<PartialResponse, PartialResponseContext>> partial = ArgumentCaptor.forClass(BiConsumer.class);
        verify(tokenStream).onPartialResponseWithContext(partial.capture());
        StreamingHandle handle = mock(StreamingHandle.class);
        partial.getValue().accept(new PartialResponse("开始"), new PartialResponseContext(handle));
        subscription.dispose();
        verify(handle).cancel();

        ArgumentCaptor<Consumer> completed = ArgumentCaptor.forClass(Consumer.class);
        verify(tokenStream).onCompleteResponse(completed.capture());
        completed.getValue().accept(null);
        verifyNoInteractions(builder);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cancellationBeforeFirstTokenCancelsWhenContextArrives() {
        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade();
        AiCodeGeneratorServiceFactory factory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService service = mock(AiCodeGeneratorService.class);
        TokenStream tokenStream = mock(TokenStream.class, RETURNS_SELF);
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
        when(factory.createAiCodeGeneratorService(any(), any(), any(ChatMemoryProvider.class))).thenReturn(service);
        when(service.generateVueProjectCodeTokenStream(any(), any())).thenReturn(tokenStream);
        var subscription = facade.generateAndSaveCodeStreamForWorkflow("测试", CodeGenTypeEnum.VUE_PROJECT,
                new AppChatMemoryId(123L, 1L), mock(ChatMemoryProvider.class)).subscribe();
        subscription.dispose();
        ArgumentCaptor<BiConsumer<PartialResponse, PartialResponseContext>> partial = ArgumentCaptor.forClass(BiConsumer.class);
        verify(tokenStream).onPartialResponseWithContext(partial.capture());
        StreamingHandle handle = mock(StreamingHandle.class);
        partial.getValue().accept(new PartialResponse("迟到"), new PartialResponseContext(handle));
        verify(handle).cancel();
    }
}
