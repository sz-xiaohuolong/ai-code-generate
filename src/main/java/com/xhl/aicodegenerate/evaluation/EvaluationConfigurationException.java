package com.xhl.aicodegenerate.evaluation;

/** 调用模型前即可判定的参数、数据集或隔离环境错误，对应命令退出码 3。 */
public final class EvaluationConfigurationException extends IllegalArgumentException {
    public EvaluationConfigurationException(String message) { super(message); }
    public EvaluationConfigurationException(String message, Throwable cause) { super(message, cause); }
}
