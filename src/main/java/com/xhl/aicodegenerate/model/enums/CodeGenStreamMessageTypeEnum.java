package com.xhl.aicodegenerate.model.enums;

import lombok.Getter;

/**
 * 代码生成流式消息类型。
 */
@Getter
public enum CodeGenStreamMessageTypeEnum {

    /**
     * AI 普通文本响应。
     */
    AI_RESPONSE("ai_response"),

    /**
     * AI 正在发起工具调用。
     */
    TOOL_REQUEST("tool_request"),

    /**
     * 工具调用已完成。
     */
    TOOL_EXECUTED("tool_executed"),

    /**
     * AI 深度思考片段，当前预留给后续前端展示。
     */
    THINKING("thinking");

    private final String value;

    CodeGenStreamMessageTypeEnum(String value) {
        this.value = value;
    }
}
