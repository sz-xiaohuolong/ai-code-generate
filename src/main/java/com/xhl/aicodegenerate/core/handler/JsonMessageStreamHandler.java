package com.xhl.aicodegenerate.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.ai.tools.BaseTool;
import com.xhl.aicodegenerate.ai.tools.ToolManager;
import com.xhl.aicodegenerate.model.dto.ai.CodeGenStreamMessage;
import com.xhl.aicodegenerate.model.enums.ChatHistoryMessageTypeEnum;
import com.xhl.aicodegenerate.model.enums.CodeGenStreamMessageTypeEnum;
import com.xhl.aicodegenerate.service.ChatHistoryService;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JSON 消息流处理器。
 * <p>
 * Vue 工程模式的上游流是统一 JSON 消息，这里负责把 AI 响应、工具选择、工具执行结果
 * 组合成前端可以直接展示的文本片段。
 * </p>
 */
public class JsonMessageStreamHandler implements StreamHandler {

    private final ChatHistoryService chatHistoryService;

    private final Long appId;

    private final Long userId;

    private final ToolManager toolManager;

    public JsonMessageStreamHandler() {
        this(null, null, null, null);
    }

    public JsonMessageStreamHandler(ChatHistoryService chatHistoryService, Long appId, Long userId) {
        this(chatHistoryService, appId, userId, null);
    }

    public JsonMessageStreamHandler(ChatHistoryService chatHistoryService, Long appId, Long userId,
                                    ToolManager toolManager) {
        this.chatHistoryService = chatHistoryService;
        this.appId = appId;
        this.userId = userId;
        this.toolManager = toolManager;
    }

    @Override
    public Flux<String> handle(Flux<String> originFlux) {
        Set<String> activeToolRequestKeys = new HashSet<>();
        StringBuilder persistedToolContentBuilder = new StringBuilder();
        AtomicReference<Long> persistedToolMessageId = new AtomicReference<>();
        return originFlux.<String>handle((chunk, sink) -> {
            CodeGenStreamMessage message = parseMessage(chunk);
            if (message == null) {
                sink.next(chunk);
                return;
            }
            String type = message.getType();
            if (CodeGenStreamMessageTypeEnum.AI_RESPONSE.getValue().equals(type)) {
                if (StrUtil.isNotBlank(message.getData())) {
                    sink.next(message.getData());
                }
                return;
            }
            if (CodeGenStreamMessageTypeEnum.TOOL_REQUEST.getValue().equals(type)) {
                String toolName = message.getName();
                if (StrUtil.isBlank(toolName)) {
                    return;
                }
                String key = StrUtil.blankToDefault(message.getId(), toolName);
                boolean sameToolAlreadyActive = activeToolRequestKeys.contains(toolName);
                if (activeToolRequestKeys.add(key) && !sameToolAlreadyActive) {
                    String toolRequestMessage = formatToolRequestMessage(toolName);
                    persistToolContent(toolRequestMessage, persistedToolContentBuilder, persistedToolMessageId);
                    sink.next(toolRequestMessage);
                }
                activeToolRequestKeys.add(toolName);
                return;
            }
            if (CodeGenStreamMessageTypeEnum.TOOL_EXECUTED.getValue().equals(type)) {
                activeToolRequestKeys.remove(message.getId());
                activeToolRequestKeys.remove(message.getName());
                String toolExecutedMessage = formatToolExecutedMessage(message);
                if (StrUtil.isNotBlank(toolExecutedMessage)) {
                    persistToolContent(toolExecutedMessage, persistedToolContentBuilder, persistedToolMessageId);
                    sink.next(toolExecutedMessage);
                }
            }
        });
    }

    private CodeGenStreamMessage parseMessage(String chunk) {
        if (StrUtil.isBlank(chunk) || !JSONUtil.isTypeJSON(chunk)) {
            return null;
        }
        try {
            return JSONUtil.toBean(chunk, CodeGenStreamMessage.class);
        } catch (Exception e) {
            return null;
        }
    }

    private BaseTool resolveTool(String toolName) {
        if (toolManager == null) {
            return null;
        }
        return toolManager.getTool(toolName);
    }

    private String formatToolRequestMessage(String toolName) {
        BaseTool tool = resolveTool(toolName);
        if (tool != null) {
            return tool.formatToolRequestMessage();
        }
        return "\n\n[选择工具] " + StrUtil.blankToDefault(toolName, "未知工具") + "\n";
    }

    private String formatToolExecutedMessage(CodeGenStreamMessage message) {
        BaseTool tool = resolveTool(message.getName());
        if (tool != null) {
            return tool.formatToolExecutedMessage(message);
        }
        return "\n\n[工具调用] " + StrUtil.blankToDefault(message.getName(), "未知工具") + "\n"
                + StrUtil.nullToEmpty(message.getData()) + "\n";
    }

    private void persistToolContent(String content, StringBuilder contentBuilder, AtomicReference<Long> messageIdRef) {
        if (!isPersistenceEnabled() || StrUtil.isBlank(content)) {
            return;
        }
        contentBuilder.append(content);
        Long messageId = messageIdRef.get();
        String message = contentBuilder.toString();
        if (messageId == null) {
            Long savedMessageId = chatHistoryService.saveMessage(appId, userId, message,
                    ChatHistoryMessageTypeEnum.AI.getValue());
            messageIdRef.set(savedMessageId);
            return;
        }
        chatHistoryService.updateMessage(messageId, message);
    }

    private boolean isPersistenceEnabled() {
        return chatHistoryService != null && appId != null && appId > 0 && userId != null && userId > 0;
    }
}
