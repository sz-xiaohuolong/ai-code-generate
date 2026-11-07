package com.xhl.aicodegenerate.observability;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.TreeMap;

/** 模型版本仅从受控模板及非秘密参数生成，不序列化提示词、工具参数或 SDK 客户端。 */
public final class TraceModelMetadata {
    private static final String PROMPT_DIGEST = promptDigest();
    private TraceModelMetadata() { }

    public static TraceSession.Step start(TraceSession trace, String name, String alias,
            ChatRequestParameters parameters, TraceSession.Step parent) {
        if (trace == null) {
            return null;
        }
        var step = trace.startStep(TraceSession.Kind.MODEL, name, parent);
        step.model(alias, parameters == null ? null : parameters.modelName(), PROMPT_DIGEST, configDigest(parameters));
        if (PROMPT_DIGEST == null) {
            trace.markPartial();
        }
        return step;
    }

    public static void complete(TraceSession.Step step, ChatResponse response) {
        if (step == null) {
            return;
        }
        var usage = response == null ? null : response.tokenUsage();
        if (response != null) {
            step.actualModel(response.modelName());
        }
        step.complete(usage == null || usage.inputTokenCount() == null ? null : usage.inputTokenCount().longValue(),
                usage == null || usage.outputTokenCount() == null ? null : usage.outputTokenCount().longValue(),
                response == null || response.finishReason() == null ? null : response.finishReason().name());
    }

    private static String configDigest(ChatRequestParameters parameters) {
        if (parameters == null) {
            return null;
        }
        var safe = new TreeMap<String, Object>();
        safe.put("modelName", parameters.modelName());
        safe.put("temperature", parameters.temperature());
        safe.put("topP", parameters.topP());
        safe.put("topK", parameters.topK());
        safe.put("frequencyPenalty", parameters.frequencyPenalty());
        safe.put("presencePenalty", parameters.presencePenalty());
        safe.put("maxOutputTokens", parameters.maxOutputTokens());
        return DigestUtil.sha256Hex(JSONUtil.toJsonStr(safe));
    }

    private static String promptDigest() {
        List<String> names = List.of("codegen-html-system-prompt.txt", "codegen-multi-file-system-prompt.txt",
                "codegen-vue-project-system-prompt.txt", "codegen-routing-system-prompt.txt",
                "code-quality-check-system-prompt.txt", "image-collection-system-prompt.txt",
                "image-collection-plan-system-prompt.txt");
        var manifest = new StringBuilder("generation-prompts-v1\n");
        try {
            for (String name : names) {
                try (var input = new ClassPathResource("prompt/" + name).getInputStream()) {
                    manifest.append(name).append(':').append(DigestUtil.sha256Hex(input)).append('\n');
                }
            }
            return DigestUtil.sha256Hex(manifest.toString());
        } catch (IOException error) {
            return null;
        }
    }
}
