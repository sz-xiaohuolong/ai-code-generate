package com.xhl.aicodegenerate.generation;

/** 真实进程诊断；只把可修复的代码错误交给模型。 */
public record BuildResult(String stage, int exitCode, String stdout, String stderr, Kind kind, boolean timedOut) {
    public enum Kind { NONE, CODE, ENVIRONMENT, TIMEOUT, CANCELLED }
    public boolean success() { return kind == Kind.NONE && exitCode == 0; }
    public String diagnostic() { return "阶段：" + stage + "，退出码：" + exitCode + "\n" + stdout + "\n" + stderr; }
}
