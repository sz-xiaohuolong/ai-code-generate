package com.xhl.aicodegenerate.config;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 低成本智能路由模型配置。
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.routing-chat-model")
@Data
@Slf4j
public class RoutingChatModelConfig {

    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private String apiKey;

    private String modelName = "qwen-turbo";

    private int maxTokens = 100;

    private int maxRetries = 3;

    private boolean logRequests = true;

    private boolean logResponses = true;

    @Bean("routingChatModel")
    public ChatModel routingChatModel(@Qualifier("openAiChatModel") ChatModel primaryChatModel) {
        if (StrUtil.isBlank(apiKey)) {
            log.warn("未配置 DASHSCOPE_API_KEY，智能路由暂时回退到主模型");
            return primaryChatModel;
        }
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .maxRetries(maxRetries)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }
}
