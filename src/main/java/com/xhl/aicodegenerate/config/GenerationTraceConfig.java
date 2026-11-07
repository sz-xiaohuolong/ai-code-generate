package com.xhl.aicodegenerate.config;

import com.xhl.aicodegenerate.observability.GenerationTraceRecorder;
import com.xhl.aicodegenerate.observability.TraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 独立记录器不注册全局 OTel，不启用第三方导出器。 */
@Configuration
public class GenerationTraceConfig {
    @Bean
    public GenerationTraceRecorder generationTraceRecorder(TraceStore store,
            @Value("${generation.trace.queue-capacity:2048}") int capacity,
            @Value("${generation.trace.enabled:true}") boolean enabled) {
        if (capacity < 1 || capacity > 65536) {
            throw new IllegalArgumentException("追踪队列容量必须在 1～65536 之间");
        }
        return new GenerationTraceRecorder(store, Clock.systemUTC(), capacity, enabled);
    }
}
