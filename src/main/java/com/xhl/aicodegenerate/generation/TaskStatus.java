package com.xhl.aicodegenerate.generation;

/** 终态不能再次进入执行。 */
public enum TaskStatus {
    STARTING, RUNNING, STOPPING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED;
    public boolean terminal() { return this != STARTING && this != RUNNING && this != STOPPING; }
}
