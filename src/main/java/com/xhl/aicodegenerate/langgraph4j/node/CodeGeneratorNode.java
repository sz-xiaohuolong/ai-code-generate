package com.xhl.aicodegenerate.langgraph4j.node;

import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;

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
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 代码生成");
            context.setCurrentStep("代码生成");
            CodeGenTypeEnum generationType = context.getGenerationType() == null
                    ? CodeGenTypeEnum.HTML
                    : context.getGenerationType();
            Long appId = context.getAppId() == null ? 0L : context.getAppId();
            Long userId = context.getUserId() == null ? 0L : context.getUserId();
            String userMessage = StrUtil.blankToDefault(context.getEnhancedPrompt(), context.getOriginalPrompt());

            context.setGenerationType(generationType);
            context.setGeneratedCodeDir(AppConstant.CODE_OUTPUT_ROOT_DIR
                    + File.separator
                    + generationType.getValue()
                    + "_"
                    + appId);
            try {
                AiCodeGeneratorFacade aiCodeGeneratorFacade = SpringContextUtil.getBean(AiCodeGeneratorFacade.class);
                AppChatMemoryId memoryId = new AppChatMemoryId(appId, userId);
                if (generationType == CodeGenTypeEnum.VUE_PROJECT) {
                    // Vue 工程依赖 TokenStream 工具调用实时写文件，所以继续走流式链路。
                    aiCodeGeneratorFacade.generateAndSaveCodeStream(userMessage, generationType, memoryId)
                            .blockLast(CODE_GENERATION_TIMEOUT);
                } else {
                    // HTML / MULTI_FILE 在工作流里不需要实时展示流式片段，直接走结构化生成和保存。
                    // 这样可以避开 SSE 长连接被底层 HTTP 客户端提前关闭后，doOnComplete 无法触发保存的问题。
                    File savedDir = aiCodeGeneratorFacade.generateAndSaveCode(userMessage, generationType, memoryId);
                    context.setGeneratedCodeDir(savedDir.getAbsolutePath());
                }
            } catch (Exception e) {
                log.error("代码生成节点异常: {}", e.getMessage(), e);
                context.setErrorMessage("代码生成失败：" + e.getMessage());
            }
            return WorkflowContext.saveContext(context);
        });
    }
}
