package com.xhl.aicodegenerate.langgraph4j.node;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.langgraph4j.model.ImageResource;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 提示词增强节点。
 * <p>
 * 这里不改变用户需求的核心含义，只把上一步收集到的图片/架构图素材补充到提示词后面，
 * 让后续代码生成模型能在页面中引用这些资源。
 * </p>
 */
@Slf4j
@Component
public class PromptEnhancerNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 提示词增强");
            context.setCurrentStep("提示词增强");
            String originalPrompt = StrUtil.blankToDefault(context.getOriginalPrompt(), "");
            StringBuilder enhancedPrompt = new StringBuilder(originalPrompt);

            if (CollUtil.isNotEmpty(context.getImageList())) {
                enhancedPrompt.append("\n\n## 可用素材资源\n");
                for (ImageResource imageResource : context.getImageList()) {
                    String category = imageResource.getCategory() == null
                            ? "图片"
                            : imageResource.getCategory().getText();
                    enhancedPrompt.append("- ")
                            .append(category)
                            .append("：")
                            .append(StrUtil.blankToDefault(imageResource.getDescription(), "素材"))
                            .append("（")
                            .append(imageResource.getUrl())
                            .append("）\n");
                }
            } else if (StrUtil.isNotBlank(context.getImageListStr())) {
                enhancedPrompt.append("\n\n## 可用素材资源\n")
                        .append(context.getImageListStr());
            }

            context.setEnhancedPrompt(enhancedPrompt.toString());
            return WorkflowContext.saveContext(context);
        });
    }
}
