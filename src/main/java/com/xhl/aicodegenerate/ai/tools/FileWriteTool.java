package com.xhl.aicodegenerate.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.model.dto.ai.CodeGenStreamMessage;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 文件写入工具
 * 支持 AI 通过工具调用的方式写入文件
 */
@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    @Override
    public String getToolName() {
        return "writeFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件";
    }

    @Override
    public String formatToolExecutedMessage(CodeGenStreamMessage message) {
        JSONObject arguments = parseArguments(message.getArguments());
        String relativeFilePath = arguments.getStr("relativeFilePath");
        String content = arguments.getStr("content");
        if (StrUtil.isBlank(relativeFilePath)) {
            return "\n\n[工具调用] " + getDisplayName() + "完成\n" + StrUtil.nullToEmpty(message.getData()) + "\n";
        }
        if (StrUtil.isBlank(content)) {
            return "\n\n[工具调用] " + getDisplayName() + " " + relativeFilePath + "\n" + StrUtil.nullToEmpty(message.getData()) + "\n";
        }
        return "\n\n[工具调用] " + getDisplayName() + " " + relativeFilePath
                + "\n```" + getMarkdownLanguage(relativeFilePath)
                + "\n" + content
                + "\n```\n";
    }

    @Tool("写入文件到指定路径")
    public String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要写入文件的内容")
            String content,
            @ToolMemoryId AppChatMemoryId memoryId
    ) {
        try {
            Long appId = memoryId.getAppId();
            Path path = Paths.get(relativeFilePath);
            if (!path.isAbsolute()) {
                // 相对路径处理，创建基于 appId 的项目目录
                String projectDirName = "vue_project_" + appId;
                Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName);
                path = projectRoot.resolve(relativeFilePath);
            }
            // 创建父目录（如果不存在）
            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            // 写入文件内容
            Files.write(path, content.getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            log.info("成功写入文件: {}", path.toAbsolutePath());
            // 注意要返回相对路径，不能让 AI 把文件绝对路径返回给用户
            return "文件写入成功: " + relativeFilePath + "。若所有必要文件已经写入完成，请停止调用工具并输出生成完毕提示。";
        } catch (IOException e) {
            String errorMessage = "文件写入失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }
}
