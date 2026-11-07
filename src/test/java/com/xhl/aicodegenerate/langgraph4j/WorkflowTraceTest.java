package com.xhl.aicodegenerate.langgraph4j;

import com.xhl.aicodegenerate.entity.GenerationTraceStep;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.observability.GenerationTraceRecorder;
import com.xhl.aicodegenerate.observability.TraceSession;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowTraceTest {
    @TempDir Path root;

    @Test void taskCleanupWaitsForWorkflowWorkerToFinishBeforeTraceCanClose() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var finalized = new java.util.concurrent.CountDownLatch(1);
        try (var run = new GenerationExecution("workflow-cleanup", root, Duration.ofSeconds(5), () -> true)) {
            reactor.core.publisher.Flux<CodeGenWorkflow.WorkflowStreamEvent> stream = ReflectionTestUtils.invokeMethod(
                    new CodeGenWorkflow(), "executeWorkflowEvents", "需求", 1L, 1L,
                    com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum.HTML, null, run.id());
            var subscription = stream.subscribe(event -> {
                entered.countDown();
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException expected) {
                    // 模拟节点退出前尚需完成的有界收尾，不能在此期间结束根追踪。
                    java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(250).toNanos());
                } finally { finalized.countDown(); }
                throw new java.util.concurrent.CancellationException("测试主动停止");
            });
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            subscription.dispose();
            run.close();
            assertEquals(0, finalized.getCount(), "任务清理返回前必须等待工作流线程结束");
        } finally { finalized.await(2, java.util.concurrent.TimeUnit.SECONDS); }
    }

    @Test void repeatedNodesAndForkedCallsKeepTheirOwnParentAcrossThreads() throws Exception {
        List<GenerationTraceStep> steps = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (var recorder = new GenerationTraceRecorder((trace, step) -> {
            if (step != null) { steps.removeIf(old -> old.getSpanId().equals(step.getSpanId())); steps.add(step); }
        }, Clock.systemUTC(), 64);
             var run = new GenerationExecution("workflow-trace", root, Duration.ofSeconds(5), () -> true)) {
            run.attachTrace(recorder.startGeneration(run.id(), 1L, 1L, "workflow", "html"));
            var context = WorkflowContext.builder().taskId(run.id()).appId(1L).userId(1L).build();
            AsyncNodeAction<MessagesState<String>> action = state -> run.fork(() -> {
                var call = run.traceStep(TraceSession.Kind.MODEL, "fixture_model");
                call.complete(1L, 2L, "STOP");
                return WorkflowContext.saveContext(WorkflowContext.getContext(state));
            });
            Consumer<CodeGenWorkflow.WorkflowStreamEvent> events = event -> { };
            BooleanSupplier cancelled = () -> false;
            AsyncNodeAction<MessagesState<String>> observed = ReflectionTestUtils.invokeMethod(new CodeGenWorkflow(),
                    "observed", "code_generator", "代码生成", action, events, cancelled);
            observed.apply(new MessagesState<>(WorkflowContext.saveContext(context))).join();
            observed.apply(new MessagesState<>(WorkflowContext.saveContext(context))).join();
            run.trace().finish("SUCCEEDED");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            var stages = steps.stream().filter(step -> "STAGE".equals(step.getStepKind()))
                    .sorted(java.util.Comparator.comparingLong(GenerationTraceStep::getStepNo)).toList();
            var calls = steps.stream().filter(step -> "MODEL".equals(step.getStepKind()))
                    .sorted(java.util.Comparator.comparingLong(GenerationTraceStep::getStepNo)).toList();
            assertEquals(2, stages.size());
            assertEquals(2, stages.get(1).getAttemptNo());
            assertEquals(stages.get(0).getSpanId(), calls.get(0).getParentSpanId());
            assertEquals(stages.get(1).getSpanId(), calls.get(1).getParentSpanId());
            assertTrue(stages.stream().allMatch(step -> "SUCCEEDED".equals(step.getStatus())));
        }
    }
}
