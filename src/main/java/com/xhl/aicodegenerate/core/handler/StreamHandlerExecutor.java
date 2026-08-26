package com.xhl.aicodegenerate.core.handler;

import com.xhl.aicodegenerate.ai.tools.ToolManager;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.service.ChatHistoryService;
import reactor.core.publisher.Flux;

/**
 * 流处理器执行器。
 */
public class StreamHandlerExecutor {

    private static final StreamHandler SIMPLE_TEXT_STREAM_HANDLER = new SimpleTextStreamHandler();

    private StreamHandlerExecutor() {
    }

    /**
     * 测试使用
     * @param originFlux
     * @param codeGenType
     * @return
     */
    public static Flux<String> execute(Flux<String> originFlux, CodeGenTypeEnum codeGenType) {
        StreamHandler streamHandler = switch (codeGenType) {
            case HTML, MULTI_FILE -> SIMPLE_TEXT_STREAM_HANDLER;
            case VUE_PROJECT -> new JsonMessageStreamHandler();
        };
        return streamHandler.handle(originFlux);
    }

    /**
     * 执行流处理器
     * @param originFlux
     * @param codeGenType
     * @param appId
     * @param userId
     * @param chatHistoryService
     * @param toolManager
     * @return
     */
    public static Flux<String> execute(Flux<String> originFlux, CodeGenTypeEnum codeGenType,
                                       Long appId, Long userId, ChatHistoryService chatHistoryService,
                                       ToolManager toolManager) {
        StreamHandler streamHandler = switch (codeGenType) {
            case HTML, MULTI_FILE -> SIMPLE_TEXT_STREAM_HANDLER;
            case VUE_PROJECT -> new JsonMessageStreamHandler(chatHistoryService, appId, userId, toolManager);
        };
        return streamHandler.handle(originFlux);
    }
}
