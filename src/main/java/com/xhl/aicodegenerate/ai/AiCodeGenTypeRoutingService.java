package com.xhl.aicodegenerate.ai;

import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.SystemMessage;

/**
 * AI代码生成类型智能路由服务
 * 使用结构化输出直接返回枚举类型
 */
public interface AiCodeGenTypeRoutingService {

    /**
     * 根据用户需求智能选择代码生成类型
     *
     * @param userPrompt 用户输入的需求描述
     * @return 推荐的代码生成类型
     */
    @SystemMessage(fromResource = "prompt/codegen-routing-system-prompt.txt")
    CodeGenTypeEnum routeCodeGenType(String userPrompt);

    /** 显式传递本次创建追踪；原调用入口继续兼容。 */
    default CodeGenTypeEnum routeCodeGenType(String userPrompt, com.xhl.aicodegenerate.observability.TraceSession trace) {
        return routeCodeGenType(userPrompt);
    }
}
