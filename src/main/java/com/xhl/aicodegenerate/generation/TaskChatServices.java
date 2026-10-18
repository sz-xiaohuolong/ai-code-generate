package com.xhl.aicodegenerate.generation;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 按任务创建结构化服务，避免依赖线程局部变量穿过图节点。 */
@Component
public class TaskChatServices {
    @Resource(name = "openAiStreamingChatModel") private StreamingChatModel model;
    public <T> T create(Class<T> service, String taskId) {
        return AiServices.builder(service).chatModel(new TaskChatModel(model, GenerationExecution.require(taskId))).build();
    }
}
