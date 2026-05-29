package com.xhl.aicodegenerate.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import com.xhl.aicodegenerate.model.dto.ai.CodeGenStreamMessage;
import com.xhl.aicodegenerate.model.enums.ChatHistoryMessageTypeEnum;
import com.xhl.aicodegenerate.model.enums.CodeGenStreamMessageTypeEnum;
import com.xhl.aicodegenerate.service.ChatHistoryService;
import reactor.core.publisher.Flux;

import java.nio.file.Paths;
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

    private static final String WRITE_FILE_TOOL_NAME = "writeFile";

    private static final String VUE_PROJECT_DIR_PREFIX = "vue_project_";

    private final ChatHistoryService chatHistoryService;

    private final Long appId;

    private final Long userId;

    private final VueProjectBuilder vueProjectBuilder;

    public JsonMessageStreamHandler() {
        this(null, null, null);
    }

    public JsonMessageStreamHandler(ChatHistoryService chatHistoryService, Long appId, Long userId) {
        this(chatHistoryService, appId, userId, new VueProjectBuilder());
    }

    public JsonMessageStreamHandler(ChatHistoryService chatHistoryService, Long appId, Long userId,
                                    VueProjectBuilder vueProjectBuilder) {
        this.chatHistoryService = chatHistoryService;
        this.appId = appId;
        this.userId = userId;
        this.vueProjectBuilder = vueProjectBuilder;
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
                    String toolRequestMessage = "\n\n[选择工具] " + getToolDisplayName(toolName) + "\n";
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
        }).doOnComplete(this::buildVueProject);
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

    private String formatToolExecutedMessage(CodeGenStreamMessage message) {
        if (!WRITE_FILE_TOOL_NAME.equals(message.getName())) {
            return "\n\n[工具调用] " + getToolDisplayName(message.getName()) + "\n" + StrUtil.nullToEmpty(message.getData()) + "\n";
        }
        JSONObject arguments = parseArguments(message.getArguments());
        String relativeFilePath = arguments.getStr("relativeFilePath");
        String content = arguments.getStr("content");
        if (StrUtil.isBlank(relativeFilePath)) {
            return "\n\n[工具调用] 写入文件完成\n" + StrUtil.nullToEmpty(message.getData()) + "\n";
        }
        if (StrUtil.isBlank(content)) {
            return "\n\n[工具调用] 写入文件 " + relativeFilePath + "\n" + StrUtil.nullToEmpty(message.getData()) + "\n";
        }
        return "\n\n[工具调用] 写入文件 " + relativeFilePath
                + "\n```" + getMarkdownLanguage(relativeFilePath)
                + "\n" + content
                + "\n```\n";
    }

    private JSONObject parseArguments(String arguments) {
        if (StrUtil.isBlank(arguments) || !JSONUtil.isTypeJSON(arguments)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String getToolDisplayName(String toolName) {
        if (WRITE_FILE_TOOL_NAME.equals(toolName)) {
            return "写入文件";
        }
        return StrUtil.blankToDefault(toolName, "未知工具");
    }

    private String getMarkdownLanguage(String relativeFilePath) {
        String suffix = StrUtil.subAfter(relativeFilePath, ".", true);
        return switch (suffix) {
            case "vue" -> "vue";
            case "js", "mjs", "cjs" -> "javascript";
            case "ts" -> "typescript";
            case "css" -> "css";
            case "html" -> "html";
            case "json" -> "json";
            case "md" -> "markdown";
            default -> "";
        };
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

    private void buildVueProject() {
        if (vueProjectBuilder == null || appId == null || appId <= 0) {
            return;
        }
        String projectPath = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, VUE_PROJECT_DIR_PREFIX + appId).toString();
        vueProjectBuilder.buildProjectAsync(projectPath);
    }
}
