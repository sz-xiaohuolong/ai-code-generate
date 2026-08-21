package com.xhl.aicodegenerate.ai;

import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用真实模型服务验证路由请求的并发行为。
 *
 * <p>这是集成测试：执行时会向已配置的 AI 服务发送 3 次真实请求。</p>
 */
@Slf4j
@SpringBootTest
class AiConcurrentTest {

    private static final Duration COMPLETION_TIMEOUT = Duration.ofMinutes(2);

    private static final Duration MAX_DISPATCH_WINDOW = Duration.ofSeconds(1);

    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    @Test
    void routesThreeAiRequestsConcurrentlyWithVirtualThreads() throws Exception {
        String[] prompts = {
                "做一个简单的HTML页面",
                "做一个多页面网站项目",
                "做一个Vue管理系统"
        };
        CountDownLatch ready = new CountDownLatch(prompts.length);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        ConcurrentLinkedQueue<RoutingCallResult> results = new ConcurrentLinkedQueue<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < prompts.length; i++) {
                int index = i + 1;
                String prompt = prompts[i];
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    awaitStartSignal(startGate);

                    long startedAt = System.nanoTime();
                    int currentInFlight = inFlight.incrementAndGet();
                    peakInFlight.accumulateAndGet(currentInFlight, Math::max);
                    try {
                        CodeGenTypeEnum result = aiCodeGenTypeRoutingService.routeCodeGenType(prompt);
                        long completedAt = System.nanoTime();
                        results.add(RoutingCallResult.success(index, prompt, result, startedAt, completedAt));
                        log.info("并发请求 {} 完成: {} -> {}, 耗时 {} ms", index, prompt, result.getValue(),
                                TimeUnit.NANOSECONDS.toMillis(completedAt - startedAt));
                    } catch (Throwable throwable) {
                        results.add(RoutingCallResult.failure(index, prompt, startedAt, System.nanoTime(), throwable));
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }));
            }

            Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS), "虚拟线程未能全部就绪");
            startGate.countDown();
            waitForAll(futures);
        }

        Assertions.assertEquals(prompts.length, results.size(), "每个并发请求都应产生一个结果");
        Assertions.assertTrue(results.stream().allMatch(RoutingCallResult::isSuccessful),
                () -> "存在 AI 路由调用失败: " + results);
        Assertions.assertEquals(prompts.length, peakInFlight.get(),
                "所有请求应在本地调用层同时进入 AI 路由服务");

        long earliestStart = results.stream().mapToLong(RoutingCallResult::startedAt).min().orElseThrow();
        long latestStart = results.stream().mapToLong(RoutingCallResult::startedAt).max().orElseThrow();
        long dispatchWindow = latestStart - earliestStart;
        Assertions.assertTrue(dispatchWindow <= MAX_DISPATCH_WINDOW.toNanos(),
                () -> "请求没有在预期窗口内并发发起，实际发起窗口为 "
                        + TimeUnit.NANOSECONDS.toMillis(dispatchWindow) + " ms");

        log.info("并发测试完成: peakInFlight={}, dispatchWindow={} ms, results={}",
                peakInFlight.get(), TimeUnit.NANOSECONDS.toMillis(dispatchWindow), results);
    }

    private void awaitStartSignal(CountDownLatch startGate) {
        try {
            if (!startGate.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发起跑信号超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发起跑信号时被中断", e);
        }
    }

    private void waitForAll(List<Future<?>> futures)
            throws InterruptedException, ExecutionException, TimeoutException {
        for (Future<?> future : futures) {
            future.get(COMPLETION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private record RoutingCallResult(int index, String prompt, CodeGenTypeEnum result,
                                     long startedAt, long completedAt, Throwable error) {

        static RoutingCallResult success(int index, String prompt, CodeGenTypeEnum result,
                                         long startedAt, long completedAt) {
            return new RoutingCallResult(index, prompt, result, startedAt, completedAt, null);
        }

        static RoutingCallResult failure(int index, String prompt, long startedAt,
                                         long completedAt, Throwable error) {
            return new RoutingCallResult(index, prompt, null, startedAt, completedAt, error);
        }

        boolean isSuccessful() {
            return error == null && result != null;
        }
    }
}
