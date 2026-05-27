package com.xhl.aicodegenerate.ai;

import com.xhl.aicodegenerate.ai.tools.FileWriteTool;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.mapper.ChatHistoryMapper;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
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

    /**
     * AI 对话记忆最多保留的消息数，和 DatabaseLoadingChatMemoryStore 从 DB 恢复的条数保持一致。
     */
    private static final int CHAT_MEMORY_MAX_MESSAGES = 20;

    /**
     * Vue 工程模式会让模型连续调用文件写入工具。
     * 同时用于 AiServices 同步工具调用上限，以及 FileWriteTool 在流式调用中的写文件次数上限。
     */
    private static final int VUE_PROJECT_MAX_FILE_WRITE_COUNT = 20;

    @Resource
    private ChatModel chatModel;

    @Resource
    private StreamingChatModel openAiStreamingChatModel;

    @Resource
    private StreamingChatModel reasoningStreamingChatModel;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryMapper chatHistoryMapper;

    @Bean
    @Primary
    public ChatMemoryStore chatMemoryStore() {
        // RedisChatMemoryStore 负责真正读写 Redis；
        // DatabaseLoadingChatMemoryStore 在外面包一层，用 DB 作为事实源做恢复和一致性保障。
        return new DatabaseLoadingChatMemoryStore(redisChatMemoryStore, chatHistoryMapper);
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore chatMemoryStore) {
        // LangChain4j 会根据 @MemoryId 获取 ChatMemory。
        // 这里每个 memoryId 对应一个 MessageWindowChatMemory，底层读写统一走 chatMemoryStore。
        return memoryId -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(CHAT_MEMORY_MAX_MESSAGES)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    /**
     * 根据代码生成类型创建 AI 服务实例。
     *
     * @param memoryId    应用级别对话记忆 id
     * @param codeGenType 代码生成类型
     * @return AI 服务实例
     */
    public AiCodeGeneratorService createAiCodeGeneratorService(AppChatMemoryId memoryId, CodeGenTypeEnum codeGenType) {
        // 每次创建服务时都使用同一套 ChatMemoryProvider。
        // 先触发一次 messages()，让 ChatMemoryStore 有机会从 DB 恢复历史到 Redis。
        ChatMemoryProvider currentChatMemoryProvider = chatMemoryProvider(chatMemoryStore());
        currentChatMemoryProvider.get(memoryId).messages();
        return switch (codeGenType) {
            case VUE_PROJECT -> AiServices.builder(AiCodeGeneratorService.class)
                    // Vue 工程生成需要工具调用和更强推理能力，所以使用单独配置的推理流式模型。
                    .streamingChatModel(reasoningStreamingChatModel)
                    .chatMemoryProvider(currentChatMemoryProvider)
                    // 允许模型通过工具调用直接写入 Vue 工程文件。
                    .tools(new FileWriteTool(VUE_PROJECT_MAX_FILE_WRITE_COUNT))
                    .maxSequentialToolsInvocations(VUE_PROJECT_MAX_FILE_WRITE_COUNT)
                    // 如果模型幻觉调用了不存在的工具，给模型一个明确的工具错误结果，而不是直接中断。
                    .hallucinatedToolNameStrategy(toolExecutionRequest -> ToolExecutionResultMessage.from(
                            toolExecutionRequest, "Error: there is no tool called " + toolExecutionRequest.name()
                    ))
                    .build();
            case HTML, MULTI_FILE -> AiServices.builder(AiCodeGeneratorService.class)
                    // HTML / 多文件模式仍使用默认模型，输出文本后由后续 parser/saver 解析和保存。
                    .chatModel(chatModel)
                    .streamingChatModel(openAiStreamingChatModel)
                    .chatMemoryProvider(currentChatMemoryProvider)
                    .build();
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "不支持的代码生成类型: " + codeGenType.getValue());
        };
    }

    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService(ChatMemoryProvider chatMemoryProvider) {
        // 保留一个默认 AI Service Bean，兼容测试或其他直接注入 AiCodeGeneratorService 的旧代码。
        // 实际按 App 类型生成代码时，应优先走 createAiCodeGeneratorService(...)。
        return AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(openAiStreamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }


}
