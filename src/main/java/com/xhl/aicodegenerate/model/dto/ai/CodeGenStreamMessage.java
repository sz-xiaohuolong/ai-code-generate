package com.xhl.aicodegenerate.model.dto.ai;

import com.xhl.aicodegenerate.model.enums.CodeGenStreamMessageTypeEnum;
import lombok.Data;

/**
 * 代码生成过程中的统一流式消息。
 */
@Data
public class CodeGenStreamMessage {

    /**
     * 消息类型，见 CodeGenStreamMessageTypeEnum.value。
     */
    private String type;

    /**
     * 工具调用 id。
     */
    private String id;

    /**
     * 工具名称。
     */
    private String name;

    /**
     * 工具调用参数；tool_request 中是参数片段，tool_executed 中是完整参数。
     */
    private String arguments;

    /**
     * 文本数据或工具执行结果。
     */
    private String data;

    public static CodeGenStreamMessage aiResponse(String data) {
        CodeGenStreamMessage message = new CodeGenStreamMessage();
        message.setType(CodeGenStreamMessageTypeEnum.AI_RESPONSE.getValue());
        message.setData(data);
        return message;
    }

    public static CodeGenStreamMessage thinking(String data) {
        CodeGenStreamMessage message = new CodeGenStreamMessage();
        message.setType(CodeGenStreamMessageTypeEnum.THINKING.getValue());
        message.setData(data);
        return message;
    }

    public static CodeGenStreamMessage toolRequest(String id, String name, String arguments) {
        CodeGenStreamMessage message = new CodeGenStreamMessage();
        message.setType(CodeGenStreamMessageTypeEnum.TOOL_REQUEST.getValue());
        message.setId(id);
        message.setName(name);
        message.setArguments(arguments);
        return message;
    }

    public static CodeGenStreamMessage toolExecuted(String id, String name, String arguments, String data) {
        CodeGenStreamMessage message = new CodeGenStreamMessage();
        message.setType(CodeGenStreamMessageTypeEnum.TOOL_EXECUTED.getValue());
        message.setId(id);
        message.setName(name);
        message.setArguments(arguments);
        message.setData(data);
        return message;
    }
}
