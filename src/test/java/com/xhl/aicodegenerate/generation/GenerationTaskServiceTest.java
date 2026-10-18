package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.service.AppService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationTaskServiceTest {
    @TempDir Path temp;
    @BeforeAll static void redis() throws Exception { RedisTaskStoreTest.openRedis(); }
    @AfterAll static void closeRedis() { RedisTaskStoreTest.closeRedis(); }
    final User user = new User();
    AppService apps;
    RedisTaskStore store;
    @BeforeEach void prepare() {
        try (var connection = RedisTaskStoreTest.template.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        user.setId(77L); apps = mock(AppService.class);
        App app = new App(); app.setId(401L); app.setUserId(77L); app.setCodeGenType("html"); app.setGenerationMode("standard");
        when(apps.getById(401L)).thenReturn(app);
        store = new RedisTaskStore(RedisTaskStoreTest.template, Duration.ofSeconds(30), Duration.ofHours(24));
    }
    GenerationTaskService service(GenerationTaskRunner runner, Duration budget) {
        return new GenerationTaskService(store, new GenerationWorkspace(store, temp.resolve("tasks"), temp.resolve("legacy")), apps, runner, budget);
    }
    @Test void disconnectDoesNotStopExecutionAndDuplicateRequestDoesNotRunAgain() throws Exception {
        var calls = new AtomicInteger(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var service = service((task, run, output, progress) -> {
            calls.incrementAndGet(); entered.countDown(); release.await();
            Files.writeString(run.root().resolve("index.html"), "可用结果"); output.accept("完整回复");
        }, Duration.ofSeconds(10))) {
            String request = UUID.randomUUID().toString();
            var task = service.start(401L, "生成页面", request, user);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            service.events(task.getId(), user).take(1).blockLast(Duration.ofSeconds(2));
            assertEquals(task.getId(), service.start(401L, "生成页面", request, user).getId());
            assertThrows(com.xhl.aicodegenerate.exception.BusinessException.class,
                    () -> service.start(401L, "新需求", UUID.randomUUID().toString(), user));
            release.countDown();
            var finished = awaitTerminal(service, task.getId());
            assertEquals(TaskStatus.SUCCEEDED, finished.getStatus()); assertEquals("完整回复", finished.getOutput());
            assertEquals(1, calls.get()); assertTrue(finished.isPreviewAvailable());
            assertEquals(task.getId(), service.start(401L, "生成页面", request, user).getId());
            assertThrows(com.xhl.aicodegenerate.exception.BusinessException.class,
                    () -> service.start(401L, "偷换输入", request, user));
        } finally { release.countDown(); }
    }
    @Test void explicitStopReleasesApplicationOnlyAfterWorkerCleanup() throws Exception {
        var entered = new CountDownLatch(1); var cleaned = new CountDownLatch(1);
        try (var service = service((task, run, output, progress) -> {
            entered.countDown(); try { new CountDownLatch(1).await(); } finally { cleaned.countDown(); }
        }, Duration.ofSeconds(10))) {
            var task = service.start(401L, "停止测试", UUID.randomUUID().toString(), user);
            assertTrue(entered.await(2, TimeUnit.SECONDS)); service.stop(task.getId(), user);
            assertEquals(TaskStatus.CANCELLED, awaitTerminal(service, task.getId()).getStatus());
            assertEquals(0, cleaned.getCount()); assertFalse(store.owns(401L, task.getId()));
        }
    }
    @Test void timeoutAndForeignOwnerCannotPublishOrStop() throws Exception {
        try (var service = service((task, run, output, progress) -> new CountDownLatch(1).await(), Duration.ofMillis(150))) {
            var task = service.start(401L, "预算测试", UUID.randomUUID().toString(), user);
            User other = new User(); other.setId(78L);
            assertThrows(com.xhl.aicodegenerate.exception.BusinessException.class, () -> service.stop(task.getId(), other));
            assertEquals(TaskStatus.TIMED_OUT, awaitTerminal(service, task.getId()).getStatus());
        }
    }
    @Test void wrappedInterruptionStillRecordsUserCancellation() throws Exception {
        var entered = new CountDownLatch(1);
        try (var service = service((task, run, output, progress) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
        }, Duration.ofSeconds(10))) {
            var task = service.start(401L, "中断标记保留", UUID.randomUUID().toString(), user);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            service.stop(task.getId(), user);
            assertEquals(TaskStatus.CANCELLED, awaitTerminal(service, task.getId()).getStatus());
        }
    }
    @Test void cleanupFailureKeepsApplicationBusyUntilResourcesAreGone() throws Exception {
        var entered = new CountDownLatch(1); var cleanupTried = new CountDownLatch(1);
        var allowCleanup = new java.util.concurrent.atomic.AtomicBoolean();
        try (var service = service((task, run, output, progress) -> {
            run.onCancel(() -> { cleanupTried.countDown(); if (!allowCleanup.get()) throw new IllegalStateException("清理暂未完成"); });
            entered.countDown(); new CountDownLatch(1).await();
        }, Duration.ofSeconds(10))) {
            var task = service.start(401L, "清理测试", UUID.randomUUID().toString(), user);
            assertTrue(entered.await(2, TimeUnit.SECONDS)); service.stop(task.getId(), user);
            assertTrue(cleanupTried.await(2, TimeUnit.SECONDS)); Thread.sleep(150);
            assertEquals(TaskStatus.STOPPING, service.get(task.getId(), user).getStatus());
            assertTrue(store.owns(401L, task.getId()));
            assertThrows(com.xhl.aicodegenerate.exception.BusinessException.class,
                    () -> service.start(401L, "不能抢跑", UUID.randomUUID().toString(), user));
            allowCleanup.set(true);
            assertEquals(TaskStatus.CANCELLED, awaitTerminal(service, task.getId()).getStatus());
        } finally { allowCleanup.set(true); }
    }
    @Test void concurrentIdenticalRequestsInvokeRunnerOnce() throws Exception {
        var calls = new AtomicInteger(); var release = new CountDownLatch(1);
        String request = UUID.randomUUID().toString();
        try (var service = service((task, run, output, progress) -> { calls.incrementAndGet(); release.await(); }, Duration.ofSeconds(10));
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ids = new java.util.HashSet<String>();
            var futures = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(i -> pool.submit(() -> service.start(401L, "并发测试", request, user).getId())).toList();
            for (var future : futures) ids.add(future.get(3, TimeUnit.SECONDS));
            assertEquals(1, ids.size()); release.countDown();
            awaitTerminal(service, ids.iterator().next()); assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }
    @Test void finalHistorySeesCommittedResultAndFailurePreservesSuccess() throws Exception {
        var records = new java.util.concurrent.CopyOnWriteArrayList<GenerationTask>();
        var publications = new AtomicInteger();
        GenerationTaskRunner runner = new GenerationTaskRunner() {
            public void run(GenerationTask task, GenerationExecution run, java.util.function.Consumer<String> output,
                    java.util.function.Consumer<java.util.Map<String, Object>> progress) throws Exception {
                if (task.getPrompt().equals("失败")) throw new IllegalStateException("真实构建失败");
                Files.writeString(run.root().resolve("index.html"), "成功页面");
            }
            public void recordResult(GenerationTask task) {
                assertEquals(task.getStatus(), store.load(task.getId()).getStatus());
                if (task.getStatus() == TaskStatus.SUCCEEDED) assertEquals(task.getId(), store.published(401L));
                records.add(task);
            }
            public void afterPublish(GenerationTask task) {
                assertEquals(TaskStatus.SUCCEEDED, store.load(task.getId()).getStatus());
                assertEquals(task.getId(), store.published(401L));
                publications.incrementAndGet();
            }
        };
        try (var service = service(runner, Duration.ofSeconds(10))) {
            String request = UUID.randomUUID().toString();
            var first = service.start(401L, "成功", request, user);
            assertEquals(TaskStatus.SUCCEEDED, awaitTerminal(service, first.getId()).getStatus());
            for (int i=0; i<100 && store.owns(401L,first.getId()); i++) Thread.sleep(10);
            service.start(401L, "成功", request, user);
            var failure = service.start(401L, "失败", UUID.randomUUID().toString(), user);
            assertEquals(TaskStatus.FAILED, awaitTerminal(service, failure.getId()).getStatus());
            for (int i=0; i<100 && records.size()<2; i++) Thread.sleep(10);
            assertEquals(first.getId(), store.published(401L));
            assertEquals(2, records.size());
            assertEquals(1, publications.get());
        }
    }
    @Test void failedHistoryWriteIsRetriedAfterExecutionHasFinished() throws Exception {
        var attempts=new AtomicInteger();var recorded=new CountDownLatch(1);
        GenerationTaskRunner runner=new GenerationTaskRunner() {
            public void run(GenerationTask task,GenerationExecution run,java.util.function.Consumer<String> output,java.util.function.Consumer<java.util.Map<String,Object>> progress) { }
            public void recordResult(GenerationTask task) {
                if(attempts.incrementAndGet()==1) throw new IllegalStateException("数据库暂不可用");
                recorded.countDown();
            }
        };
        try(var service=service(runner,Duration.ofSeconds(10))) {
            var task=service.start(401L,"历史补记",UUID.randomUUID().toString(),user);
            assertEquals(TaskStatus.SUCCEEDED,awaitTerminal(service,task.getId()).getStatus());
            assertTrue(recorded.await(3,TimeUnit.SECONDS),"终态的待记历史必须恢复补记");
            assertEquals(2,attempts.get());
        }
    }
    GenerationTask awaitTerminal(GenerationTaskService service, String id) throws Exception {
        for (int i = 0; i < 200; i++) { var t = service.get(id, user); if (t.getStatus().terminal()) return t; Thread.sleep(20); }
        fail("任务未结束"); return null;
    }
}
