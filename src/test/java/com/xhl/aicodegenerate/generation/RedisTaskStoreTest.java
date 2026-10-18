package com.xhl.aicodegenerate.generation;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RedisTaskStoreTest {
    static Process redis;
    static JedisConnectionFactory connection;
    static StringRedisTemplate template;
    RedisTaskStore store;

    @BeforeAll static void openRedis() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        redis = new ProcessBuilder("redis-server", "--port", String.valueOf(port), "--save", "", "--appendonly", "no")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        connection = new JedisConnectionFactory(new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1", port));
        connection.afterPropertiesSet(); connection.start();
        template = new StringRedisTemplate(connection); template.afterPropertiesSet();
        Exception last = null;
        for (int i = 0; i < 100; i++) {
            try { template.getConnectionFactory().getConnection().ping(); return; }
            catch (Exception e) { last = e; Thread.sleep(20); }
        }
        throw last;
    }
    @AfterAll static void closeRedis() {
        if (connection != null) connection.destroy();
        if (redis != null) GenerationExecution.terminate(redis);
    }
    @BeforeEach void setup() {
        try (var connection = RedisTaskStoreTest.template.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); } store = new RedisTaskStore(template, Duration.ofSeconds(30), Duration.ofHours(24)); }

    static GenerationTask task(long app, String request) {
        GenerationTask t = new GenerationTask();
        t.setId(UUID.randomUUID().toString()); t.setAppId(app); t.setUserId(7L);
        t.setClientRequestId(request); t.setInputHash("相同输入"); t.setStatus(TaskStatus.STARTING);
        return t;
    }
    @Test void duplicateRequestReturnsOriginalTaskAndDifferentRequestIsBusy() {
        var first = task(101, UUID.randomUUID().toString());
        assertEquals(first.getId(), store.claim(first));
        assertEquals(first.getId(), store.claim(task(101, first.getClientRequestId())));
        assertThrows(com.xhl.aicodegenerate.exception.BusinessException.class,
                () -> store.claim(task(101, UUID.randomUUID().toString())));
        assertEquals(first.getId(), store.latest(101L));
    }
    @Test void onlyOwnerCanFinishOrPublishAndStopPreventsSuccess() {
        var first = task(102, UUID.randomUUID().toString()); store.claim(first);
        var stranger = task(102, UUID.randomUUID().toString()); stranger.setStatus(TaskStatus.SUCCEEDED);
        assertFalse(store.finish(stranger));
        assertTrue(store.owns(102L, first.getId()));
        store.requestStop(first.getId()); first.setStatus(TaskStatus.SUCCEEDED);
        assertFalse(store.finish(first)); assertNull(store.published(102L));
        first.setStatus(TaskStatus.CANCELLED); assertTrue(store.finish(first));
        assertFalse(store.owns(102L, first.getId()));
    }
    @Test void lostLeaseIsRecoveredAsInterruptedWithoutStartingModel() {
        var first = task(104, UUID.randomUUID().toString()); store.claim(first);
        template.delete("generation:owner:104");
        var recovered = store.recoverInterrupted(store.load(first.getId()));
        assertEquals(TaskStatus.INTERRUPTED, recovered.getStatus());
        assertEquals(TaskStatus.INTERRUPTED, store.load(first.getId()).getStatus());
        assertNull(store.published(104L));
    }
    @Test void staleSnapshotCannotOverwriteCommittedTerminalWhileHistoryIsBeingSaved() {
        var first=task(105,UUID.randomUUID().toString());store.claim(first);
        var stale=store.load(first.getId()); stale.setStatus(TaskStatus.RUNNING);stale.setRevision(1);
        first.setStatus(TaskStatus.SUCCEEDED);first.setRevision(2);assertTrue(store.finish(first,false));
        assertFalse(store.save(stale));assertEquals(TaskStatus.SUCCEEDED,store.load(first.getId()).getStatus());
        assertEquals(first.getId(),store.published(105L));store.release(first);
    }
    @Test void pendingHistorySurvivesTaskExpiryUntilAcknowledged() {
        var task=task(106,UUID.randomUUID().toString());store.claim(task);task.setStatus(TaskStatus.SUCCEEDED);store.finish(task);
        template.delete("generation:task:"+task.getId());
        assertNull(store.load(task.getId()));assertNotNull(store.historyTask(task.getId()));
        assertNotNull(store.historyTask(task.getId()).getUserMessageId());
        assertTrue(store.claimHistory(task.getId(),"recorder"));
        store.finishHistory(task.getId(),"recorder",true);assertNull(store.historyTask(task.getId()));
    }
    @Test void successPointerOutlivesTaskAndCannotBeChangedByLateOwner() {
        var first = task(103, UUID.randomUUID().toString()); store.claim(first);
        first.setStatus(TaskStatus.SUCCEEDED); assertTrue(store.finish(first));
        assertEquals(first.getId(), store.published(103L));
        assertEquals(-1, template.getExpire(RedisTaskStore.versionKey(103L)));
        var next = task(103, UUID.randomUUID().toString()); store.claim(next);
        assertFalse(store.finish(first)); assertTrue(store.owns(103L, next.getId()));
        assertEquals(first.getId(), store.published(103L));
    }
}
