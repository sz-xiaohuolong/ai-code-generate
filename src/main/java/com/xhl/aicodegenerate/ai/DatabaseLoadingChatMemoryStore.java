package com.xhl.aicodegenerate.ai;

import cn.hutool.core.collection.CollUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.xhl.aicodegenerate.entity.ChatHistory;
import com.xhl.aicodegenerate.mapper.ChatHistoryMapper;
import com.xhl.aicodegenerate.model.enums.ChatHistoryMessageTypeEnum;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 对话记忆存储的数据库兜底包装。
 * Redis 中没有某个 appId 的记忆时，从 chat_history 表恢复最近一段历史。
 */
public class DatabaseLoadingChatMemoryStore implements ChatMemoryStore {

    private static final int MAX_MESSAGES = 20;

    private final ChatMemoryStore delegate;

    private final ChatHistoryMapper chatHistoryMapper;

    private final Set<Object> emptyInitializedMemoryIds = ConcurrentHashMap.newKeySet();

    public DatabaseLoadingChatMemoryStore(ChatMemoryStore delegate, ChatHistoryMapper chatHistoryMapper) {
        this.delegate = delegate;
        this.chatHistoryMapper = chatHistoryMapper;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<ChatMessage> messages = delegate.getMessages(memoryId);
        if (CollUtil.isNotEmpty(messages)) {
            emptyInitializedMemoryIds.remove(memoryId);
            return messages;
        }
        if (emptyInitializedMemoryIds.contains(memoryId)) {
            return messages;
        }
        Long appId = parseAppId(memoryId);
        if (appId == null) {
            return messages;
        }
        List<ChatMessage> historyMessages = loadMessagesFromDatabase(appId);
        if (CollUtil.isNotEmpty(historyMessages)) {
            delegate.updateMessages(memoryId, historyMessages);
            emptyInitializedMemoryIds.remove(memoryId);
        } else {
            emptyInitializedMemoryIds.add(memoryId);
        }
        return historyMessages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        emptyInitializedMemoryIds.remove(memoryId);
        delegate.updateMessages(memoryId, messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        emptyInitializedMemoryIds.remove(memoryId);
        delegate.deleteMessages(memoryId);
    }

    private List<ChatMessage> loadMessagesFromDatabase(Long appId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", appId)
                .orderBy("createTime", false)
                .orderBy("id", false)
                .limit(MAX_MESSAGES);
        List<ChatHistory> chatHistoryList = chatHistoryMapper.selectListByQuery(queryWrapper);
        if (CollUtil.isEmpty(chatHistoryList)) {
            return new ArrayList<>();
        }
        Collections.reverse(chatHistoryList);
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatHistory chatHistory : chatHistoryList) {
            ChatMessage chatMessage = toChatMessage(chatHistory);
            if (chatMessage != null) {
                messages.add(chatMessage);
            }
        }
        return messages;
    }

    private ChatMessage toChatMessage(ChatHistory chatHistory) {
        String messageType = chatHistory.getMessageType();
        String message = chatHistory.getMessage();
        if (ChatHistoryMessageTypeEnum.USER.getValue().equals(messageType)) {
            return UserMessage.from(message);
        }
        if (ChatHistoryMessageTypeEnum.AI.getValue().equals(messageType)) {
            return AiMessage.from(message);
        }
        return null;
    }

    private Long parseAppId(Object memoryId) {
        if (memoryId instanceof Number number) {
            return number.longValue();
        }
        if (memoryId instanceof String string) {
            try {
                return Long.valueOf(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
