package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import java.util.Set;

/** 记录生成／修复实际响应的用量，保留取消上下文与模型能力。 */
public final class UsageTrackingStreamingModel implements StreamingChatModel {
    private final StreamingChatModel delegate;
    private final GenerationExecution execution;
    private UsageTrackingStreamingModel(StreamingChatModel delegate, GenerationExecution execution) { this.delegate = delegate; this.execution = execution; }
    public static StreamingChatModel wrap(StreamingChatModel model, AppChatMemoryId id) {
        return id.getTaskId() == null ? model : new UsageTrackingStreamingModel(model, GenerationExecution.require(id.getTaskId()));
    }
    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
    @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        execution.modelStarted(); delegate.chat(request, observe(handler));
    }
    @Override public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
        execution.modelStarted(); delegate.chat(request, options, observe(handler));
    }
    private StreamingChatResponseHandler observe(StreamingChatResponseHandler target) {
        return new StreamingChatResponseHandler() {
            private final Set<StreamingHandle> handles = java.util.concurrent.ConcurrentHashMap.newKeySet();
            private void track(StreamingHandle handle) { if (handles.add(handle)) execution.onCancel(handle::cancel); execution.checkActive(); }
            @Override public void onPartialResponse(String value) { execution.checkActive(); target.onPartialResponse(value); }
            @Override public void onPartialResponse(PartialResponse value, PartialResponseContext context) { track(context.streamingHandle()); target.onPartialResponse(value, context); }
            @Override public void onPartialThinking(PartialThinking value) { target.onPartialThinking(value); }
            @Override public void onPartialThinking(PartialThinking value, PartialThinkingContext context) { track(context.streamingHandle()); target.onPartialThinking(value, context); }
            @Override public void onPartialToolCall(PartialToolCall value) { target.onPartialToolCall(value); }
            @Override public void onPartialToolCall(PartialToolCall value, PartialToolCallContext context) { track(context.streamingHandle()); target.onPartialToolCall(value, context); }
            @Override public void onCompleteToolCall(CompleteToolCall value) { target.onCompleteToolCall(value); }
            @Override public void onCompleteResponse(ChatResponse value) { execution.usage(value.tokenUsage()); target.onCompleteResponse(value); }
            @Override public void onError(Throwable error) { target.onError(error); }
        };
    }
}
