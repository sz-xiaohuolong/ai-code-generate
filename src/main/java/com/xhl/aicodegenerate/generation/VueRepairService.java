package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.core.handler.JsonMessageStreamHandler;
import com.xhl.aicodegenerate.ai.tools.ToolManager;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.util.function.Consumer;

/** 修复使用临时记忆，仅将真实诊断交给文件工具定位与修改。 */
@Service
public class VueRepairService implements VueRepairer {
    private final AiCodeGeneratorFacade facade;
    private final ToolManager tools;
    public VueRepairService(@Lazy AiCodeGeneratorFacade facade, ToolManager tools) { this.facade = facade; this.tools = tools; }
    @Override public void repair(AppChatMemoryId id, String diagnostic, Consumer<String> output) {
        GenerationExecution run = GenerationExecution.require(id.getTaskId());
        var memory = MessageWindowChatMemory.withMaxMessages(20);
        String prompt = "当前 Vue 工程交付检查失败。仅修复下面诊断涉及的问题。先读取目录和相关文件，优先用 modifyFile 做唯一匹配的局部修复；"
                + "必要时修正依赖声明。保留用户需求和已有功能，不要全量重写，不要自行运行安装或部署。文件路径使用项目内相对路径。\n\n" + diagnostic;
        var stream = facade.generateAndSaveCodeStreamForWorkflow(prompt, CodeGenTypeEnum.VUE_PROJECT, id, ignored -> memory);
        new JsonMessageStreamHandler(null, null, null, tools).handle(stream)
                .doOnSubscribe(subscription -> run.onCancel(subscription::cancel)).doOnNext(output).blockLast(run.remaining());
    }
}
