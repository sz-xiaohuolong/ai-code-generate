package com.xhl.aicodegenerate.langgraph4j.node;

import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.langgraph4j.ai.ImageCollectionService;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 图片素材收集节点。
 * <p>
 * 这一层只负责把用户需求交给图片收集 AI 服务，并把返回的素材说明写入工作流上下文。
 * 图片生成、架构图渲染、Logo 兜底等细节都封装在 ImageCollectionService 的工具里。
 * </p>
 */
@Slf4j
@Component
public class ImageCollectorNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 图片收集");
            context.setCurrentStep("图片收集");
            if (StrUtil.isBlank(context.getOriginalPrompt())) {
                context.setImageListStr("");
                return WorkflowContext.saveContext(context);
            }
            try {
                ImageCollectionService imageCollectionService = SpringContextUtil.getBean(ImageCollectionService.class);
                String imageListStr = imageCollectionService.collectImages(context.getOriginalPrompt());
                context.setImageListStr(StrUtil.blankToDefault(imageListStr, ""));
            } catch (Exception e) {
                log.warn("图片素材收集失败，继续执行后续代码生成流程: {}", e.getMessage());
                context.setImageListStr("");
            }
            return WorkflowContext.saveContext(context);
        });
    }
}
