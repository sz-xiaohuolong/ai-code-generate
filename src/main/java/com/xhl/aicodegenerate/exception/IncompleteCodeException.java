package com.xhl.aicodegenerate.exception;

/** 模型交付不完整，可在同一任务预算内重新生成一次。 */
public class IncompleteCodeException extends BusinessException {
    public IncompleteCodeException(String message) {
        super(ErrorCode.OPERATION_ERROR, message);
    }
}
