package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import java.util.Set;
import java.util.concurrent.*;

/** 工作流结构化调用也经可取消的模型流，统一计入任务预算和实际用量。 */
public final class TaskChatModel implements ChatModel {
    private final StreamingChatModel model;
    private final GenerationExecution execution;
    public TaskChatModel(StreamingChatModel model, GenerationExecution execution) {
        this.model = UsageTrackingStreamingModel.wrap(model, new AppChatMemoryId(0L, 0L, execution.id()));
        this.execution = execution;
    }
    @Override public ChatRequestParameters defaultRequestParameters() { return model.defaultRequestParameters(); }
    @Override public Set<Capability> supportedCapabilities() { return model.supportedCapabilities(); }
    @Override public ModelProvider provider() { return model.provider(); }
    @Override public ChatResponse doChat(ChatRequest request) {
        execution.checkActive();
        var response = new CompletableFuture<ChatResponse>();
        Runnable registration = execution.onCancel(() -> response.completeExceptionally(new CancellationException("任务已停止")));
        try {
            model.chat(request, new StreamingChatResponseHandler() {
                @Override public void onCompleteResponse(ChatResponse value) { response.complete(value); }
                @Override public void onError(Throwable error) { response.completeExceptionally(error); }
            });
            return response.get(execution.remaining().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException("任务已停止"); }
        catch (TimeoutException error) { throw new GenerationExecution.BudgetExceeded(); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("模型调用失败", error.getCause());
        } finally { registration.run(); }
    }
}
