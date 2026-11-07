package com.xhl.aicodegenerate.evaluation;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 在出站调用之前原子预占，失败不退回额度；批次时限使用单调时钟，不受系统时间校准影响。 */
public final class EvaluationBudget {
    public enum StopReason { MODEL_CALL_LIMIT, DEADLINE, CANCELLED }
    public static final class Exhausted extends IllegalStateException {
        private final StopReason reason;
        private Exhausted(StopReason reason) {
            super(switch (reason) {
                case MODEL_CALL_LIMIT -> "评测模型调用额度已用尽";
                case DEADLINE -> "评测总时限已到";
                case CANCELLED -> "评测已停止";
            });
            this.reason = reason;
        }
        public StopReason reason() { return reason; }
    }
    private final long maximumCalls;
    private final long durationNanos;
    private final LongSupplier clock;
    private final long startedAt;
    private long calls;
    private StopReason stopped;

    public EvaluationBudget(long maximumCalls, Duration deadline) {
        this(maximumCalls, deadline, System::nanoTime);
    }
    EvaluationBudget(long maximumCalls, Duration deadline, LongSupplier clock) {
        if (maximumCalls <= 0 || deadline.isNegative() || deadline.isZero()) {
            throw new EvaluationConfigurationException("评测预算必须为正值");
        }
        this.maximumCalls = maximumCalls;
        this.durationNanos = deadline.toNanos();
        this.clock = Objects.requireNonNull(clock);
        this.startedAt = clock.getAsLong();
    }
    public synchronized void reserveModelCall() {
        checkActive();
        if (calls >= maximumCalls) {
            stopped = StopReason.MODEL_CALL_LIMIT;
            throw new Exhausted(stopped);
        }
        calls++;
    }
    public synchronized void checkActive() {
        if (stopped == null && clock.getAsLong() - startedAt >= durationNanos) stopped = StopReason.DEADLINE;
        if (stopped != null) throw new Exhausted(stopped);
    }
    public synchronized void requestStop() { if (stopped == null) stopped = StopReason.CANCELLED; }
    public synchronized long modelCalls() { return calls; }
    public synchronized StopReason stopReason() { return stopped; }
    public synchronized Duration remaining() {
        if (stopped != null) return Duration.ZERO;
        return Duration.ofNanos(Math.max(0, durationNanos - (clock.getAsLong() - startedAt)));
    }
}
