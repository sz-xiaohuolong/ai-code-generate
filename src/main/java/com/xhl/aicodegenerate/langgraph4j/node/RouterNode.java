package com.xhl.aicodegenerate.langgraph4j.node;

import com.xhl.aicodegenerate.ai.AiCodeGenTypeRoutingService;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 代码生成类型路由节点。
 * <p>
 * 复用项目现有的 AiCodeGenTypeRoutingService，避免在工作流模块里重新发明路由规则。
 * 路由失败时默认退回 HTML 模式，保证工作流不会因为路由模型异常整体中断。
 * </p>
 */
@Slf4j
@Component
public class RouterNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 智能路由");
            context.setCurrentStep("智能路由");
            try {
                AiCodeGenTypeRoutingService routingService = SpringContextUtil.getBean(AiCodeGenTypeRoutingService.class);
                CodeGenTypeEnum generationType = routingService.routeCodeGenType(context.getOriginalPrompt());
                context.setGenerationType(generationType == null ? CodeGenTypeEnum.HTML : generationType);
            } catch (Exception e) {
                log.warn("代码生成类型路由失败，默认使用 HTML 模式: {}", e.getMessage());
                context.setGenerationType(CodeGenTypeEnum.HTML);
            }
            return WorkflowContext.saveContext(context);
        });
    }
}
