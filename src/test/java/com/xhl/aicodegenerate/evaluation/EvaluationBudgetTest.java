package com.xhl.aicodegenerate.evaluation;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationBudgetTest {
    @Test void outboundReservationIsAtomicAndFailedRequestsStillSpendTheirSlot() throws Exception {
        var budget = new EvaluationBudget(7, Duration.ofSeconds(30));
        var accepted = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 40).mapToObj(i -> executor.submit(() -> {
                try { budget.reserveModelCall(); accepted.incrementAndGet(); }
                catch (EvaluationBudget.Exhausted ignored) { }
            })).toList();
            for (var future : futures) future.get();
        }
        assertEquals(7, accepted.get());
        assertEquals(7, budget.modelCalls());
        assertEquals(EvaluationBudget.StopReason.MODEL_CALL_LIMIT,
                assertThrows(EvaluationBudget.Exhausted.class, budget::reserveModelCall).reason());
    }

    @Test void exactDeadlineAndCancellationForbidFurtherCallsWithoutResettingUsage() {
        var clock = new AtomicLong(100);
        var budget = new EvaluationBudget(3, Duration.ofNanos(50), clock::get);
        budget.reserveModelCall();
        clock.set(149); assertEquals(Duration.ofNanos(1), budget.remaining());
        clock.set(150);
        assertEquals(EvaluationBudget.StopReason.DEADLINE,
                assertThrows(EvaluationBudget.Exhausted.class, budget::reserveModelCall).reason());
        assertEquals(1, budget.modelCalls());
        assertEquals(Duration.ZERO, budget.remaining());
        var cancelled = new EvaluationBudget(3, Duration.ofSeconds(10));
        cancelled.requestStop();
        assertEquals(EvaluationBudget.StopReason.CANCELLED,
                assertThrows(EvaluationBudget.Exhausted.class, cancelled::checkActive).reason());
        assertEquals(0, cancelled.modelCalls());
    }
}
