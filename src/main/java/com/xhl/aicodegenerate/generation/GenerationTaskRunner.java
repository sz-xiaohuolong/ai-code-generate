package com.xhl.aicodegenerate.generation;

import java.util.Map;
import java.util.function.Consumer;

/** 后台执行与模型／构建之间的边界，任务服务负责生命周期。 */
@FunctionalInterface
public interface GenerationTaskRunner {
    void run(GenerationTask task, GenerationExecution execution, Consumer<String> output,
             Consumer<Map<String, Object>> progress) throws Exception;
    default void recordResult(GenerationTask task) { }
    default void afterPublish(GenerationTask task) { }
}
