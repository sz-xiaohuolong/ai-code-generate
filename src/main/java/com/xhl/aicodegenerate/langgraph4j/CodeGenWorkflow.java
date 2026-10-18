package com.xhl.aicodegenerate.langgraph4j;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.langgraph4j.model.QualityResult;
import com.xhl.aicodegenerate.langgraph4j.node.*;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.model.dto.ai.CodeGenStreamMessage;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.service.AppScreenshotService;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/** 复用代码生成能力，编排素材、生成、质检和构建，并发送真实节点进度。 */
@Slf4j
@Service
public class CodeGenWorkflow {
    private static final String IMAGE_COLLECTOR = "image_collector";
    private static final String PROMPT_ENHANCER = "prompt_enhancer";
    private static final String ROUTER = "router";
    private static final String CODE_GENERATOR = "code_generator";
    private static final String CODE_QUALITY_CHECK = "code_quality_check";
    private static final String PROJECT_BUILDER = "project_builder";
    private static final AtomicLong TEMP_APP_ID_GENERATOR = new AtomicLong(System.currentTimeMillis());

    public WorkflowContext executeWorkflow(String prompt) {
        return executeWorkflow(prompt, TEMP_APP_ID_GENERATOR.incrementAndGet(), 0L);
    }

    public WorkflowContext executeWorkflow(String prompt, Long appId, Long userId) {
        return executeWorkflow(prompt, appId, userId, null);
    }

    public WorkflowContext executeWorkflow(String prompt, Long appId, Long userId, CodeGenTypeEnum type) {
        try {
            return runWorkflow(initialContext(prompt, appId, userId, type), null, event -> {}, () -> false);
        } catch (Exception e) {
            throw workflowFailure(e);
        }
    }

    /** 应用入口：内部记忆隔离，正式历史保留一次原始输入和最终结果。 */
    public Flux<String> generateForApp(String prompt, Long appId, Long userId, CodeGenTypeEnum type,
                                       ChatMemory persistentMemory) {
        return generateForTask(prompt, new AppChatMemoryId(appId, userId), type, persistentMemory);
    }

    public Flux<String> generateForTask(String prompt, AppChatMemoryId memoryId, CodeGenTypeEnum type, ChatMemory persistentMemory) {
        Long appId = memoryId.getAppId(), userId = memoryId.getUserId();
        return Flux.defer(() -> {
            ChatMemory runMemory = MessageWindowChatMemory.builder().maxMessages(20).build();
            runMemory.add(persistentMemory.messages());
            persistentMemory.add(UserMessage.from(prompt));
            StringBuilder finalContent = new StringBuilder();
            AtomicBoolean recorded = new AtomicBoolean();
            AtomicBoolean qualitySkipped = new AtomicBoolean();
            return executeWorkflowEvents(prompt, appId, userId, type, id -> runMemory, memoryId.getTaskId())
                    .<String>handle((event, sink) -> {
                        if ("step_started".equals(event.eventType())
                                && CODE_GENERATOR.equals(event.data().get("id"))) {
                            finalContent.setLength(0);
                        }
                        if ("code_chunk".equals(event.eventType())) {
                            String text = String.valueOf(event.data().get("content"));
                            finalContent.append(text);
                            sink.next(JSONUtil.toJsonStr(CodeGenStreamMessage.aiResponse(text)));
                        } else {
                            if (CODE_QUALITY_CHECK.equals(event.data().get("id"))) {
                                qualitySkipped.set("skipped".equals(event.data().get("status")));
                            }
                            sink.next(JSONUtil.toJsonStr(event.data()));
                            if ("workflow_error".equals(event.eventType())) {
                                sink.error(new BusinessException(ErrorCode.SYSTEM_ERROR,
                                        String.valueOf(event.data().get("message"))));
                            }
                        }
                    })
                    .doOnComplete(() -> recordResult(persistentMemory, recorded,
                            finalContent + "\n\n工作流已完成，生成结果可预览。"
                                    + (qualitySkipped.get() ? "本次质检服务不可用，已跳过检查。" : "")))
                    .doOnError(error -> recordResult(persistentMemory, recorded,
                            "工作流生成失败：" + error.getMessage()))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            recordResult(persistentMemory, recorded, "工作流已中断，生成结果尚未确认可用。");
                        }
                    });
        });
    }

    public Flux<String> executeWorkflowWithFlux(String prompt) {
        return executeWorkflowWithFlux(prompt, TEMP_APP_ID_GENERATOR.incrementAndGet(), 0L);
    }

    public Flux<String> executeWorkflowWithFlux(String prompt, Long appId, Long userId) {
        return executeWorkflowEvents(prompt, appId, userId, null, null)
                .map(event -> formatSseEvent(event.eventType(), event.data()));
    }

    public Flux<ServerSentEvent<String>> executeWorkflowWithServerSentEvent(String prompt) {
        return executeWorkflowWithServerSentEvent(prompt, TEMP_APP_ID_GENERATOR.incrementAndGet(), 0L);
    }

    public Flux<ServerSentEvent<String>> executeWorkflowWithServerSentEvent(String prompt, Long appId, Long userId) {
        return executeWorkflowWithServerSentEvent(prompt, appId, userId, null);
    }

    public Flux<ServerSentEvent<String>> executeWorkflowWithServerSentEvent(String prompt, Long appId, Long userId,
                                                                          CodeGenTypeEnum type) {
        return executeWorkflowEvents(prompt, appId, userId, type, null)
                .map(event -> ServerSentEvent.builder(JSONUtil.toJsonStr(event.data())).event(event.eventType()).build());
    }

    private Flux<WorkflowStreamEvent> executeWorkflowEvents(String prompt, Long appId, Long userId,
                                                            CodeGenTypeEnum type, ChatMemoryProvider memoryProvider) {
        return executeWorkflowEvents(prompt, appId, userId, type, memoryProvider, null);
    }

    private Flux<WorkflowStreamEvent> executeWorkflowEvents(String prompt, Long appId, Long userId,
            CodeGenTypeEnum type, ChatMemoryProvider memoryProvider, String taskId) {
        return Flux.create(sink -> {
            Thread worker = Thread.ofVirtual().name("workflow-" + appId).unstarted(() -> {
                try {
                    sink.next(progress("workflow_start", "workflow", "工作流", "running", "开始执行工作流", 0));
                    WorkflowContext initial = initialContext(prompt, appId, userId, type);
                    initial.setTaskId(taskId);
                    runWorkflow(initial, memoryProvider, sink::next, sink::isCancelled);
                    if (!sink.isCancelled()) {
                        sink.next(progress("workflow_completed", "workflow", "工作流", "completed", "工作流已完成", 0));
                        sink.complete();
                    }
                } catch (Exception e) {
                    if (!sink.isCancelled()) {
                        BusinessException failure = workflowFailure(e);
                        log.warn("工作流失败：{}", failure.getMessage());
                        sink.next(progress("workflow_error", "workflow", "工作流", "failed", failure.getMessage(), 0));
                        sink.complete();
                    }
                }
            });
            sink.onCancel(worker::interrupt);
            worker.start();
        });
    }

    private WorkflowContext runWorkflow(WorkflowContext initial, ChatMemoryProvider memoryProvider,
                                         Consumer<WorkflowStreamEvent> events, BooleanSupplier cancelled) throws Exception {
        CompiledGraph<MessagesState<String>> graph = createWorkflow(events, cancelled, memoryProvider);
        WorkflowContext result = initial;
        for (NodeOutput<MessagesState<String>> step : graph.stream(WorkflowContext.saveContext(initial))) {
            checkCancelled(cancelled);
            WorkflowContext current = WorkflowContext.getContext(step.state());
            if (current != null) result = current;
        }
        if (StrUtil.isNotBlank(result.getErrorMessage())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, result.getErrorMessage());
        }
        if (result.getGenerationType() != CodeGenTypeEnum.VUE_PROJECT) {
            events.accept(progress("step_completed", PROJECT_BUILDER, "项目构建", "skipped", "静态页面无需构建", result.getQualityCheckRetryCount()));
            try {
                SpringContextUtil.getBean(AppScreenshotService.class)
                        .captureAppCoverAsync(result.getAppId(), result.getGeneratedCodeDir());
            } catch (Exception e) {
                log.debug("工作流完成后的封面截图不可用：{}", e.getMessage());
            }
        }
        return result;
    }

    public String getMermaidGraph() {
        try {
            return createWorkflow(event -> {}, () -> false, null).getGraph(GraphRepresentation.Type.MERMAID).content();
        } catch (GraphStateException e) {
            throw workflowFailure(e);
        }
    }

    private CompiledGraph<MessagesState<String>> createWorkflow(Consumer<WorkflowStreamEvent> events,
                                                                BooleanSupplier cancelled,
                                                                ChatMemoryProvider memoryProvider) throws GraphStateException {
        Consumer<String> content = chunk -> {
            checkCancelled(cancelled);
            events.accept(new WorkflowStreamEvent("code_chunk", Map.of("content", chunk)));
        };
        return new MessagesStateGraph<String>()
                .addNode(IMAGE_COLLECTOR, observed(IMAGE_COLLECTOR, "素材收集", ImageCollectorNode.create(), events, cancelled))
                .addNode(PROMPT_ENHANCER, observed(PROMPT_ENHANCER, "提示增强", PromptEnhancerNode.create(), events, cancelled))
                .addNode(ROUTER, observed(ROUTER, "确认代码类型", RouterNode.create(), events, cancelled))
                .addNode(CODE_GENERATOR, observed(CODE_GENERATOR, "代码生成", CodeGeneratorNode.create(content, memoryProvider), events, cancelled))
                .addNode(CODE_QUALITY_CHECK, observed(CODE_QUALITY_CHECK, "代码质检", CodeQualityCheckNode.create(), events, cancelled))
                .addNode(PROJECT_BUILDER, observed(PROJECT_BUILDER, "项目构建", ProjectBuilderNode.create(), events, cancelled))
                .addEdge(START, IMAGE_COLLECTOR)
                .addEdge(IMAGE_COLLECTOR, PROMPT_ENHANCER)
                .addEdge(PROMPT_ENHANCER, ROUTER)
                .addEdge(ROUTER, CODE_GENERATOR)
                .addEdge(CODE_GENERATOR, CODE_QUALITY_CHECK)
                .addConditionalEdges(CODE_QUALITY_CHECK, edge_async(this::routeAfterQualityCheck),
                        Map.of("build", PROJECT_BUILDER, "skip_build", END, "fail", CODE_GENERATOR, "stop", END))
                .addEdge(PROJECT_BUILDER, END).compile();
    }

    private AsyncNodeAction<MessagesState<String>> observed(String id, String name,
                                                            AsyncNodeAction<MessagesState<String>> action,
                                                            Consumer<WorkflowStreamEvent> events,
                                                            BooleanSupplier cancelled) {
        return state -> {
            checkCancelled(cancelled);
            WorkflowContext before = WorkflowContext.getContext(state);
            int retryCount = before == null ? 0 : before.getQualityCheckRetryCount();
            events.accept(progress("step_started", id, name, "running", "正在" + name, retryCount));
            return action.apply(state).thenApply(update -> {
                checkCancelled(cancelled);
                WorkflowContext after = WorkflowContext.getContext(new MessagesState<>(update));
                String status = "completed";
                String message = name + "已完成";
                if (StrUtil.isNotBlank(after.getErrorMessage())) {
                    events.accept(progress("step_completed", id, name, "failed", after.getErrorMessage(), retryCount));
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, after.getErrorMessage());
                }
                if (CODE_QUALITY_CHECK.equals(id)) {
                    if (StrUtil.isNotBlank(after.getQualityCheckSkippedReason())) {
                        status = "skipped";
                        message = after.getQualityCheckSkippedReason();
                    } else if (after.getQualityResult() == null || !Boolean.TRUE.equals(after.getQualityResult().getIsValid())) {
                        if (after.getQualityCheckRetryCount() >= WorkflowContext.MAX_QUALITY_RETRY_COUNT) {
                            events.accept(progress("step_completed", id, name, "failed", "代码质检失败，已达到最大重试次数", retryCount));
                            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "代码质检失败，已达到最大重试次数");
                        }
                        status = "retrying";
                        message = "质检未通过，准备第 " + (after.getQualityCheckRetryCount() + 1) + " 次修复";
                    }
                }
                events.accept(progress("step_completed", id, name, status, message, after.getQualityCheckRetryCount()));
                return update;
            });
        };
    }

    String routeAfterQualityCheck(MessagesState<String> state) {
        WorkflowContext context = WorkflowContext.getContext(state);
        if (context == null || StrUtil.isNotBlank(context.getErrorMessage())) return "stop";
        QualityResult result = context.getQualityResult();
        if (result == null || !Boolean.TRUE.equals(result.getIsValid())) {
            return context.getQualityCheckRetryCount() >= WorkflowContext.MAX_QUALITY_RETRY_COUNT ? "stop" : "fail";
        }
        return routeBuildOrSkip(state);
    }

    String routeBuildOrSkip(MessagesState<String> state) {
        WorkflowContext context = WorkflowContext.getContext(state);
        if (context == null || context.getGenerationType() == null) return "skip_build";
        return switch (context.getGenerationType()) {
            case VUE_PROJECT -> "build";
            case HTML, MULTI_FILE -> "skip_build";
        };
    }

    private static WorkflowContext initialContext(String prompt, Long appId, Long userId, CodeGenTypeEnum type) {
        return WorkflowContext.builder().currentStep("初始化").originalPrompt(prompt)
                .appId(appId).userId(userId).generationType(type).build();
    }

    private static WorkflowStreamEvent progress(String event, String id, String name, String status, String message, int retryCount) {
        return new WorkflowStreamEvent(event, Map.of("type", "workflow_progress", "id", id, "name", name,
                "status", status, "message", message, "retryCount", retryCount));
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("工作流已中断");
    }

    private static BusinessException workflowFailure(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error instanceof BusinessException business ? business :
                new BusinessException(ErrorCode.SYSTEM_ERROR, "工作流执行失败：" + StrUtil.blankToDefault(error.getMessage(), "未知错误"));
    }

    private static void recordResult(ChatMemory memory, AtomicBoolean recorded, String content) {
        if (recorded.compareAndSet(false, true)) memory.add(AiMessage.from(content));
    }

    static String formatSseEvent(String eventType, Object data) {
        return "event: " + eventType + "\ndata: " + JSONUtil.toJsonStr(data) + "\n\n";
    }

    record WorkflowStreamEvent(String eventType, Map<String, Object> data) {}
}
