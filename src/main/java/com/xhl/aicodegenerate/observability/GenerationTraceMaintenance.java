package com.xhl.aicodegenerate.observability;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 删除通知只入有界队列；原事务提交后清理，失败由周期扫描补偿。 */
@Component
@Slf4j
public class GenerationTraceMaintenance implements AutoCloseable {
    private final MybatisTraceStore store;
    private final GenerationTraceRecorder recorder;
    private final boolean enabled;
    private final int intervalSeconds;
    private final GenerationTraceRecovery recovery;
    private final ArrayBlockingQueue<Long> deletedApps = new ArrayBlockingQueue<>(100);
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("generation-trace-maintenance").factory());

    @Autowired
    public GenerationTraceMaintenance(MybatisTraceStore store, GenerationTraceRecorder recorder, GenerationTraceRecovery recovery,
            @Value("${generation.trace.enabled:true}") boolean enabled,
            @Value("${generation.trace.maintenance-interval-seconds:15}") int intervalSeconds) {
        this(store, recorder, enabled, intervalSeconds, recovery);
    }

    GenerationTraceMaintenance(MybatisTraceStore store, GenerationTraceRecorder recorder, boolean enabled, int intervalSeconds) {
        this(store, recorder, enabled, intervalSeconds, null);
    }

    private GenerationTraceMaintenance(MybatisTraceStore store, GenerationTraceRecorder recorder,
            boolean enabled, int intervalSeconds, GenerationTraceRecovery recovery) {
        if (intervalSeconds < 1 || intervalSeconds > 3600) {
            throw new IllegalArgumentException("追踪维护周期必须在 1～3600 秒之间");
        }
        this.store = store;
        this.recorder = recorder;
        this.enabled = enabled;
        this.intervalSeconds = intervalSeconds;
        this.recovery = recovery;
    }

    @PostConstruct
    public void start() {
        if (enabled) {
            worker.scheduleWithFixedDelay(this::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }
    }

    public void applicationDeleted(Long appId) {
        if (!enabled || !TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            // 非事务调用没有可信的提交时点；周期扫描仍会校验数据库中的实际删除状态。
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // 队列满也不阻塞业务提交，周期扫描会补偿。
                deletedApps.offer(appId);
            }
        });
    }

    void tick() {
        if (!enabled) {
            return;
        }
        attempt(recorder::reconcileMissing);
        for (int count = 0; count < 100; count++) {
            Long appId = deletedApps.poll();
            if (appId == null) {
                break;
            }
            attempt(() -> store.cleanupApp(appId));
        }
        attempt(store::cleanup);
        if (recovery != null) {
            attempt(recovery::tick);
        }
    }

    private void attempt(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            // 仅记录固定说明；不输出连接串、SQL 参数或异常正文。
            log.warn("追踪后台维护未完成，将在下一周期重试");
        }
    }

    @Override @PreDestroy
    public void close() {
        worker.shutdownNow();
    }
}
