package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.GenerationTraceStep;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 有界、单写入通道：调用线程只入队，存储故障不参与生成的成功判定。 */
public final class GenerationTraceRecorder implements AutoCloseable {
    private final TraceStore store;
    private final Clock clock;
    private final ArrayBlockingQueue<Event> queue;
    private final SdkTracerProvider provider = SdkTracerProvider.builder().build();
    private final Tracer tracer = provider.get("generation-observability", "1");
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicInteger pending = new AtomicInteger();
    private final Object idle = new Object();
    private final Thread writer;
    private volatile boolean accepting = true;
    private volatile Event inFlight;
    private final Map<String, Missing> missing = new LinkedHashMap<>();
    private boolean instanceIncomplete;
    private static final int MAX_MISSING = 4096;
    private final boolean enabled;

    public GenerationTraceRecorder(TraceStore store, Clock clock, int capacity) {
        this(store, clock, capacity, true);
    }

    public GenerationTraceRecorder(TraceStore store, Clock clock, int capacity, boolean enabled) {
        this.store = java.util.Objects.requireNonNull(store);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.enabled = enabled;
        this.queue = new ArrayBlockingQueue<>(capacity);
        writer = Thread.ofPlatform().daemon().name("generation-trace-writer").start(this::write);
    }

    public TraceSession startGeneration(String taskId, Long appId, Long userId, String mode, String type) {
        return new TraceSession(this, tracer, clock, instanceId, "GENERATION", null, taskId, appId, userId, mode, type);
    }

    public TraceSession startCreation(String requestId, Long userId, String mode) {
        return new TraceSession(this, tracer, clock, instanceId, "CREATE", requestId, null, null, userId, mode, null);
    }

    synchronized void noteMissing(String traceId, Instant expiresAt) {
        missing.values().removeIf(value -> !value.expiresAt().isAfter(clock.instant()));
        if (missing.size() >= MAX_MISSING && !missing.containsKey(traceId)) {
            // ponytail: 有界索引满时降低整个本进程的完整性，不用无限缓存掩盖故障。
            instanceIncomplete = true;
            return;
        }
        missing.put(traceId, new Missing(expiresAt, false));
    }

    /** 只叠加已知缺口；查询权限、关联状态及过期检查仍由查询服务执行。 */
    public synchronized GenerationTrace visibleSnapshot(GenerationTrace persisted) {
        if (persisted != null && instanceId.equals(persisted.getServiceInstanceId())
                && (instanceIncomplete || missing.containsKey(persisted.getTraceId()))) {
            return persisted.toBuilder().recordingStatus("PARTIAL").build();
        }
        return persisted;
    }

    /** 后台补偿只写缺口标记，即使最后一个事件丢失也不依赖新业务调用。 */
    public void reconcileMissing() {
        List<String> pendingIds;
        boolean overflow;
        synchronized (this) {
            missing.values().removeIf(value -> !value.expiresAt().isAfter(clock.instant()));
            overflow = instanceIncomplete;
            pendingIds = missing.entrySet().stream().filter(entry -> !entry.getValue().persisted())
                    .limit(100).map(Map.Entry::getKey).toList();
        }
        if (overflow) {
            store.markInstanceIncomplete(instanceId);
        }
        for (String id : pendingIds) {
            store.markIncomplete(id, instanceId);
            synchronized (this) {
                missing.computeIfPresent(id, (key, value) -> new Missing(value.expiresAt(), true));
            }
        }
    }

    public String instanceId() { return instanceId; }
    public boolean isEnabled() { return enabled; }
    public synchronized void markInstanceIncomplete() { instanceIncomplete = true; }

    void record(TraceSession session, GenerationTrace root, GenerationTraceStep step) {
        if (!enabled || !accepting) {
            session.markPartial();
            return;
        }
        pending.incrementAndGet();
        if (!queue.offer(new Event(session, root, step))) {
            session.markPartial();
            completed();
        }
    }

    private void write() {
        while (accepting || pending.get() > 0) {
            Event event;
            try {
                event = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
            if (event == null) {
                continue;
            }
            inFlight = event;
            try {
                if (event.session.isPartial()) {
                    event.root.setRecordingStatus("PARTIAL");
                }
                store.persist(visibleSnapshot(event.root), event.step);
            } catch (RuntimeException error) {
                // 不输出供应商或存储异常正文；缺失标记在同一会话中只增不减。
                event.session.markPartial();
            } finally {
                inFlight = null;
                completed();
            }
        }
    }

    private void completed() {
        pending.decrementAndGet();
        synchronized (idle) {
            idle.notifyAll();
        }
    }

    /** 供批次报告及关闭流程等待已接受事件；生成调用不会等待此方法。 */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (idle) {
            while (pending.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(idle, remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    @PreDestroy
    public void close() {
        accepting = false;
        if (!awaitIdle(Duration.ofSeconds(3))) {
            Event active = inFlight;
            if (active != null) {
                active.session.markPartial();
            }
            queue.forEach(event -> event.session.markPartial());
        }
        writer.interrupt();
        try {
            writer.join(1000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        provider.close();
    }

    private record Event(TraceSession session, GenerationTrace root, GenerationTraceStep step) { }
    private record Missing(Instant expiresAt, boolean persisted) { }
}
