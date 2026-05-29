package com.xhl.aicodegenerate.core.handler;

import reactor.core.publisher.Flux;

/**
 * 代码生成流处理器。
 */
public interface StreamHandler {

    /**
     * 处理生成流。
     *
     * @param originFlux 原始流
     * @return 处理后的流
     */
    Flux<String> handle(Flux<String> originFlux);
}
