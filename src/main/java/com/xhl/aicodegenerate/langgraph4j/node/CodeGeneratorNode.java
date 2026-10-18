package com.xhl.aicodegenerate.langgraph4j.node;

import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.core.handler.JsonMessageStreamHandler;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import com.xhl.aicodegenerate.langgraph4j.model.QualityResult;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 代码生成节点。
 * <p>
 * 工作流不直接调用底层 AI 服务，而是复用 AiCodeGeneratorFacade，
 * 这样原有的流式生成、TokenStream 工具调用、文件保存、Vue 项目保存目录规则都保持一致。
 * </p>
 */
@Slf4j
@Component
public class CodeGeneratorNode {

    private static final Duration CODE_GENERATION_TIMEOUT = Duration.ofMinutes(15);

    public static AsyncNodeAction<MessagesState<String>> create() {
        return create(chunk -> {}, null);
    }

    public static AsyncNodeAction<MessagesState<String>> create(Consumer<String> onContent,
                                                                 ChatMemoryProvider memoryProvider) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 代码生成");
            context.setCurrentStep("代码生成");
            CodeGenTypeEnum generationType = context.getGenerationType() == null
                    ? CodeGenTypeEnum.HTML
                    : context.getGenerationType();
            Long appId = context.getAppId() == null ? 0L : context.getAppId();
            Long userId = context.getUserId() == null ? 0L : context.getUserId();
            String userMessage = buildUserMessage(context);

            context.setGenerationType(generationType);
            context.setGeneratedCodeDir(AppConstant.CODE_OUTPUT_ROOT_DIR
                    + File.separator
                    + generationType.getValue()
                    + "_"
                    + appId);
            try {
                AiCodeGeneratorFacade aiCodeGeneratorFacade = SpringContextUtil.getBean(AiCodeGeneratorFacade.class);
                AppChatMemoryId memoryId = new AppChatMemoryId(appId, userId, context.getTaskId());
                if (context.getTaskId() != null) {
                    var execution = com.xhl.aicodegenerate.generation.GenerationExecution.require(context.getTaskId());
                    execution.checkActive(); context.setGeneratedCodeDir(execution.root().toString());
                }
                var stream = aiCodeGeneratorFacade.generateAndSaveCodeStreamForWorkflow(
                        userMessage, generationType, memoryId, memoryProvider);
                if (generationType == CodeGenTypeEnum.VUE_PROJECT) {
                    stream = new JsonMessageStreamHandler().handle(stream);
                }
                if (context.getTaskId() != null) {
                    var execution = com.xhl.aicodegenerate.generation.GenerationExecution.require(context.getTaskId());
                    stream.doOnSubscribe(subscription -> execution.onCancel(subscription::cancel))
                            .doOnNext(onContent).blockLast(execution.remaining());
                } else stream.doOnNext(onContent).blockLast(CODE_GENERATION_TIMEOUT);
            } catch (Exception e) {
                log.error("代码生成节点异常: {}", e.getMessage(), e);
                context.setErrorMessage("代码生成失败：" + e.getMessage());
            }
            return WorkflowContext.saveContext(context);
        });
    }

    static String buildUserMessage(WorkflowContext context) {
        String userMessage = StrUtil.blankToDefault(context.getEnhancedPrompt(), context.getOriginalPrompt());
        QualityResult qualityResult = context.getQualityResult();
        if (!isQualityCheckFailed(qualityResult)) {
            return userMessage;
        }
        Integer retryCount = context.getQualityCheckRetryCount();
        context.setQualityCheckRetryCount(retryCount == null ? 1 : retryCount + 1);
        StringBuilder errorInfo = new StringBuilder();
        errorInfo.append(userMessage).append("\n\n");
        errorInfo.append("## 上次生成的代码存在以下问题，请基于原需求重新生成并修复\n");
        appendList(errorInfo, "错误列表", qualityResult.getErrors());
        appendList(errorInfo, "修复建议", qualityResult.getSuggestions());
        errorInfo.append("\n请重新生成完整代码，确保上述问题全部修复。");
        return errorInfo.toString();
    }

    private static boolean isQualityCheckFailed(QualityResult qualityResult) {
        return qualityResult != null
                && Boolean.FALSE.equals(qualityResult.getIsValid());
    }

    private static void appendList(StringBuilder builder, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        builder.append("\n### ").append(title).append("\n");
        for (String item : items) {
            builder.append("- ").append(item).append("\n");
        }
    }
}
