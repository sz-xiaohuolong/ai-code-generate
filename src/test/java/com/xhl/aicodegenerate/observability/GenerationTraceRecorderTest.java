package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.GenerationTraceStep;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 校验持久化可观察结果，避免重复回调、异步执行和存储故障制造成功记录。 */
class GenerationTraceRecorderTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void keepsRepeatedStagesAndExplicitParentsAcrossThreads() throws Exception {
        var store = new MemoryStore();
        try (var recorder = new GenerationTraceRecorder(store, CLOCK, 32)) {
            var session = recorder.startGeneration("task-1", 7L, 9L, "standard", "html");
            var first = session.startStep(TraceSession.Kind.STAGE, "generate", null);
            var second = session.startStep(TraceSession.Kind.STAGE, "generate", null);
            Thread thread = Thread.ofVirtual().start(() -> {
                var call = session.startStep(TraceSession.Kind.MODEL, "generation_model", first);
                call.complete(12L, null, "STOP");
            });
            thread.join();
            first.succeed();
            second.succeed();
            session.finish("SUCCEEDED");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            var root = store.latestRoot();
            var steps = store.finalSteps();
            assertTrue(root.getTraceId().matches("[0-9a-f]{32}"));
            assertNotEquals("0".repeat(32), root.getTraceId());
            assertEquals(3, steps.size());
            assertEquals(1, steps.get(0).getAttemptNo());
            assertEquals(2, steps.get(1).getAttemptNo());
            assertEquals(steps.get(0).getSpanId(), steps.get(2).getParentSpanId());
            assertEquals("COMPLETE", root.getRecordingStatus());
            assertEquals(Instant.parse("2026-11-14T12:00:00Z"), root.getExpiresAt());
        }
    }

    @Test
    void duplicateAndLateCallbacksDoNotDoubleCountOrRewriteExecutionResult() {
        var store = new MemoryStore();
        try (var recorder = new GenerationTraceRecorder(store, CLOCK, 32)) {
            var session = recorder.startGeneration("task-2", 7L, 9L, "workflow", "vue_project");
            var first = session.startStep(TraceSession.Kind.MODEL, "model", null);
            var second = session.startStep(TraceSession.Kind.MODEL, "model", null);
            first.complete(12L, null, "STOP");
            first.complete(12L, null, "STOP");
            first.fail(new RuntimeException("secret-key=/Users/private"));
            second.cancel();
            session.finish("CANCELLED");
            second.complete(null, 8L, "STOP");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            var root = store.latestRoot();
            assertEquals("CANCELLED", root.getExecutionStatus());
            assertEquals(2, root.getModelCalls());
            assertEquals(1, root.getInputReportedCalls());
            assertEquals(1, root.getOutputReportedCalls());
            assertEquals(12L, root.getKnownInputTokens());
            assertEquals(8L, root.getKnownOutputTokens());
            assertEquals("SUCCEEDED", store.finalSteps().get(0).getStatus());
            assertEquals("CANCELLED", store.finalSteps().get(1).getStatus());
            assertNull(store.finalSteps().get(1).getInputTokens());
        }
    }

    @Test
    void storageFailureNeverEscapesAndCannotBecomeCompleteAfterRecovery() {
        var store = new MemoryStore() {
            @Override public synchronized void persist(GenerationTrace root, GenerationTraceStep step) {
                if (step != null && "RUNNING".equals(step.getStatus())) throw new IllegalStateException("db-password");
                super.persist(root, step);
            }
        };
        try (var recorder = new GenerationTraceRecorder(store, CLOCK, 32)) {
            var session = recorder.startGeneration("task-3", 7L, 9L, "standard", "html");
            var step = session.startStep(TraceSession.Kind.MODEL, "model", null);
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertDoesNotThrow(step::succeed);
            assertDoesNotThrow(() -> session.finish("SUCCEEDED"));
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertEquals("SUCCEEDED", store.latestRoot().getExecutionStatus());
            assertEquals("PARTIAL", store.latestRoot().getRecordingStatus());
            assertFalse(store.latestRoot().toString().contains("db-password"));
        }
    }

    @Test
    void queueOverflowReturnsImmediatelyAndMarksMissingEvidence() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = new MemoryStore() {
            @Override public synchronized void persist(GenerationTrace root, GenerationTraceStep step) {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                super.persist(root, step);
            }
        };
        try (var recorder = new GenerationTraceRecorder(store, CLOCK, 1)) {
            var session = recorder.startGeneration("task-4", 7L, 9L, "standard", "html");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(300), () -> {
                for (int i = 0; i < 10; i++) session.startStep(TraceSession.Kind.STAGE, "generate", null).succeed();
                session.finish("SUCCEEDED");
            });
            assertEquals("PARTIAL", session.snapshot().getRecordingStatus());
            release.countDown();
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertNotEquals("COMPLETE", store.latestRoot().getRecordingStatus());
        } finally { release.countDown(); }
    }

    @Test
    void failedFinalWriteRemainsVisibleAndIsReconciledWithoutInventingEvents() {
        var incomplete = new ArrayList<String>();
        var store = new MemoryStore() {
            @Override public synchronized void persist(GenerationTrace root, GenerationTraceStep step) {
                if ("COMPLETE".equals(root.getRecordingStatus())) throw new IllegalStateException("终态写入失败");
                super.persist(root, step);
            }
            @Override public void markIncomplete(String traceId, String instanceId) { incomplete.add(traceId); }
        };
        try (var recorder = new GenerationTraceRecorder(store, CLOCK, 32)) {
            var session = recorder.startGeneration("task-final", 7L, 9L, "standard", "html");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            session.finish("SUCCEEDED");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertEquals("PARTIAL", recorder.visibleSnapshot(store.latestRoot()).getRecordingStatus());
            assertEquals("RUNNING", store.latestRoot().getExecutionStatus());
            recorder.reconcileMissing();
            assertEquals(List.of(session.traceId()), incomplete);
            recorder.reconcileMissing();
            assertEquals(1, incomplete.size());
            var foreign = store.latestRoot().toBuilder().serviceInstanceId("另一个进程").build();
            assertEquals("COLLECTING", recorder.visibleSnapshot(foreign).getRecordingStatus());
        }
    }

    @Test
    void unfinishedStepsCannotClaimCompleteOrFabricateDuration() {
        var store = new MemoryStore();
        try (var recorder = new GenerationTraceRecorder(store, CLOCK, 16)) {
            var session = recorder.startCreation("request-1", 9L, "standard");
            session.startStep(TraceSession.Kind.MODEL, "routing", null);
            session.finish("UNKNOWN");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertEquals("PARTIAL", store.latestRoot().getRecordingStatus());
            assertEquals("UNRESOLVED", store.latestRoot().getCreationLinkState());
            assertNull(store.finalSteps().getFirst().getDurationMs());
            assertEquals("UNKNOWN", store.finalSteps().getFirst().getStatus());
        }
    }

    static class MemoryStore implements TraceStore {
        private final List<GenerationTrace> roots = new ArrayList<>();
        private final List<GenerationTraceStep> steps = new ArrayList<>();
        @Override public synchronized void persist(GenerationTrace root, GenerationTraceStep step) {
            roots.add(root);
            if (step != null) {
                steps.removeIf(existing -> existing.getSpanId().equals(step.getSpanId()));
                steps.add(step);
            }
        }
        synchronized GenerationTrace latestRoot() { return roots.getLast(); }
        synchronized List<GenerationTraceStep> finalSteps() {
            return steps.stream().sorted(java.util.Comparator.comparingLong(GenerationTraceStep::getStepNo)).toList();
        }
    }
}
