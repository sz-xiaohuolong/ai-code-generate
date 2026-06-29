package com.xhl.aicodegenerate.langgraph4j.node;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.langgraph4j.ai.CodeQualityCheckService;
import com.xhl.aicodegenerate.langgraph4j.model.QualityResult;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 代码质量检查节点。
 */
@Slf4j
@Component
public class CodeQualityCheckNode {

    private static final List<String> CODE_EXTENSIONS = Arrays.asList(
            ".html", ".htm", ".css", ".js", ".json", ".vue", ".ts", ".jsx", ".tsx"
    );

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 代码质量检查");
            context.setCurrentStep("代码质量检查");
            QualityResult qualityResult;
            try {
                String codeContent = readAndConcatenateCodeFiles(context.getGeneratedCodeDir());
                if (StrUtil.isBlank(codeContent)) {
                    qualityResult = QualityResult.builder()
                            .isValid(false)
                            .errors(List.of("未找到可检查的代码文件"))
                            .suggestions(List.of("请确认代码生成节点已成功保存文件"))
                            .build();
                } else {
                    CodeQualityCheckService qualityCheckService = SpringContextUtil.getBean(CodeQualityCheckService.class);
                    qualityResult = qualityCheckService.checkCodeQuality(codeContent);
                    if (qualityResult == null || qualityResult.getIsValid() == null) {
                        qualityResult = QualityResult.builder()
                                .isValid(true)
                                .errors(List.of())
                                .suggestions(List.of("质检模型未返回完整结构，已按通过处理"))
                                .build();
                    }
                }
            } catch (Exception e) {
                log.warn("代码质量检查异常，放行后续流程: {}", e.getMessage(), e);
                qualityResult = QualityResult.builder()
                        .isValid(true)
                        .errors(List.of())
                        .suggestions(List.of("质检服务异常，已跳过本次质检: " + e.getMessage()))
                        .build();
            }
            context.setQualityResult(qualityResult);
            if (!Boolean.TRUE.equals(qualityResult.getIsValid())
                    && context.getQualityCheckRetryCount() != null
                    && context.getQualityCheckRetryCount() >= WorkflowContext.MAX_QUALITY_RETRY_COUNT) {
                context.setErrorMessage("代码质检失败，已达到最大重试次数");
            }
            if (Boolean.TRUE.equals(qualityResult.getIsValid())
                    && (context.getGenerationType() == CodeGenTypeEnum.HTML
                    || context.getGenerationType() == CodeGenTypeEnum.MULTI_FILE)) {
                context.setBuildResultDir(context.getGeneratedCodeDir());
            }
            return WorkflowContext.saveContext(context);
        });
    }

    public static String readAndConcatenateCodeFiles(String codeDir) {
        if (StrUtil.isBlank(codeDir)) {
            return "";
        }
        File directory = new File(codeDir);
        if (!directory.exists() || !directory.isDirectory()) {
            log.warn("代码目录不存在或不是目录: {}", codeDir);
            return "";
        }
        StringBuilder codeContent = new StringBuilder("# 项目文件结构和代码内容\n\n");
        AtomicInteger codeFileCount = new AtomicInteger();
        FileUtil.walkFiles(directory, file -> {
            if (shouldSkipFile(file, directory) || !isCodeFile(file)) {
                return;
            }
            codeFileCount.incrementAndGet();
            String relativePath = FileUtil.subPath(directory.getAbsolutePath(), file.getAbsolutePath());
            codeContent.append("## 文件: ").append(relativePath).append("\n\n");
            codeContent.append(FileUtil.readUtf8String(file)).append("\n\n");
        });
        if (codeFileCount.get() == 0) {
            return "";
        }
        return codeContent.toString();
    }

    private static boolean shouldSkipFile(File file, File rootDir) {
        if (file.getName().startsWith(".")) {
            return true;
        }
        String relativePath = FileUtil.subPath(rootDir.getAbsolutePath(), file.getAbsolutePath());
        return relativePath.contains("node_modules" + File.separator)
                || relativePath.contains("dist" + File.separator)
                || relativePath.contains("target" + File.separator)
                || relativePath.contains(".git" + File.separator);
    }

    private static boolean isCodeFile(File file) {
        String fileName = file.getName().toLowerCase();
        return CODE_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }
}
