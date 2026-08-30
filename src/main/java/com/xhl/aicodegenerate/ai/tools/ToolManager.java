package com.xhl.aicodegenerate.ai.tools;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具管理器。
 * <p>
 * 通过 {@code @Resource} 注入 {@link BaseTool} 数组，Spring 会自动收集容器中所有
 * 继承 {@link BaseTool} 的 Bean；在 {@code @PostConstruct} 中按工具名建立索引，
 * 供后续按名称查找以及向 LangChain4j 统一注册全部工具。
 * </p>
 */
@Component
public class ToolManager {

    private final Map<String, BaseTool> toolMap = new ConcurrentHashMap<>();

    @Resource
    private BaseTool[] tools;

    @PostConstruct
    public void init() {
        for (BaseTool tool : tools) {
            toolMap.put(tool.getToolName(), tool);
        }
    }

    /**
     * 按工具名称获取已注册工具。
     *
     * @param toolName 工具名称
     * @return 对应工具实例，未注册时返回 null
     */
    public BaseTool getTool(String toolName) {
        return toolMap.get(toolName);
    }

    /**
     * 返回全部已注册工具，用于 LangChain4j AiService 统一注册所有可用工具。
     */
    public BaseTool[] getAllTools() {
        return tools;
    }
}
