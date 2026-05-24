package com.xhl.aicodegenerate.ai;

import com.xhl.aicodegenerate.mapper.ChatHistoryMapper;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * AI 代码生成服务工厂。
 * <p>
 * 该配置类负责把 LangChain4j 提供的 {@link ChatModel} 和
 * {@link StreamingChatModel} 组装成 {@link AiCodeGeneratorService} 的代理实现，
 * 并将该代理对象注册为 Spring Bean。
 * </p>
 * <p>
 * {@link AiCodeGeneratorService} 本身只是一个声明式 AI 调用接口，不需要手写实现类。
 * LangChain4j 会根据接口方法、返回值类型以及方法上的提示词注解，在运行时创建动态代理；
 * 调用接口方法时，代理对象会负责拼接提示词、调用对应的模型，并把模型响应转换为方法返回值。
 * </p>
 */
@Configuration
public class AiCodeGeneratorServiceFactory {

    private static final int CHAT_MEMORY_MAX_MESSAGES = 20;

    @Resource
    private ChatModel chatModel;

    @Resource
    private StreamingChatModel streamingChatModel;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryMapper chatHistoryMapper;

    @Bean
    @Primary
    public ChatMemoryStore chatMemoryStore() {
        return new DatabaseLoadingChatMemoryStore(redisChatMemoryStore, chatHistoryMapper);
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore chatMemoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(CHAT_MEMORY_MAX_MESSAGES)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService(ChatMemoryProvider chatMemoryProvider) {
        return AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }


}
