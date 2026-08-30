package com.xhl.aicodegenerate.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.model.dto.ai.CodeGenStreamMessage;

/**
 * 工具基类。
 * <p>
 * 定义所有工具的通用契约（工具名 / 展示名）以及生成不同阶段向用户展示信息的统一方法。
 * 具体工具继承本类并声明为 Spring Bean，由 {@link ToolManager} 统一收集和管理。
 * </p>
 * <p>
 * 注意：本类中的展示方法均不标注 {@code @Tool}，避免被 LangChain4j 误注册为可调用工具。
 * </p>
 */
public abstract class BaseTool {

    /**
     * 工具名称，与 LangChain4j {@code @Tool} 注解的方法名保持一致。
     */
    public abstract String getToolName();

    /**
     * 工具展示名称，用于向用户展示。
     */
    public abstract String getDisplayName();

    /**
     * 工具选择阶段的统一展示文案。
     */
    public String formatToolRequestMessage() {
        return "\n\n[选择工具] " + getDisplayName() + "\n";
    }

    /**
     * 工具执行完成阶段的统一展示文案。
     * <p>
     * 默认直接拼接工具执行结果；有特殊展示需求的工具（如写入文件）可覆盖本方法。
     * </p>
     */
    public String formatToolExecutedMessage(CodeGenStreamMessage message) {
        return "\n\n[工具调用] " + getDisplayName() + "\n" + StrUtil.nullToEmpty(message.getData()) + "\n";
    }

    /**
     * 根据文件相对路径推断 Markdown 代码块语言。
     */
    protected static String getMarkdownLanguage(String relativeFilePath) {
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

    /**
     * 解析工具调用参数 JSON，解析失败时返回空对象。
     */
    protected static JSONObject parseArguments(String arguments) {
        if (StrUtil.isBlank(arguments) || !JSONUtil.isTypeJSON(arguments)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
