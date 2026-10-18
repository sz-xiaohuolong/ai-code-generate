package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Consumer;

/** 两种生成模式共用的真实构建、有限修复及预览验证闭环。 */
@Service
public class VueDeliveryService {
    private final VueProjectBuilder builder;
    private final PreviewVerifier verifier;
    private final VueRepairer repairer;
    private final int maxRepairs;
    @Autowired public VueDeliveryService(VueProjectBuilder builder, PreviewVerifier verifier, VueRepairer repairer,
            @Value("${generation.max-build-repairs:2}") int maxRepairs) {
        this.builder = builder; this.verifier = verifier; this.repairer = repairer; this.maxRepairs = Math.max(0, Math.min(2, maxRepairs));
    }
    public void deliver(GenerationExecution run, AppChatMemoryId memoryId, Consumer<String> output, Consumer<Map<String, Object>> progress) {
        Set<String> seen = new HashSet<>();
        for (int attempt = 0; ; attempt++) {
            run.checkActive();
            progress.accept(event("vue_build", "项目构建", "running", "正在安装依赖并执行真实构建", attempt));
            BuildResult result = builder.buildDetailed(run.root(), run);
            if (result.success()) {
                progress.accept(event("vue_build", "项目构建", "completed", "真实构建已通过", attempt));
                progress.accept(event("preview_check", "预览检查", "running", "正在检查入口、资源与 Vue 挂载", attempt));
                result = verifier.verify(run.root(), true, run);
                if (result.success()) {
                    progress.accept(event("preview_check", "预览检查", "completed", "预览检查已通过", attempt)); return;
                }
            }
            String diagnostic = result.diagnostic().replace(run.root().toString(), "[项目]");
            String fingerprint = result.stage() + ":" + (result.stderr().isBlank() ? result.stdout() : result.stderr())
                    .replaceAll("\\u001B\\[[;\\d]*m", "").replaceAll("\\d+(?:\\.\\d+)?\\s*(?:ms|seconds?|s)\\b", "[耗时]").strip();
            if (result.kind() != BuildResult.Kind.CODE || attempt >= maxRepairs || !seen.add(fingerprint)) {
                progress.accept(event("vue_build", "交付检查", "failed", diagnostic, attempt));
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "Vue 交付失败：" + diagnostic);
            }
            progress.accept(event("vue_repair", "定向修复", "retrying", "依据真实诊断执行第 " + (attempt + 1) + " 轮修复", attempt + 1));
            repairer.repair(memoryId, diagnostic, output);
            run.checkActive();
        }
    }
    public static Map<String, Object> event(String id, String name, String status, String message, int retries) {
        return Map.of("type", "workflow_progress", "id", id, "name", name, "status", status, "message", message, "retryCount", retries);
    }
}
