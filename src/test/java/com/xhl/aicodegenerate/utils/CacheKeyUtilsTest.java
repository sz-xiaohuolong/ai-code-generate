package com.xhl.aicodegenerate.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhl.aicodegenerate.model.dto.app.AppQueryRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyUtilsTest {

    private final CacheKeyUtils cacheKeyUtils = new CacheKeyUtils(new ObjectMapper().findAndRegisterModules());

    @Test
    void shouldGenerateStableFixedLengthSha256Key() {
        AppQueryRequest first = query(1, "博客");
        AppQueryRequest second = query(1, "博客");

        String firstKey = cacheKeyUtils.generateKey(first);
        String secondKey = cacheKeyUtils.generateKey(second);

        assertThat(firstKey).isEqualTo(secondKey).matches("[0-9a-f]{64}");
    }

    @Test
    void shouldGenerateDifferentKeysForDifferentQueries() {
        assertThat(cacheKeyUtils.generateKey(query(1, "博客")))
                .isNotEqualTo(cacheKeyUtils.generateKey(query(2, "博客")))
                .isNotEqualTo(cacheKeyUtils.generateKey(query(1, "商城")));
    }

    private AppQueryRequest query(int pageNum, String appName) {
        AppQueryRequest request = new AppQueryRequest();
        request.setPageNum(pageNum);
        request.setPageSize(6);
        request.setSortField("createTime");
        request.setSortOrder("descend");
        request.setAppName(appName);
        return request;
    }
}
