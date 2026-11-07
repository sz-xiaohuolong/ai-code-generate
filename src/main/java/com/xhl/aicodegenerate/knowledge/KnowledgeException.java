package com.xhl.aicodegenerate.knowledge;

import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;

/** 对用户仅返回稳定分类，不泄露供应商响应或文档正文。 */
public class KnowledgeException extends BusinessException {
    private final String reasonCode;
    public KnowledgeException(String reasonCode, String message) {
        super(ErrorCode.OPERATION_ERROR, message);
        this.reasonCode = reasonCode;
    }
    public String getReasonCode() { return reasonCode; }
}
