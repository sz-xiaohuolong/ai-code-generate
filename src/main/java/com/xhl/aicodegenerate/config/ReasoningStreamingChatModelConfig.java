package com.xhl.aicodegenerate.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.chat-model")
@Data
public class ReasoningStreamingChatModelConfig {

    private String baseUrl;

    private String apiKey;

    /**
     * 推理流式模型（用于 Vue 项目生成，带工具调用）
     */
    @Bean
    public StreamingChatModel reasoningStreamingChatModel() {
        // 为了测试方便临时修改
        final String modelName = "deepseek-v4-flash";
        final int maxTokens = 8192;
        // 生产环境使用：
        // final String modelName = "deepseek-v4-pro";
        // final int maxTokens = 32768;
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                // DeepSeek thinking mode 会返回 reasoning_content。
                // 工具调用后的下一轮请求必须把该字段带回 API，否则 DeepSeek 会返回 400。
                .returnThinking(true)
                .sendThinking(true, "reasoning_content")
                // DeepSeek / Qwen 等 OpenAI-compatible 流式工具调用通常会完整返回 tool_call id。
                // 关闭增量累积，避免把 id 片段重复拼接成非法 tool_call_id。
                .accumulateToolCallId(false)
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
