package com.xhl.aicodegenerate.ai;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.xhl.aicodegenerate.entity.ChatHistory;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.mapper.ChatHistoryMapper;
import com.xhl.aicodegenerate.model.enums.ChatHistoryMessageTypeEnum;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话记忆存储。
 * 读取时优先 Redis，Redis 缺失时从 chat_history 表恢复；写入时先落库，成功后再写 Redis。
 *
 * <p>这里把 DB 作为最终事实源，Redis 只作为 LangChain4j ChatMemory 的快速缓存。
 * 这样即使 Redis 重启或过期，也可以从 chat_history 重新恢复上下文。</p>
 */
public class DatabaseLoadingChatMemoryStore implements ChatMemoryStore {

    private static final int MAX_MESSAGES = 20;

    private final ChatMemoryStore delegate;

    private final ChatHistoryMapper chatHistoryMapper;

    public DatabaseLoadingChatMemoryStore(ChatMemoryStore delegate, ChatHistoryMapper chatHistoryMapper) {
        this.delegate = delegate;
        this.chatHistoryMapper = chatHistoryMapper;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        // LangChain4j 每次构造 prompt 前都会读取 ChatMemory；先取 Redis 缓存，降低 DB 压力。
        List<ChatMessage> redisMessages = delegate.getMessages(memoryId);
        Long appId = parseAppId(memoryId);
        if (appId == null) {
            return redisMessages;
        }
        // 再取 DB 最近的历史。DB 是事实源，用于恢复 Redis，也用于纠正 Redis 中的旧数据。
        List<ChatMessage> historyMessages = loadMessagesFromDatabase(appId);
        if (CollUtil.isNotEmpty(historyMessages)) {
            // Redis 为空、过期，或者与 DB 不一致时，用 DB 刷新 Redis，避免使用旧上下文。
            if (!samePersistableMessages(redisMessages, historyMessages)) {
                delegate.updateMessages(memoryId, historyMessages);
            }
            return historyMessages;
        }
        // DB 已没有历史但 Redis 还有值，说明可能删除过应用或历史，清掉 Redis 防止旧对话复活。
        if (CollUtil.isNotEmpty(redisMessages)) {
            delegate.deleteMessages(memoryId);
        }
        return new ArrayList<>();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // LangChain4j 会把完整窗口传进来，而不是只传新增消息，所以需要先读取旧窗口做 diff。
        List<ChatMessage> oldMessages = delegate.getMessages(memoryId);
        // 先写 DB，DB 成功后才写 Redis，避免出现 Redis 有消息但 DB 缺消息的恢复断层。
        saveNewMessagesToDatabase(memoryId, oldMessages, messages);
        delegate.updateMessages(memoryId, messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        delegate.deleteMessages(memoryId);
    }

    private List<ChatMessage> loadMessagesFromDatabase(Long appId) {
        // 与 MessageWindowChatMemory 的窗口大小保持一致，只恢复最近一段上下文。
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", appId)
                .orderBy("createTime", false)
                .orderBy("id", false)
                .limit(MAX_MESSAGES);
        List<ChatHistory> chatHistoryList = chatHistoryMapper.selectListByQuery(queryWrapper);
        if (CollUtil.isEmpty(chatHistoryList)) {
            return new ArrayList<>();
        }
        // SQL 为倒序取最新 N 条，传给模型时需要恢复成正常聊天顺序：旧消息在前，新消息在后。
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

    private void saveNewMessagesToDatabase(Object memoryId, List<ChatMessage> oldMessages, List<ChatMessage> newMessages) {
        Long appId = parseAppId(memoryId);
        Long userId = parseUserId(memoryId);
        if (appId == null || userId == null) {
            return;
        }
        // 只比较 user / ai 消息；SystemMessage 不写业务历史表，避免污染前端聊天记录。
        List<PersistableMessage> oldPersistableMessages = toPersistableMessages(oldMessages);
        List<PersistableMessage> newPersistableMessages = toPersistableMessages(newMessages);
        // 找出旧窗口尾部与新窗口头部的最大重叠段，重叠之后的部分就是 LangChain4j 新增的消息。
        int overlapSize = findOverlapSize(oldPersistableMessages, newPersistableMessages);
        for (int i = overlapSize; i < newPersistableMessages.size(); i++) {
            PersistableMessage message = newPersistableMessages.get(i);
            insertChatHistory(appId, userId, message);
        }
    }

    private List<PersistableMessage> toPersistableMessages(List<ChatMessage> messages) {
        if (CollUtil.isEmpty(messages)) {
            return new ArrayList<>();
        }
        List<PersistableMessage> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            PersistableMessage persistableMessage = toPersistableMessage(message);
            if (persistableMessage != null) {
                result.add(persistableMessage);
            }
        }
        return result;
    }

    private PersistableMessage toPersistableMessage(ChatMessage chatMessage) {
        if (chatMessage instanceof UserMessage userMessage) {
            String message = userMessage.singleText();
            if (StrUtil.isBlank(message)) {
                return null;
            }
            return new PersistableMessage(ChatHistoryMessageTypeEnum.USER.getValue(), message);
        }
        if (chatMessage instanceof AiMessage aiMessage) {
            String message = aiMessage.text();
            if (StrUtil.isBlank(message)) {
                return null;
            }
            return new PersistableMessage(ChatHistoryMessageTypeEnum.AI.getValue(), message);
        }
        return null;
    }

    private int findOverlapSize(List<PersistableMessage> oldMessages, List<PersistableMessage> newMessages) {
        int maxOverlapSize = Math.min(oldMessages.size(), newMessages.size());
        // 从最大可能重叠开始尝试，兼容 MessageWindowChatMemory 淘汰旧消息后的滑动窗口。
        for (int overlapSize = maxOverlapSize; overlapSize > 0; overlapSize--) {
            List<PersistableMessage> oldSuffix = oldMessages.subList(oldMessages.size() - overlapSize, oldMessages.size());
            List<PersistableMessage> newPrefix = newMessages.subList(0, overlapSize);
            if (oldSuffix.equals(newPrefix)) {
                return overlapSize;
            }
        }
        return 0;
    }

    private boolean samePersistableMessages(List<ChatMessage> oldMessages, List<ChatMessage> newMessages) {
        return toPersistableMessages(oldMessages).equals(toPersistableMessages(newMessages));
    }

    private void insertChatHistory(Long appId, Long userId, PersistableMessage message) {
        // 这里直接使用 Mapper，保证 ChatMemoryStore 是统一写入点，避免 AppService 和 Redis 双写分散。
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setAppId(appId);
        chatHistory.setUserId(userId);
        chatHistory.setMessage(message.message());
        chatHistory.setMessageType(message.messageType());
        int result = chatHistoryMapper.insert(chatHistory);
        if (result <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存对话历史失败");
        }
    }

    private Long parseAppId(Object memoryId) {
        // 业务调用使用 AppChatMemoryId；保留 Number/String 兼容，便于历史调用或测试场景读取。
        if (memoryId instanceof AppChatMemoryId appChatMemoryId) {
            return appChatMemoryId.getAppId();
        }
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

    private Long parseUserId(Object memoryId) {
        // DB 需要记录操作者 userId；Redis key 仍由 AppChatMemoryId.toString() 保持为 appId。
        if (memoryId instanceof AppChatMemoryId appChatMemoryId) {
            return appChatMemoryId.getUserId();
        }
        return null;
    }

    private record PersistableMessage(String messageType, String message) {
    }
}
