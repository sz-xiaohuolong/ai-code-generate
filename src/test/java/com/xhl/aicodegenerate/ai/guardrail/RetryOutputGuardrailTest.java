package com.xhl.aicodegenerate.ai.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.GuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RetryOutputGuardrailTest {

    private final RetryOutputGuardrail guardrail = new RetryOutputGuardrail();

    @Test
    void shouldRepromptWhenResponseIsEmpty() {
        OutputGuardrailResult result = validate("   ");

        assertReprompt(result, "响应内容为空", "请重新生成完整的内容");
    }

    @Test
    void shouldRepromptWhenResponseIsTooShort() {
        OutputGuardrailResult result = validate("内容太短");

        assertReprompt(result, "响应内容过短", "请提供更详细的内容");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "这里包含 password 字段，不应直接输出",
            "请保存这个 API KEY 到页面中",
            "下面是用户的私钥和证书信息"
    })
    void shouldRepromptWhenResponseContainsCredentials(String response) {
        OutputGuardrailResult result = validate(response);

        assertReprompt(result, "包含敏感信息", "请重新生成内容，避免包含敏感信息");
    }

    @Test
    void shouldAllowDetailedSafeResponse() {
        OutputGuardrailResult result = validate("这是一个完整且不包含敏感凭据的页面实现说明。");

        assertThat(result.result()).isEqualTo(GuardrailResult.Result.SUCCESS);
        assertThat(result.isRetry()).isFalse();
    }

    private OutputGuardrailResult validate(String response) {
        return guardrail.validate(AiMessage.from(response));
    }

    private void assertReprompt(OutputGuardrailResult result, String failureMessage, String reprompt) {
        // LangChain4j 1.15 将可重试的 reprompt 标记为 FATAL，
        // 再通过 isRetry/isReprompt 区分“终止”与“重新调用模型”。
        assertThat(result.result()).isEqualTo(GuardrailResult.Result.FATAL);
        assertThat(result.isRetry()).isTrue();
        assertThat(result.isReprompt()).isTrue();
        assertThat(result.getReprompt()).contains(reprompt);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().getFirst().message()).isEqualTo(failureMessage);
    }
}
