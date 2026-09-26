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
        // 当前正处于"发起调用->等待返回"状态的工具，用于对 tool_request 去重（流式阶段同一工具可能连发多个请求块）
        Set<String> activeToolRequestKeys = new HashSet<>();
        // 累积本次工具调用过程中产生的所有文本，作为一条连贯的 AI 消息持久化
        StringBuilder persistedToolContentBuilder = new StringBuilder();
        // DB 中这条 AI 消息的 id；为 null 表示还未创建，需要 saveMessage 新建
        AtomicReference<Long> persistedToolMessageId = new AtomicReference<>();
        return originFlux.<String>handle((chunk, sink) -> {
            // 1. 解析统一 JSON 消息；非 JSON（如模型裸文本碎片）直接原样透传给前端
            CodeGenStreamMessage message = parseMessage(chunk);
            if (message == null) {
                sink.next(chunk);
                return;
            }
            String type = message.getType();
            // 2. AI 普通文本响应：直接透传正文，不做额外处理
            if (CodeGenStreamMessageTypeEnum.AI_RESPONSE.getValue().equals(type)) {
                if (StrUtil.isNotBlank(message.getData())) {
                    sink.next(message.getData());
                }
                return;
            }
            // 3. 工具开始调用：仅当该工具首次出现时输出一次"选择工具"横幅，避免重复
            if (CodeGenStreamMessageTypeEnum.TOOL_REQUEST.getValue().equals(type)) {
                String toolName = message.getName();
                if (StrUtil.isBlank(toolName)) {
                    return;
                }
                // 以 id 为准的唯一 key（缺 id 时退化为工具名）
                String key = StrUtil.blankToDefault(message.getId(), toolName);
                // 该工具是否已在活跃集合中（按 name 去重，兼容流式重复上报）
                boolean sameToolAlreadyActive = activeToolRequestKeys.contains(toolName);
                // 只有 key 首次出现且工具此前未活跃，才真正输出一次请求文案
                if (activeToolRequestKeys.add(key) && !sameToolAlreadyActive) {
                    String toolRequestMessage = formatToolRequestMessage(toolName);
                    persistToolContent(toolRequestMessage, persistedToolContentBuilder, persistedToolMessageId);
                    sink.next(toolRequestMessage);
                }
                // 无论是否输出文案，都把工具名标记为活跃
                activeToolRequestKeys.add(toolName);
                return;
            }
            // 4. 工具执行完成：从活跃集合移除（id 与 name 双重移除以兼容上报），输出并持久化执行结果
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

    /**
     * 尝试把 chunk 解析为统一 JSON 消息。非 JSON 或解析失败返回 null（由调用方透传原块）。
     */
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

    /**
     * 按工具名从 ToolManager 解析出具体工具实现；未配置 ToolManager 时返回 null。
     */
    private BaseTool resolveTool(String toolName) {
        if (toolManager == null) {
            return null;
        }
        return toolManager.getTool(toolName);
    }

    /**
     * 生成"选择工具"提示文案：优先用工具自带的格式化逻辑，兜底给通用文案。
     */
    private String formatToolRequestMessage(String toolName) {
        BaseTool tool = resolveTool(toolName);
        if (tool != null) {
            return tool.formatToolRequestMessage();
        }
        return "\n\n[选择工具] " + StrUtil.blankToDefault(toolName, "未知工具") + "\n";
    }

    /**
     * 生成"工具执行完成"文案：优先用工具自带的格式化逻辑，兜底拼出通用结果文本。
     */
    private String formatToolExecutedMessage(CodeGenStreamMessage message) {
        BaseTool tool = resolveTool(message.getName());
        if (tool != null) {
            return tool.formatToolExecutedMessage(message);
        }
        return "\n\n[工具调用] " + StrUtil.blankToDefault(message.getName(), "未知工具") + "\n"
                + StrUtil.nullToEmpty(message.getData()) + "\n";
    }

    /**
     * 将工具文本累积并持久化为一条 AI 消息：首次调用 saveMessage 新建并记录 id，
     * 之后通过 updateMessage 不断追加，保证一次工具调用在 DB 中是一条连贯消息。
     */
    private void persistToolContent(String content, StringBuilder contentBuilder, AtomicReference<Long> messageIdRef) {
        // 未提供持久化参数（纯展示场景）或内容为空时直接跳过
        if (!isPersistenceEnabled() || StrUtil.isBlank(content)) {
            return;
        }
        contentBuilder.append(content);
        Long messageId = messageIdRef.get();
        String message = contentBuilder.toString();
        if (messageId == null) {
            // 首次：新建一条 AI 消息，记录返回 id 供后续追加
            Long savedMessageId = chatHistoryService.saveMessage(appId, userId, message,
                    ChatHistoryMessageTypeEnum.AI.getValue());
            messageIdRef.set(savedMessageId);
            return;
        }
        // 已有消息：更新为累积后的完整内容，实现追加效果
        chatHistoryService.updateMessage(messageId, message);
    }

    /**
     * 持久化是否可用：需要注入 chatHistoryService 且 appId/userId 均为有效正数。
     */
    private boolean isPersistenceEnabled() {
        return chatHistoryService != null && appId != null && appId > 0 && userId != null && userId > 0;
    }
}
