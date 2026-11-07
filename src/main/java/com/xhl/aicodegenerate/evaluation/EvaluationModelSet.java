package com.xhl.aicodegenerate.evaluation;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.Objects;
import java.util.Set;

/** 所有模型槽位显式注入并共用批次预算；包含路由、回退、工作流结构化与修复调用。 */
public record EvaluationModelSet(ChatModel primary, ChatModel routing,
        StreamingChatModel standard, StreamingChatModel reasoning) {
    public EvaluationModelSet {
        Objects.requireNonNull(primary); Objects.requireNonNull(routing);
        Objects.requireNonNull(standard); Objects.requireNonNull(reasoning);
    }

    public static EvaluationModelSet budgeted(ChatModel primary, ChatModel routing,
            StreamingChatModel standard, StreamingChatModel reasoning, EvaluationBudget budget) {
        var wrappedPrimary = wrap(primary, budget);
        var wrappedStandard = wrap(standard, budget);
        return new EvaluationModelSet(wrappedPrimary, routing == primary ? wrappedPrimary : wrap(routing, budget),
                wrappedStandard, reasoning == standard ? wrappedStandard : wrap(reasoning, budget));
    }

    private static ChatModel wrap(ChatModel delegate, EvaluationBudget budget) {
        return new ChatModel() {
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
            @Override public ModelProvider provider() { return delegate.provider(); }
            @Override public ChatResponse doChat(ChatRequest request) {
                budget.reserveModelCall();
                return delegate.chat(request);
            }
        };
    }

    private static StreamingChatModel wrap(StreamingChatModel delegate, EvaluationBudget budget) {
        return new StreamingChatModel() {
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
            @Override public ModelProvider provider() { return delegate.provider(); }
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                budget.reserveModelCall();
                delegate.chat(request, handler);
            }
        };
    }
}
