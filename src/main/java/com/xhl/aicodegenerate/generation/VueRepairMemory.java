package com.xhl.aicodegenerate.generation;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.ArrayList;
import java.util.List;

/** 修复上下文作为用户消息固定保留，工具历史继续使用有限窗口。 */
final class VueRepairMemory implements ChatMemory {
    private final ChatMemory window = MessageWindowChatMemory.withMaxMessages(20);
    private UserMessage context;

    VueRepairMemory(String context) { this.context = UserMessage.from(context); }

    @Override public Object id() { return window.id(); }
    @Override public synchronized void add(ChatMessage message) { window.add(message); }
    @Override public synchronized List<ChatMessage> messages() {
        var messages = new ArrayList<>(window.messages());
        if (context != null) messages.add(!messages.isEmpty() && messages.getFirst() instanceof SystemMessage ? 1 : 0, context);
        return messages;
    }
    @Override public synchronized void clear() { window.clear(); context = null; }
}
