package com.xhl.aicodegenerate.config;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "spring.data.redis")
@Data
public class RedisChatMemoryStoreConfig {

    private String host;

    private int port;

    private String password;

    /**
     * 对话记忆在 Redis 中的过期时间，单位秒。
     * 0 表示永不过期，避免后端重启后因 key 过期导致记忆丢失。
     */
    private long ttl = 0;

    @Bean
    public RedisChatMemoryStore redisChatMemoryStore() {
        RedisChatMemoryStore.Builder builder = RedisChatMemoryStore.builder()
                .host(host)
                .port(port)
                .ttl(ttl);
        if (StrUtil.isNotBlank(password)) {
            builder.password(password);
        }
        return builder.build();
    }
}
