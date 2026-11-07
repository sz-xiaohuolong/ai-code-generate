package com.xhl.aicodegenerate.observability;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.Set;
import java.util.function.Supplier;

/** 每次路由绑定自己的追踪，共享模型客户端不保存当前请求的可变上下文。 */
public final class TracedChatModel implements ChatModel {
    private final ChatModel delegate;
    private final TraceSession trace;
    private final String alias;

    public TracedChatModel(ChatModel delegate, TraceSession trace, String alias) {
        this.delegate = delegate;
        this.trace = trace;
        this.alias = alias;
    }

    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
    @Override public ModelProvider provider() { return delegate.provider(); }

    @Override public ChatResponse chat(ChatRequest request) {
        return observe(request, () -> delegate.chat(request));
    }

    @Override public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return observe(request, () -> delegate.chat(request, options));
    }

    private ChatResponse observe(ChatRequest request, Supplier<ChatResponse> call) {
        var parameters = request.parameters() == null ? defaultRequestParameters()
                : request.parameters().defaultedBy(defaultRequestParameters());
        var step = TraceModelMetadata.start(trace, "routing_model", alias, parameters, null);
        try {
            ChatResponse response = call.get();
            TraceModelMetadata.complete(step, response);
            return response;
        } catch (RuntimeException error) {
            if (step != null) {
                step.fail(error);
            }
            throw error;
        }
    }
}
