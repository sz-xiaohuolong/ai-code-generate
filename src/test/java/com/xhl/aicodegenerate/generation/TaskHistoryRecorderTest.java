package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.entity.ChatHistory;
import com.xhl.aicodegenerate.mapper.ChatHistoryMapper;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskHistoryRecorderTest {
    @Test void partialHistoryRetryReusesStablePrimaryKeysAndDoesNotDuplicateMessages() {
        var mapper=mock(ChatHistoryMapper.class);var memory=mock(RedisChatMemoryStore.class);
        Map<Long,ChatHistory> rows=new HashMap<>();var failed=new AtomicBoolean();
        when(mapper.insertTaskMessage(any())).thenAnswer(invocation->{
            ChatHistory message=invocation.getArgument(0);
            if(message.getMessageType().equals("ai")&&failed.compareAndSet(false,true))throw new IllegalStateException("数据库暂不可用");
            rows.putIfAbsent(message.getId(),message);return 1;
        });
        when(mapper.selectOneById(any())).thenAnswer(invocation->rows.get(invocation.getArgument(0)));
        var task=new GenerationTask();task.setId(UUID.randomUUID().toString());task.setAppId(1L);task.setUserId(2L);
        task.setUserMessageId(10L);task.setAiMessageId(11L);task.setPrompt("原始输入");task.setOutput("最终结果");task.setStatus(TaskStatus.SUCCEEDED);
        var recorder=new TaskHistoryRecorder(mapper,memory);
        assertThrows(IllegalStateException.class,()->recorder.record(task));
        recorder.record(task);recorder.record(task);
        assertEquals(2,rows.size());assertEquals("原始输入",rows.get(10L).getMessage());
        assertTrue(rows.get(11L).getMessage().contains("交付检查通过"));
    }
}
