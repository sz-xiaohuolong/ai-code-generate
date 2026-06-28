package com.xhl.aicodegenerate.langgraph4j.ai;

import com.xhl.aicodegenerate.langgraph4j.tools.ImageSearchTool;
import com.xhl.aicodegenerate.langgraph4j.tools.KrokiMermaidDiagramTool;
import com.xhl.aicodegenerate.langgraph4j.tools.PlaceholderLogoTool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图片收集 AI 服务工厂。
 */
@Configuration
public class ImageCollectionServiceFactory {

    @Resource
    private ChatModel chatModel;

    @Resource
    private KrokiMermaidDiagramTool krokiMermaidDiagramTool;

    @Resource
    private PlaceholderLogoTool placeholderLogoTool;

    @Resource
    private ImageSearchTool imageSearchTool;

    @Bean
    public ImageCollectionService imageCollectionService() {
        return AiServices.builder(ImageCollectionService.class)
                .chatModel(chatModel)
                .tools(placeholderLogoTool, krokiMermaidDiagramTool, imageSearchTool)
                .build();
    }
}
