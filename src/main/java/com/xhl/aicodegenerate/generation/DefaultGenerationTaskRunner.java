package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.ai.tools.ToolManager;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.core.handler.JsonMessageStreamHandler;
import com.xhl.aicodegenerate.langgraph4j.CodeGenWorkflow;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.function.Consumer;

/** 正式历史只接收一次用户输入和最终结果，内部生成、质检和修复使用本轮记忆。 */
@Service
public class DefaultGenerationTaskRunner implements GenerationTaskRunner {
    @jakarta.annotation.Resource private com.xhl.aicodegenerate.service.AppScreenshotService screenshots;
    @org.springframework.beans.factory.annotation.Value("${server.port:8123}") private int serverPort = 8123;
    @org.springframework.beans.factory.annotation.Value("${server.servlet.context-path:/api}") private String contextPath = "/api";
    private final ChatMemoryProvider memories;
    private final AiCodeGeneratorFacade facade;
    private final CodeGenWorkflow workflow;
    private final VueDeliveryService delivery;
    private final PreviewVerifier verifier;
    private final ToolManager tools;
    private final TaskHistoryRecorder history;
    public DefaultGenerationTaskRunner(ChatMemoryProvider memories, AiCodeGeneratorFacade facade, CodeGenWorkflow workflow,
            VueDeliveryService delivery, PreviewVerifier verifier, ToolManager tools, TaskHistoryRecorder history) {
        this.memories = memories; this.facade = facade; this.workflow = workflow; this.delivery = delivery; this.verifier = verifier; this.tools = tools; this.history = history;
    }
    @Override public void run(GenerationTask task, GenerationExecution run, Consumer<String> output, Consumer<Map<String, Object>> progress) {
        AppChatMemoryId id = new AppChatMemoryId(task.getAppId(), task.getUserId(), task.getId());
        var formal = memories.get(new AppChatMemoryId(task.getAppId(), task.getUserId()));
        var scratch = MessageWindowChatMemory.withMaxMessages(20);
        scratch.add(formal.messages());
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(task.getCodeGenType());
        try {
            if (type == null) throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用代码类型无效，请重新创建应用");
            boolean isWorkflow = "workflow".equals(task.getGenerationMode());
            var stream = isWorkflow ? workflow.generateForTask(task.getPrompt(), id, type, scratch)
                    : facade.generateAndSaveCodeStreamForWorkflow(task.getPrompt(), type, id, ignored -> scratch);
            if (!isWorkflow && type == CodeGenTypeEnum.VUE_PROJECT) stream = new JsonMessageStreamHandler(null, null, null, tools).handle(stream);
            stream.doOnSubscribe(subscription -> run.onCancel(subscription::cancel)).doOnNext(output).blockLast(run.remaining());
            run.checkActive();
            if (type == CodeGenTypeEnum.VUE_PROJECT) {
                if (!isWorkflow) delivery.deliver(run, id, output, progress);
            } else {
                progress.accept(VueDeliveryService.event("preview_check", "预览检查", "running", "正在检查生成页面", 0));
                BuildResult result = verifier.verify(run.root(), false, run);
                if (!result.success()) throw new BusinessException(ErrorCode.OPERATION_ERROR, result.diagnostic());
                progress.accept(VueDeliveryService.event("preview_check", "预览检查", "completed", "页面检查已通过", 0));
            }
            run.checkActive();
        } catch (RuntimeException error) { throw error; }
    }
    @Override public void recordResult(GenerationTask task) { history.record(task); }
    @Override public void afterPublish(GenerationTask task) {
        // 封面读取已验证的固定版本，失败和取消任务不会触发截图。
        if (screenshots != null) screenshots.captureAppCoverAsync(task.getAppId(),
                "http://localhost:" + serverPort + contextPath.replaceAll("/$", "") + "/static/v_" + task.getId()
                        + "_" + task.getCodeGenType() + "_" + task.getAppId() + "/");
    }
}
