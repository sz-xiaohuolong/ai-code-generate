package com.xhl.aicodegenerate.observability;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 清理只能由提交后的通知触发，存储故障不改变应用删除结果。 */
class GenerationTraceMaintenanceTest {
    private final MybatisTraceStore store = mock(MybatisTraceStore.class);
    private final GenerationTraceRecorder recorder = mock(GenerationTraceRecorder.class);

    @Test void queuesOnlyAfterCommitAndPeriodicCleanupCompensatesFailures() {
        try (var maintenance = new GenerationTraceMaintenance(store, recorder, true, 60)) {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                maintenance.applicationDeleted(7L);
                verifyNoInteractions(store);
                TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
                verifyNoInteractions(store);
            } finally {
                TransactionSynchronizationManager.clear();
            }
            doThrow(new IllegalStateException("数据库暂不可用")).when(store).cleanupApp(7L);
            assertDoesNotThrow(maintenance::tick);
            verify(store).cleanupApp(7L);
            verify(store).cleanup();
            verify(recorder).reconcileMissing();
        }
    }

    @Test void rollbackDoesNotEnqueueAppCleanupAndDisabledModeDoesNotWrite() {
        try (var maintenance = new GenerationTraceMaintenance(store, recorder, true, 60)) {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                maintenance.applicationDeleted(7L);
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            } finally {
                TransactionSynchronizationManager.clear();
            }
            maintenance.tick();
            verify(store, never()).cleanupApp(any());
        }
        clearInvocations(store, recorder);
        try (var disabled = new GenerationTraceMaintenance(store, recorder, false, 60)) {
            disabled.applicationDeleted(7L);
            disabled.tick();
            verifyNoInteractions(store, recorder);
        }
    }
}
