package com.xhl.aicodegenerate.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RoutingChatModelConfigTest {

    @Test
    void shouldFallbackToPrimaryModelWhenRoutingCredentialIsMissing() {
        RoutingChatModelConfig config = new RoutingChatModelConfig();
        ChatModel primaryModel = mock(ChatModel.class);

        ChatModel routingModel = config.routingChatModel(primaryModel);

        assertThat(routingModel).isSameAs(primaryModel);
    }

    @Test
    void shouldCreateLowCostQwenRoutingModelWithBoundedOutput() {
        RoutingChatModelConfig config = new RoutingChatModelConfig();
        config.setApiKey("test-key");
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setModelName("qwen-turbo");
        config.setMaxTokens(100);
        config.setMaxRetries(3);

        ChatModel routingModel = config.routingChatModel(mock(ChatModel.class));

        assertThat(routingModel).isInstanceOf(OpenAiChatModel.class);
        OpenAiChatModel openAiChatModel = (OpenAiChatModel) routingModel;
        assertThat(openAiChatModel.defaultRequestParameters().modelName()).isEqualTo("qwen-turbo");
        assertThat(openAiChatModel.defaultRequestParameters().maxOutputTokens()).isEqualTo(100);
        assertThat(ReflectionTestUtils.getField(openAiChatModel, "maxRetries")).isEqualTo(3);
    }
}
