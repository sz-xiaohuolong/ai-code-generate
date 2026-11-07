package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.observability.TraceModelMetadata;
import com.xhl.aicodegenerate.observability.TraceSession;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import io.opentelemetry.context.Context;

import java.util.Set;
import java.util.function.Consumer;

/** 每次实际流式调用独立计量和终结，显式捕获上下文以隔离并发任务。 */
public final class UsageTrackingStreamingModel implements StreamingChatModel {
    private final StreamingChatModel delegate;
    private final GenerationExecution execution;
    private final boolean includeKnowledge;
    private final TraceSession.Step parent;

    private UsageTrackingStreamingModel(StreamingChatModel delegate, GenerationExecution execution, boolean includeKnowledge) {
        this.delegate = delegate;
        this.execution = execution;
        this.includeKnowledge = includeKnowledge;
        this.parent = execution.trace() == null ? null : execution.trace().currentStep();
    }

    public static StreamingChatModel wrap(StreamingChatModel model, AppChatMemoryId id) {
        return wrap(model, id, true);
    }

    public static StreamingChatModel wrap(StreamingChatModel model, AppChatMemoryId id, boolean includeKnowledge) {
        if (id.getTaskId() == null) return model;
        GenerationExecution execution = GenerationExecution.require(id.getTaskId());
        if (model instanceof UsageTrackingStreamingModel observed) {
            if (observed.execution == execution && observed.includeKnowledge == includeKnowledge) return observed;
            model = observed.delegate;
        }
        return new UsageTrackingStreamingModel(model, execution, includeKnowledge);
    }

    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
    @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        ChatRequest projected = project(request);
        invoke(projected, handler, observed -> delegate.chat(projected, observed));
    }
    @Override public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
        ChatRequest projected = project(request);
        invoke(projected, handler, observed -> delegate.chat(projected, options, observed));
    }

    private ChatRequest project(ChatRequest request) {
        execution.assertGenerationAllowed();
        var knowledge = execution.knowledge();
        if (!includeKnowledge || knowledge == null) return request;
        knowledge.persist();
        return knowledge.project(request);
    }

    private void invoke(ChatRequest request, StreamingChatResponseHandler handler, Consumer<Observation> call) {
        execution.modelStarted();
        var parameters = request.parameters() == null ? defaultRequestParameters()
                : request.parameters().defaultedBy(defaultRequestParameters());
        var currentParent = execution.trace() == null ? null : execution.trace().currentStep();
        var step = TraceModelMetadata.start(execution.trace(), "streaming_model", "generation", parameters,
                currentParent == null ? parent : currentParent);
        var observed = new Observation(handler, step);
        try {
            execution.checkActive();
            call.accept(observed);
        } catch (RuntimeException error) {
            observed.failed(error);
            throw error;
        }
    }

    private final class Observation implements StreamingChatResponseHandler {
        private final StreamingChatResponseHandler target;
        private final TraceSession.Step step;
        private final Context context;
        private final GenerationExecution.ModelUsage usage = execution.newModelUsage();
        private volatile boolean terminal;
        private final Set<StreamingHandle> handles = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private Observation(StreamingChatResponseHandler target, TraceSession.Step step) {
            this.target = target;
            this.step = step;
            this.context = step == null ? execution.traceContext() : step.context();
            execution.onCancel(this::cancelled);
        }

        private synchronized void cancelled() {
            if (!terminal) {
                terminal = true;
                if (step != null) step.cancel();
            }
        }

        private void track(StreamingHandle handle) {
            if (handles.add(handle)) execution.onCancel(handle::cancel);
            execution.checkActive();
        }

        private void partial(Runnable action) {
            if (terminal) return;
            execution.checkActive();
            if (includeKnowledge && execution.knowledge() != null) execution.knowledge().submitted();
            try (var ignored = context.makeCurrent()) { action.run(); }
        }

        @Override public void onPartialResponse(String value) { partial(() -> target.onPartialResponse(value)); }
        @Override public void onPartialResponse(PartialResponse value, PartialResponseContext context) {
            track(context.streamingHandle()); partial(() -> target.onPartialResponse(value, context));
        }
        @Override public void onPartialThinking(PartialThinking value) { partial(() -> target.onPartialThinking(value)); }
        @Override public void onPartialThinking(PartialThinking value, PartialThinkingContext context) {
            track(context.streamingHandle()); partial(() -> target.onPartialThinking(value, context));
        }
        @Override public void onPartialToolCall(PartialToolCall value) { partial(() -> target.onPartialToolCall(value)); }
        @Override public void onPartialToolCall(PartialToolCall value, PartialToolCallContext context) {
            track(context.streamingHandle()); partial(() -> target.onPartialToolCall(value, context));
        }
        @Override public void onCompleteToolCall(CompleteToolCall value) { partial(() -> target.onCompleteToolCall(value)); }

        @Override public void onCompleteResponse(ChatResponse value) {
            boolean deliver;
            synchronized (this) {
                deliver = !terminal;
                terminal = true;
                usage.record(value == null ? null : value.tokenUsage());
                TraceModelMetadata.complete(step, value);
            }
            if (deliver) {
                if (includeKnowledge && execution.knowledge() != null) execution.knowledge().submitted();
                try (var ignored = context.makeCurrent()) { target.onCompleteResponse(value); }
            }
        }

        private synchronized boolean failed(Throwable error) {
            if (terminal) return false;
            if (includeKnowledge && execution.knowledge() != null) execution.knowledge().unconfirmed();
            terminal = true;
            if (step != null) step.fail(error);
            return true;
        }

        @Override public void onError(Throwable error) {
            if (failed(error)) {
                try (var ignored = context.makeCurrent()) { target.onError(error); }
            }
        }
    }
}
