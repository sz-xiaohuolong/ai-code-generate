package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.GenerationTraceStep;

/** 生产事务存储与隔离评测存储的共同写入边界；失败交由记录器标记缺口。 */
public interface TraceStore {
    void persist(GenerationTrace root, GenerationTraceStep step);

    /** 只降低已有记录的完整性；不创建根、不补造步骤或执行终态。 */
    default void markIncomplete(String traceId, String instanceId) { }

    /** 缺口索引达到上限时保守标记该进程的记录，不影响其他进程。 */
    default void markInstanceIncomplete(String instanceId) { }
}
