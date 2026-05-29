package com.xhl.aicodegenerate.ai;

import com.xhl.aicodegenerate.mapper.ChatHistoryMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DatabaseLoadingChatMemoryStoreTest {

    @Test
    void getMessagesShouldKeepRedisMessagesWhenSystemMessageExists() {
        ChatHistoryMapper chatHistoryMapper = mock(ChatHistoryMapper.class);
        List<ChatMessage> redisMessages = List.of(
                SystemMessage.from("system prompt"),
                UserMessage.from("生成 Vue 项目")
        );
        InMemoryChatMemoryStore delegate = new InMemoryChatMemoryStore(redisMessages);
        DatabaseLoadingChatMemoryStore store = new DatabaseLoadingChatMemoryStore(delegate, chatHistoryMapper);

        List<ChatMessage> messages = store.getMessages(new AppChatMemoryId(1L, 1L));

        assertEquals(redisMessages, messages);
        verifyNoInteractions(chatHistoryMapper);
    }

    @Test
    void getMessagesShouldKeepRedisMessagesWhenToolExecutionContextExists() {
        ChatHistoryMapper chatHistoryMapper = mock(ChatHistoryMapper.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_1")
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"index.html\",\"content\":\"<div></div>\"}")
                .build();
        List<ChatMessage> redisMessages = List.of(
                UserMessage.from("生成 Vue 项目"),
                AiMessage.from("开始写文件", List.of(request)),
                ToolExecutionResultMessage.from(request, "文件写入成功: index.html")
        );
        InMemoryChatMemoryStore delegate = new InMemoryChatMemoryStore(redisMessages);
        DatabaseLoadingChatMemoryStore store = new DatabaseLoadingChatMemoryStore(delegate, chatHistoryMapper);

        List<ChatMessage> messages = store.getMessages(new AppChatMemoryId(1L, 1L));

        assertEquals(redisMessages, messages);
        verifyNoInteractions(chatHistoryMapper);
    }

    @Test
    void getMessagesShouldRemoveOrphanToolResultMessages() {
        ChatHistoryMapper chatHistoryMapper = mock(ChatHistoryMapper.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_1")
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"index.html\",\"content\":\"<div></div>\"}")
                .build();
        List<ChatMessage> redisMessages = List.of(
                UserMessage.from("生成 Vue 项目"),
                ToolExecutionResultMessage.from(request, "文件写入成功: index.html")
        );
        InMemoryChatMemoryStore delegate = new InMemoryChatMemoryStore(redisMessages);
        DatabaseLoadingChatMemoryStore store = new DatabaseLoadingChatMemoryStore(delegate, chatHistoryMapper);

        List<ChatMessage> messages = store.getMessages(new Object());

        assertEquals(List.of(UserMessage.from("生成 Vue 项目")), messages);
        assertEquals(messages, delegate.getMessages(new Object()));
        verifyNoInteractions(chatHistoryMapper);
    }

    private static class InMemoryChatMemoryStore implements ChatMemoryStore {

        private List<ChatMessage> messages;

        private InMemoryChatMemoryStore(List<ChatMessage> messages) {
            this.messages = new ArrayList<>(messages);
        }

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return messages;
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            this.messages = new ArrayList<>(messages);
        }

        @Override
        public void deleteMessages(Object memoryId) {
            this.messages = new ArrayList<>();
        }
    }
}
