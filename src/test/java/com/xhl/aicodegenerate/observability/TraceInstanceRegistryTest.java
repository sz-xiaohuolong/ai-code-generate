package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.generation.GenerationExecution;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在专用 Redis 进程执行 Lua，验证活跃、未知、过期及过期后不可复活。 */
class TraceInstanceRegistryTest {
    private static Process process;
    private static JedisConnectionFactory connection;
    private static StringRedisTemplate redis;

    @BeforeAll static void startRedis() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        process = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", String.valueOf(port),
                "--save", "", "--appendonly", "no")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        connection = new JedisConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
        connection.afterPropertiesSet(); connection.start();
        redis = new StringRedisTemplate(connection); redis.afterPropertiesSet();
        Exception last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try (var client = connection.getConnection()) { client.ping(); return; }
            catch (Exception error) { last = error; Thread.sleep(20); }
        }
        throw last;
    }

    @AfterAll static void stopRedis() {
        if (connection != null) connection.destroy();
        if (process != null) GenerationExecution.terminate(process);
    }

    @Test void liveAndUnknownInstancesAreNeverClassifiedAsRetired() {
        var recorder = recorder();
        try (var live = new TraceInstanceRegistry(redis, recorder, true);
             var observer = new TraceInstanceRegistry(redis, recorder(), true)) {
            live.heartbeat();
            assertFalse(observer.retired(recorder.instanceId()));
            assertFalse(observer.retired(UUID.randomUUID().toString()));
            assertTrue(redis.getExpire("generation:trace:instance:" + recorder.instanceId()) > 604700);
            live.heartbeat();
            verify(recorder, never()).markInstanceIncomplete();
        }
    }

    @Test void expiredLeaseCannotRenewAndGracefulCloseProvidesEvidence() {
        var recorder = recorder();
        try (var instance = new TraceInstanceRegistry(redis, recorder, true);
             var observer = new TraceInstanceRegistry(redis, recorder(), true)) {
            instance.heartbeat();
            String key = "generation:trace:instance:" + recorder.instanceId();
            redis.opsForHash().put(key, "deadline", "0");
            assertTrue(observer.retired(recorder.instanceId()));
            instance.heartbeat();
            verify(recorder).markInstanceIncomplete();
            assertEquals("RETIRED", redis.opsForHash().get(key, "state"));
            assertFalse(instance.retired(recorder.instanceId()));
        }
        var closed = recorder();
        try (var instance = new TraceInstanceRegistry(redis, closed, true)) { instance.heartbeat(); }
        try (var observer = new TraceInstanceRegistry(redis, recorder(), true)) {
            assertTrue(observer.retired(closed.instanceId()));
        }
    }

    private GenerationTraceRecorder recorder() {
        var recorder = mock(GenerationTraceRecorder.class);
        when(recorder.instanceId()).thenReturn(UUID.randomUUID().toString());
        return recorder;
    }
}
