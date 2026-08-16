package com.xhl.aicodegenerate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.paginate.Page;
import com.xhl.aicodegenerate.common.BaseResponse;
import com.xhl.aicodegenerate.model.vo.AppVO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCacheConfigTest {

    private final RedisCacheConfig config = new RedisCacheConfig();

    @Test
    void shouldConfigureTenMinuteTtlPrefixAndNoNullValues() {
        RedisCacheConfiguration cacheConfiguration = config.redisCacheConfiguration(
                new ObjectMapper().findAndRegisterModules());

        assertThat(cacheConfiguration.getTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(cacheConfiguration.getAllowCacheNullValues()).isFalse();
        assertThat(cacheConfiguration.getKeyPrefixFor("featuredApp"))
                .isEqualTo("zero-code:cache:featuredApp::");
    }

    @Test
    void shouldRoundTripFeaturedPageAsJson() {
        RedisCacheConfiguration cacheConfiguration = config.redisCacheConfiguration(
                new ObjectMapper().findAndRegisterModules());
        AppVO app = new AppVO();
        app.setId(1L);
        app.setAppName("缓存测试");
        app.setCreateTime(LocalDateTime.of(2026, 8, 16, 12, 0));
        Page<AppVO> page = new Page<>(List.of(app), 1, 6, 1);
        BaseResponse<Page<AppVO>> response = new BaseResponse<>(0, page);

        ByteBuffer bytes = cacheConfiguration.getValueSerializationPair().write(response);
        Object restored = cacheConfiguration.getValueSerializationPair().read(bytes);

        assertThat(restored).isInstanceOf(BaseResponse.class);
        BaseResponse<?> restoredResponse = (BaseResponse<?>) restored;
        assertThat(restoredResponse.getData()).isInstanceOf(Page.class);
    }
}
