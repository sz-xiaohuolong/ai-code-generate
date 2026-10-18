package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.entity.ChatHistory;
import com.xhl.aicodegenerate.mapper.ChatHistoryMapper;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.Objects;

/** 两条正式历史在同一事务补记；稳定主键使 Redis 待记记录可安全重试。 */
@Service
public class TaskHistoryRecorder {
    private final ChatHistoryMapper mapper;
    private final RedisChatMemoryStore memory;
    public TaskHistoryRecorder(ChatHistoryMapper mapper, RedisChatMemoryStore memory) { this.mapper=mapper;this.memory=memory; }
    @Transactional public void record(GenerationTask task) {
        String result = task.getStatus()==TaskStatus.SUCCEEDED
                ? task.getOutput()+("workflow".equals(task.getGenerationMode())?"\n\n工作流已完成，生成结果可预览。":"\n\n生成已完成，交付检查通过。")
                : "生成未完成："+task.getMessage();
        insert(task,task.getUserMessageId(),"user",task.getPrompt(),task.getCreatedAt());
        insert(task,task.getAiMessageId(),"ai",result,task.getUpdatedAt());
        memory.deleteMessages(new AppChatMemoryId(task.getAppId(),task.getUserId()));
    }
    private void insert(GenerationTask task,Long id,String type,String text,long at) {
        if(id==null) throw new IllegalStateException("任务历史主键缺失");
        var message=ChatHistory.builder().id(id).appId(task.getAppId()).userId(task.getUserId()).messageType(type).message(text)
                .createTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(at),ZoneId.systemDefault())).build();
        mapper.insertTaskMessage(message);
        ChatHistory saved=mapper.selectOneById(id);
        if(saved==null || !Objects.equals(saved.getAppId(),task.getAppId()) || !Objects.equals(saved.getUserId(),task.getUserId())
                || !Objects.equals(saved.getMessageType(),type) || !Objects.equals(saved.getMessage(),text))
            throw new IllegalStateException("正式历史主键冲突或写入未完成");
    }
}
