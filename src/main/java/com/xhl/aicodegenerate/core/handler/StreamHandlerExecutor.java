package com.xhl.aicodegenerate.core.handler;

import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.service.ChatHistoryService;
import reactor.core.publisher.Flux;

/**
 * 流处理器执行器。
 */
public class StreamHandlerExecutor {

    private static final StreamHandler SIMPLE_TEXT_STREAM_HANDLER = new SimpleTextStreamHandler();

    private static final StreamHandler JSON_MESSAGE_STREAM_HANDLER = new JsonMessageStreamHandler();

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
            case VUE_PROJECT -> JSON_MESSAGE_STREAM_HANDLER;
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
     * @return
     */
    public static Flux<String> execute(Flux<String> originFlux, CodeGenTypeEnum codeGenType,
                                       Long appId, Long userId, ChatHistoryService chatHistoryService) {
        StreamHandler streamHandler = switch (codeGenType) {
            case HTML, MULTI_FILE -> SIMPLE_TEXT_STREAM_HANDLER;
            case VUE_PROJECT -> new JsonMessageStreamHandler(chatHistoryService, appId, userId);
        };
        return streamHandler.handle(originFlux);
    }
}
