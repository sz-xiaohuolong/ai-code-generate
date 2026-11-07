package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.generation.GenerationTask;
import com.xhl.aicodegenerate.generation.RedisTaskStore;
import com.xhl.aicodegenerate.mapper.GenerationTraceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** 保守收敛旧实例未闭合记录；不调用任务恢复、重新生成或发布入口。 */
@Component
public class GenerationTraceRecovery {
    private final GenerationTraceMapper roots;
    private final MybatisTraceStore store;
    private final RedisTaskStore tasks;
    private final TraceInstanceRegistry instances;
    private final Clock clock;
    private String cursor;

    @Autowired
    public GenerationTraceRecovery(GenerationTraceMapper roots, MybatisTraceStore store,
            RedisTaskStore tasks, TraceInstanceRegistry instances) {
        this(roots, store, tasks, instances, Clock.systemUTC());
    }

    GenerationTraceRecovery(GenerationTraceMapper roots, MybatisTraceStore store,
            RedisTaskStore tasks, TraceInstanceRegistry instances, Clock clock) {
        this.roots = roots;
        this.store = store;
        this.tasks = tasks;
        this.instances = instances;
        this.clock = clock;
    }

    public synchronized void tick() {
        List<GenerationTrace> candidates = roots.unfinished(cursor, clock.instant(), 100);
        for (GenerationTrace trace : candidates) {
            if (!instances.retired(trace.getServiceInstanceId())) {
                continue;
            }
            String result = "UNKNOWN";
            if ("GENERATION".equals(trace.getTraceKind())) {
                if (tasks.owns(trace.getAppId(), trace.getTaskId())) {
                    continue;
                }
                GenerationTask task = tasks.load(trace.getTaskId());
                if (task != null && Objects.equals(task.getId(), trace.getTaskId())
                        && Objects.equals(task.getAppId(), trace.getAppId())
                        && Objects.equals(task.getUserId(), trace.getUserId())
                        && task.getStatus() != null && task.getStatus().terminal()) {
                    result = task.getStatus().name();
                }
            }
            store.recover(trace, result);
        }
        // 按主键继续扫描，活跃实例不会长期挡住后续旧记录。
        cursor = candidates.size() < 100 ? null : candidates.getLast().getTraceId();
    }
}
