package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.generation.GenerationTask;
import com.xhl.aicodegenerate.generation.RedisTaskStore;
import com.xhl.aicodegenerate.generation.TaskStatus;
import com.xhl.aicodegenerate.mapper.GenerationTraceMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.*;

/** 恢复只消费存活与任务证据，不能改写活跃任务、重放模型或猜测终态。 */
class GenerationTraceRecoveryTest {
    private final GenerationTraceMapper roots = mock(GenerationTraceMapper.class);
    private final MybatisTraceStore store = mock(MybatisTraceStore.class);
    private final RedisTaskStore tasks = mock(RedisTaskStore.class);
    private final TraceInstanceRegistry instances = mock(TraceInstanceRegistry.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);

    @Test void ignoresActiveOrUnregisteredInstancesAndTasksHoldingLease() {
        GenerationTrace alive = root("1", "live");
        GenerationTrace unregistered = root("2", "unregistered");
        GenerationTrace owned = root("3", "retired");
        when(roots.unfinished(isNull(), any(), eq(100))).thenReturn(List.of(alive, unregistered, owned));
        when(instances.retired("retired")).thenReturn(true);
        when(tasks.owns(7L, owned.getTaskId())).thenReturn(true);
        new GenerationTraceRecovery(roots, store, tasks, instances, clock).tick();
        verifyNoInteractions(store);
        verify(tasks, never()).recoverInterrupted(any());
        verify(tasks, never()).finish(any());
    }

    @Test void usesReliableTerminalButMissingOrMismatchedTaskOnlyBecomesUnknown() {
        var terminal = root("1", "old");
        var absent = root("2", "old");
        var mismatch = root("3", "old");
        var creation = root("4", "old").toBuilder().traceKind("CREATE").appId(null).taskId(null).build();
        when(roots.unfinished(isNull(), any(), eq(100))).thenReturn(List.of(terminal, absent, mismatch, creation));
        when(instances.retired("old")).thenReturn(true);
        GenerationTask task = new GenerationTask();
        task.setId(terminal.getTaskId()); task.setAppId(7L); task.setUserId(9L); task.setStatus(TaskStatus.CANCELLED);
        when(tasks.load(terminal.getTaskId())).thenReturn(task);
        when(tasks.load(mismatch.getTaskId())).thenReturn(task);
        new GenerationTraceRecovery(roots, store, tasks, instances, clock).tick();
        verify(store).recover(terminal, "CANCELLED");
        verify(store).recover(absent, "UNKNOWN");
        verify(store).recover(mismatch, "UNKNOWN");
        verify(store).recover(creation, "UNKNOWN");
        verify(tasks, never()).recoverInterrupted(any());
        verify(tasks, never()).claim(any());
    }

    private GenerationTrace root(String id, String instance) {
        return GenerationTrace.builder().traceId(id.repeat(32)).traceKind("GENERATION").serviceInstanceId(instance)
                .taskId("00000000-0000-0000-0000-00000000000" + id).appId(7L).userId(9L)
                .revision(5).executionStatus("RUNNING").recordingStatus("COLLECTING").build();
    }
}
