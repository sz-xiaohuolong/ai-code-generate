package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.GenerationTraceStep;
import com.xhl.aicodegenerate.mapper.GenerationTraceMapper;
import com.xhl.aicodegenerate.mapper.GenerationTraceStepMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Objects;

/** 根、步骤及完整性同事务提交；统一按应用、根、步骤顺序加锁，删除后不允许重新插入。 */
@Component
public class MybatisTraceStore implements TraceStore {
    private final GenerationTraceMapper roots;
    private final GenerationTraceStepMapper steps;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public MybatisTraceStore(GenerationTraceMapper roots, GenerationTraceStepMapper steps, PlatformTransactionManager manager) {
        this(roots, steps, manager, Clock.systemUTC());
    }

    public MybatisTraceStore(GenerationTraceMapper roots, GenerationTraceStepMapper steps,
                             PlatformTransactionManager manager, Clock clock) {
        this.roots = roots;
        this.steps = steps;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    @Override public void persist(GenerationTrace root, GenerationTraceStep step) {
        if (!root.getExpiresAt().isAfter(clock.instant())) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            if (root.getAppId() != null) {
                App app = roots.lockApp(root.getAppId());
                if (!valid(app) || !Objects.equals(app.getUserId(), root.getUserId())) {
                    deleteLocked(root.getTraceId());
                    return;
                }
            }
            GenerationTrace previous = roots.lock(root.getTraceId());
            if (previous == null) {
                // 只有初始事件可以创建根；清理后的迟到更新不能使记录复活。
                if (root.getRevision() != 1) {
                    return;
                }
                if ("GENERATION".equals(root.getTraceKind()) && root.getAppId() != null) {
                    // 在异步写入线程关联已持久化的创建记录，不增加正常生成线程的数据库等待。
                    root.setCreationTraceId(roots.creationForApp(root.getAppId(), root.getUserId(), clock.instant()));
                }
                roots.insert(root);
            } else {
                if (root.getRevision() <= previous.getRevision()) {
                    return;
                }
                root.setCreationTraceId(previous.getCreationTraceId());
                if ("PARTIAL".equals(previous.getRecordingStatus())
                        || root.getRevision() != previous.getLastPersistedSequence() + 1) {
                    root.setRecordingStatus("PARTIAL");
                }
            }
            if (step != null) {
                if (!root.getTraceId().equals(step.getTraceId())) {
                    throw new IllegalArgumentException("追踪步骤归属不一致");
                }
                GenerationTraceStep before = steps.find(step.getTraceId(), step.getSpanId());
                if (before == null) {
                    steps.insert(step);
                } else if (step.getRevision() > before.getRevision()) {
                    steps.update(step);
                }
            }
            if (previous != null) {
                roots.update(root);
            }
        });
    }

    /** 每次处理一个有界批次，后续调度补偿剩余记录；查询过期判断不等待清理。 */
    public void cleanup() {
        for (String id : roots.expired(clock.instant())) {
            cleanupOne(id);
        }
    }

    public void cleanupApp(Long appId) {
        for (String id : roots.forApp(appId)) {
            cleanupOne(id);
        }
    }

    @Override public void markIncomplete(String traceId, String instanceId) {
        transaction.executeWithoutResult(status -> roots.markIncomplete(traceId, instanceId));
    }

    @Override public void markInstanceIncomplete(String instanceId) {
        transaction.executeWithoutResult(status -> roots.markInstanceIncomplete(instanceId));
    }

    public void recover(GenerationTrace hint, String result) {
        if (!java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED", "UNKNOWN").contains(result)) {
            throw new IllegalArgumentException("恢复结果无效");
        }
        transaction.executeWithoutResult(status -> {
            App app = hint.getAppId() == null ? null : roots.lockApp(hint.getAppId());
            GenerationTrace current = roots.lock(hint.getTraceId());
            if (current == null || current.getRevision() != hint.getRevision()
                    || !Objects.equals(current.getAppId(), hint.getAppId())
                    || !Objects.equals(current.getServiceInstanceId(), hint.getServiceInstanceId())
                    || !"RUNNING".equals(current.getExecutionStatus())) {
                return;
            }
            if (!current.getExpiresAt().isAfter(clock.instant()) || (current.getAppId() != null && !valid(app))) {
                steps.deleteTrace(current.getTraceId());
                roots.delete(current.getTraceId());
                return;
            }
            current.setRecordingStatus("PARTIAL");
            current.setExecutionStatus(result);
            current.setRevision(current.getRevision() + 1);
            // 缺失的结束时间、耗时、用量和产物保留未知，不能用恢复时刻补造。
            steps.interruptOpen(current.getTraceId());
            roots.update(current);
        });
    }

    private void cleanupOne(String id) {
        GenerationTrace hint = roots.find(id);
        if (hint == null) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            App app = hint.getAppId() == null ? null : roots.lockApp(hint.getAppId());
            GenerationTrace current = roots.lock(id);
            if (current == null) {
                return;
            }
            // 读取后发生绑定时交给下一批次，避免反转应用与追踪根的锁顺序。
            if (!Objects.equals(hint.getAppId(), current.getAppId())) {
                return;
            }
            if (!current.getExpiresAt().isAfter(clock.instant()) || (current.getAppId() != null && !valid(app))) {
                steps.deleteTrace(id);
                roots.delete(id);
            }
        });
    }

    private void deleteLocked(String traceId) {
        roots.lock(traceId);
        steps.deleteTrace(traceId);
        roots.delete(traceId);
    }

    private static boolean valid(App app) {
        return app != null && Integer.valueOf(0).equals(app.getIsDelete());
    }
}
