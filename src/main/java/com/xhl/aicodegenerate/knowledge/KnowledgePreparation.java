package com.xhl.aicodegenerate.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhl.aicodegenerate.ai.guardrail.PromptSafetyInputGuardrail;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/** 规划模型只能提出查询。真实检索由程序执行，成功后才产生代码生成所需的知识上下文。 */
@Service
public class KnowledgePreparation {
    private static final ToolSpecification SEARCH = ToolSpecification.builder().name("searchKnowledge")
            .description("在当前用户本人已发布的知识文档中查询本轮生成所需的接口和业务规则")
            .parameters(JsonObjectSchema.builder().addStringProperty("query", "与当前需求相关的语义查询，1～2000 字符")
                    .required("query").additionalProperties(false).build()).build();
    private final KnowledgeStore store;
    private final KnowledgeRetrievalService retrieval;
    private final StreamingChatModel model;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.core.env.Environment environment;

    public KnowledgePreparation(KnowledgeStore store, KnowledgeRetrievalService retrieval,
                                @Qualifier("reasoningStreamingChatModel") StreamingChatModel model, ObjectMapper mapper) {
        this.store = store; this.retrieval = retrieval; this.model = model; this.mapper = mapper;
    }

    public KnowledgeContext prepare(String taskId, long ownerId, long appId, Long userMessageId,
                                     Long aiMessageId, GenerationExecution run) {
        run.checkActive();
        if (!new PromptSafetyInputGuardrail().validate(UserMessage.from(run.requirements().currentPrompt())).failures().isEmpty())
            throw new KnowledgeException("KNOWLEDGE_INPUT_REJECTED", "输入未通过安全检查，请修改后重试");
        try { store.beginReference(taskId, ownerId, appId, userMessageId, aiMessageId); }
        catch (RuntimeException error) { throw new KnowledgeException("KNOWLEDGE_RECORD_UNAVAILABLE", "参考记录暂不可用，已停止生成，请稍后重试"); }
        try {
            if (environment != null && (environment.getProperty("langchain4j.open-ai.streaming-chat-model.log-requests", Boolean.class, false)
                    || environment.getProperty("langchain4j.open-ai.streaming-chat-model.log-responses", Boolean.class, false)))
                throw new KnowledgeException("KNOWLEDGE_LOGGING_UNSAFE", "请先关闭生成模型的原始请求及响应日志，再使用个人知识库");
            retrieval.requireAvailable(ownerId);
            String query = null;
            for (int attempt = 0; attempt < 2 && query == null; attempt++) {
                String correction = attempt == 0 ? "" : "\n上一次未完成合法工具请求。请只调用 searchKnowledge，参数只能包含 query；不能直接写代码或调用其他工具。";
                ChatRequest request = ChatRequest.builder().messages(
                        SystemMessage.from("根据用户需求组织知识库检索查询。生成代码前必须调用 searchKnowledge。不要生成代码，不要请求文件工具，身份与范围由后端决定。" + correction),
                        UserMessage.from(run.requirements().describe())).toolSpecifications(SEARCH).build();
                query = query(complete(request, run));
            }
            if (query == null) throw new KnowledgeException("KNOWLEDGE_PROTOCOL_FAILED", "模型未完成必要检索，已停止生成；请检查模型工具调用能力");
            run.checkActive();
            var sources = retrieval.search(ownerId, query);
            run.checkActive();
            var context = new KnowledgeContext(store, taskId, ownerId, appId, sources, mapper);
            context.persist();
            return context;
        } catch (RuntimeException error) {
            try { store.markReferenceFailure(taskId, ownerId, appId, "FAILED"); } catch (RuntimeException ignored) { }
            throw error;
        }
    }

    private String query(ChatResponse response) {
        if (response == null || response.aiMessage() == null || !response.aiMessage().hasToolExecutionRequests()) return null;
        Set<String> queries = new LinkedHashSet<>();
        for (var call : response.aiMessage().toolExecutionRequests()) {
            if (!"searchKnowledge".equals(call.name()) || call.arguments() == null || call.arguments().length() > 16_384) return null;
            try {
                JsonNode arguments = mapper.readTree(call.arguments());
                if (!arguments.isObject() || arguments.size() != 1 || !arguments.path("query").isTextual()) return null;
                String value = arguments.path("query").asText().trim();
                if (value.isEmpty() || value.length() > 2000) return null;
                queries.add(value);
            } catch (Exception error) { return null; }
        }
        return queries.size() == 1 ? queries.iterator().next() : null;
    }

    private ChatResponse complete(ChatRequest request, GenerationExecution run) {
        run.modelStarted();
        var usage = run.newModelUsage();
        var result = new CompletableFuture<ChatResponse>();
        var handles = ConcurrentHashMap.<StreamingHandle>newKeySet();
        Runnable unregister = run.onCancel(() -> {
            handles.forEach(StreamingHandle::cancel);
            result.completeExceptionally(new CancellationException("知识检索已取消"));
        });
        try {
            model.chat(request, new StreamingChatResponseHandler() {
                private void track(StreamingHandle handle) {
                    handles.add(handle);
                    if (result.isDone()) handle.cancel();
                }
                @Override public void onPartialResponse(String value) { }
                @Override public void onPartialResponse(PartialResponse value, PartialResponseContext context) { track(context.streamingHandle()); }
                @Override public void onPartialThinking(PartialThinking value, PartialThinkingContext context) { track(context.streamingHandle()); }
                @Override public void onPartialToolCall(PartialToolCall value, PartialToolCallContext context) { track(context.streamingHandle()); }
                @Override public void onCompleteResponse(ChatResponse value) {
                    usage.record(value == null ? null : value.tokenUsage());
                    result.complete(value);
                }
                @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            });
            ChatResponse response = result.get(run.remaining().toMillis(), TimeUnit.MILLISECONDS);
            run.checkActive();
            return response;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("知识检索已取消");
        } catch (TimeoutException error) {
            throw new GenerationExecution.BudgetExceeded();
        } catch (CancellationException | GenerationExecution.BudgetExceeded error) {
            throw error;
        } catch (ExecutionException | RuntimeException error) {
            var safe = new KnowledgeException("KNOWLEDGE_PLANNER_UNAVAILABLE", "知识查询模型暂不可用或不支持工具调用，请检查配置");
            throw safe;
        } finally {
            handles.forEach(StreamingHandle::cancel);
            result.cancel(false);
            unregister.run();
        }
    }
}
