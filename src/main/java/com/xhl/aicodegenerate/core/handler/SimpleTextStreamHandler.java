package com.xhl.aicodegenerate.core.handler;

import reactor.core.publisher.Flux;

/**
 * 原生文本流处理器。
 */
public class SimpleTextStreamHandler implements StreamHandler {

    @Override
    public Flux<String> handle(Flux<String> originFlux) {
        return originFlux;
    }
}
