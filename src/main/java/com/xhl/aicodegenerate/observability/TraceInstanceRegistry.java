package com.xhl.aicodegenerate.observability;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 只维护追踪采集实例的存活证据，不拥有或续租生成任务。过期的采集租约不能复活。 */
@Component
@Slf4j
public class TraceInstanceRegistry implements AutoCloseable {
    private static final String KEY_PREFIX = "generation:trace:instance:";
    private static final DefaultRedisScript<Long> HEARTBEAT = new DefaultRedisScript<>("""
        local t = redis.call('TIME')
        local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
        local state = redis.call('HGET', KEYS[1], 'state')
        if not state and ARGV[1] == 'register' then state = 'LIVE' end
        if state ~= 'LIVE' then return 0 end
        local deadline = tonumber(redis.call('HGET', KEYS[1], 'deadline') or (now + 90000))
        if deadline <= now then
          redis.call('HSET', KEYS[1], 'state', 'RETIRED')
          return 0
        end
        redis.call('HSET', KEYS[1], 'state', 'LIVE', 'deadline', now + 90000)
        redis.call('EXPIRE', KEYS[1], 604800)
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RETIRED = new DefaultRedisScript<>("""
        local state = redis.call('HGET', KEYS[1], 'state')
        if not state then return 0 end
        if state == 'RETIRED' then return 1 end
        local t = redis.call('TIME')
        local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
        local deadline = tonumber(redis.call('HGET', KEYS[1], 'deadline'))
        if deadline and deadline <= now then
          redis.call('HSET', KEYS[1], 'state', 'RETIRED')
          return 1
        end
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> CLOSE = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
        redis.call('HSET', KEYS[1], 'state', 'RETIRED')
        redis.call('EXPIRE', KEYS[1], 604800)
        return 1
        """, Long.class);
    private final StringRedisTemplate redis;
    private final GenerationTraceRecorder recorder;
    private final boolean enabled;
    private volatile boolean registered;
    private final ScheduledExecutorService heartbeatWorker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("generation-trace-heartbeat").factory());

    public TraceInstanceRegistry(StringRedisTemplate redis, GenerationTraceRecorder recorder,
            @Value("${generation.trace.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.recorder = recorder;
        this.enabled = enabled;
    }

    @PostConstruct public void start() {
        if (enabled) {
            heartbeatWorker.scheduleWithFixedDelay(this::heartbeat, 0, 15, TimeUnit.SECONDS);
        }
    }

    synchronized void heartbeat() {
        if (!enabled) {
            return;
        }
        try {
            Long result = redis.execute(HEARTBEAT, List.of(KEY_PREFIX + recorder.instanceId()), registered ? "renew" : "register");
            if (Long.valueOf(1).equals(result)) {
                registered = true;
            } else {
                // 心跳过期只表示失去采集连续性，不能把仍在运行的业务任务改为失败。
                recorder.markInstanceIncomplete();
            }
        } catch (RuntimeException error) {
            log.warn("追踪实例存活信息暂不可用，保留未知状态");
        }
    }

    public boolean retired(String instanceId) {
        if (!enabled || recorder.instanceId().equals(instanceId) || instanceId == null) {
            return false;
        }
        return Long.valueOf(1).equals(redis.execute(RETIRED, List.of(KEY_PREFIX + instanceId)));
    }

    @Override @PreDestroy public void close() {
        heartbeatWorker.shutdownNow();
        if (enabled && registered) {
            try {
                redis.execute(CLOSE, List.of(KEY_PREFIX + recorder.instanceId()));
            } catch (RuntimeException error) {
                // 无可靠关闭记录时依赖既有心跳期限，不能反向阻断服务关闭。
                log.warn("追踪实例关闭标记未保存");
            }
        }
    }
}
