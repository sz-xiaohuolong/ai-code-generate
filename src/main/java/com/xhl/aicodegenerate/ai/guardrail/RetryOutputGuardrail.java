package com.xhl.aicodegenerate.ai.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;

import java.util.List;

/**
 * 对同步 AI 输出执行基础质量检查，并通过 reprompt 请求重新生成。
 */
public class RetryOutputGuardrail implements OutputGuardrail {

    private static final int MIN_RESPONSE_LENGTH = 10;

    private static final List<String> SENSITIVE_WORDS = List.of(
            "密码", "password", "secret", "token",
            "api key", "私钥", "证书", "credential"
    );

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLlm) {
        String response = responseFromLlm.text();
        if (response == null || response.trim().isEmpty()) {
            return reprompt("响应内容为空", "请重新生成完整的内容");
        }
        if (response.trim().length() < MIN_RESPONSE_LENGTH) {
            return reprompt("响应内容过短", "请提供更详细的内容");
        }
        if (containsSensitiveContent(response)) {
            return reprompt("包含敏感信息", "请重新生成内容，避免包含敏感信息");
        }
        return success();
    }

    private boolean containsSensitiveContent(String response) {
        String lowerResponse = response.toLowerCase();
        return SENSITIVE_WORDS.stream().anyMatch(lowerResponse::contains);
    }
}
