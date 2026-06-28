package com.xhl.aicodegenerate.langgraph4j;

import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.langgraph4j.node.CodeGeneratorNode;
import com.xhl.aicodegenerate.langgraph4j.node.ImageCollectorNode;
import com.xhl.aicodegenerate.langgraph4j.node.ProjectBuilderNode;
import com.xhl.aicodegenerate.langgraph4j.node.PromptEnhancerNode;
import com.xhl.aicodegenerate.langgraph4j.node.RouterNode;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * AI 代码生成工作流。LangGraph4j 主编排服务
 * <p>
 * 对应核心链路：
 * 图片收集 -> 提示词增强 -> 智能路由 -> 代码生成 -> 项目构建。
 * 每个节点只处理自己的职责，业务能力继续复用项目中已有的 AI 服务、代码生成门面和 Vue 构建器。
 * </p>
 */
@Slf4j
@Service
public class CodeGenWorkflow {

    private static final String IMAGE_COLLECTOR = "image_collector";

    private static final String PROMPT_ENHANCER = "prompt_enhancer";

    private static final String ROUTER = "router";

    private static final String CODE_GENERATOR = "code_generator";

    private static final String PROJECT_BUILDER = "project_builder";

    private static final AtomicLong TEMP_APP_ID_GENERATOR = new AtomicLong(System.currentTimeMillis());

    public WorkflowContext executeWorkflow(String originalPrompt) {
        // 直接 new CodeGenWorkflow() 跑测试时没有真实应用 id。
        // 使用临时 appId 隔离 ChatMemory，避免多条真实大模型测试共享 appId=0 后互相污染工具调用上下文。
        return executeWorkflow(originalPrompt, TEMP_APP_ID_GENERATOR.incrementAndGet(), 0L);
    }

    public WorkflowContext executeWorkflow(String originalPrompt, Long appId, Long userId) {
        WorkflowContext initialContext = WorkflowContext.builder()
                .currentStep("初始化")
                .originalPrompt(originalPrompt)
                .appId(appId)
                .userId(userId)
                .build();

        WorkflowContext finalContext = initialContext;
        try {
            // 创建工作流
            CompiledGraph<MessagesState<String>> workflow = createWorkflow();
            // 执行工作流，本质上是把 initialContext 放进 LangGraph4j 的状态容器里，然后从容器里取结果。
            for (NodeOutput<MessagesState<String>> step
                    : workflow.stream(Map.of(WorkflowContext.WORKFLOW_CONTEXT_KEY, initialContext))) {
                WorkflowContext currentContext = WorkflowContext.getContext(step.state());
                if (currentContext != null) {
                    // 每一个节点执行完后都会添加一个 WorkflowContext
                    finalContext = currentContext;
                    log.info("工作流步骤完成: {}, context={}", currentContext.getCurrentStep(), currentContext);
                }
            }
            return finalContext;
        } catch (GraphStateException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "LangGraph4j 工作流执行失败：" + e.getMessage());
        }
    }

    //流水线设计图
    public String getMermaidGraph() {
        try {
            GraphRepresentation graph = createWorkflow().getGraph(GraphRepresentation.Type.MERMAID);
            return graph.content();
        } catch (GraphStateException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "LangGraph4j 工作流图生成失败：" + e.getMessage());
        }
    }

    private CompiledGraph<MessagesState<String>> createWorkflow() throws GraphStateException {
        return new MessagesStateGraph<String>()
                .addNode(IMAGE_COLLECTOR, ImageCollectorNode.create())
                .addNode(PROMPT_ENHANCER, PromptEnhancerNode.create())
                .addNode(ROUTER, RouterNode.create())
                .addNode(CODE_GENERATOR, CodeGeneratorNode.create())
                .addNode(PROJECT_BUILDER, ProjectBuilderNode.create())
                .addEdge(START, IMAGE_COLLECTOR)
                .addEdge(IMAGE_COLLECTOR, PROMPT_ENHANCER)
                .addEdge(PROMPT_ENHANCER, ROUTER)
                .addEdge(ROUTER, CODE_GENERATOR)
                .addEdge(CODE_GENERATOR, PROJECT_BUILDER)
                .addEdge(PROJECT_BUILDER, END)
                .compile();
    }
}
