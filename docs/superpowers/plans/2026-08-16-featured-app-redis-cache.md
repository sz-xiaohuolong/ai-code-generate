# Featured App Redis Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cache the first ten pages of `POST /app/list/page/featured` in Redis for ten minutes with deterministic SHA-256 query keys and no write-side eviction.

**Architecture:** Spring Cache intercepts the featured-app Controller method. It hashes the complete `AppQueryRequest`, reads/writes Redis through a JSON-configured `RedisCacheManager`, and bypasses caching outside page numbers 1–10. A cache hit returns before the Controller body, while a miss preserves the existing login check and database query.

**Tech Stack:** Java 21, Spring Boot 3.5.13, Spring Cache, Spring Data Redis 3.5.10, Jackson 2.21.2, JUnit 5, Mockito, AssertJ.

## Global Constraints

- Cache TTL is exactly 10 minutes.
- Only page numbers 1 through 10 are cacheable.
- `@Cacheable` is applied directly to `AppController.listFeaturedAppVOByPage`.
- Anonymous callers may read an existing cache hit; an anonymous cache miss still reaches the current login check and fails.
- Cache keys are fixed-length SHA-256 hashes of the complete query object.
- No application create, update, delete, or admin endpoint actively evicts this cache.
- Existing Spring Session and AI ChatMemory Redis keys must remain unaffected.

---

### Task 1: Deterministic cache-key utility

**Files:**
- Create: `src/test/java/com/xhl/aicodegenerate/utils/CacheKeyUtilsTest.java`
- Create: `src/main/java/com/xhl/aicodegenerate/utils/CacheKeyUtils.java`

**Interfaces:**
- Consumes: the existing application `ObjectMapper` bean and any query object.
- Produces: Spring bean `cacheKeyUtils` with `String generateKey(Object source)`.

- [ ] **Step 1: Write the failing cache-key tests**

```java
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
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./mvnw -Dtest=CacheKeyUtilsTest test`

Expected: test compilation fails because `CacheKeyUtils` does not exist.

- [ ] **Step 3: Implement the minimal utility**

```java
package com.xhl.aicodegenerate.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component("cacheKeyUtils")
public class CacheKeyUtils {

    private final ObjectMapper objectMapper;

    public CacheKeyUtils(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String generateKey(Object source) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(source);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("生成缓存键失败", e);
        }
    }
}
```

- [ ] **Step 4: Run the test and verify GREEN**

Run: `./mvnw -Dtest=CacheKeyUtilsTest test`

Expected: 2 tests pass.

- [ ] **Step 5: Commit the utility**

```bash
git add src/main/java/com/xhl/aicodegenerate/utils/CacheKeyUtils.java \
  src/test/java/com/xhl/aicodegenerate/utils/CacheKeyUtilsTest.java
git commit -m "feat: add deterministic cache key utility"
```

---

### Task 2: Redis CacheManager and Spring Cache activation

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/xhl/aicodegenerate/AiCodeGenerateApplication.java`
- Create: `src/test/java/com/xhl/aicodegenerate/config/RedisCacheConfigTest.java`
- Create: `src/main/java/com/xhl/aicodegenerate/config/RedisCacheConfig.java`

**Interfaces:**
- Consumes: Boot-provided `RedisConnectionFactory` and the existing `ObjectMapper` bean.
- Produces: `RedisCacheConfiguration redisCacheConfiguration(ObjectMapper)` and `CacheManager cacheManager(RedisConnectionFactory, RedisCacheConfiguration)`.

- [ ] **Step 1: Write the failing Redis cache configuration tests**

```java
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
```

- [ ] **Step 2: Run the configuration test and verify RED**

Run: `./mvnw -Dtest=RedisCacheConfigTest test`

Expected: test compilation fails because `RedisCacheConfig` does not exist.

- [ ] **Step 3: Add the Spring Cache starter dependency**

Add inside `<dependencies>` in `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

- [ ] **Step 4: Implement Redis cache configuration**

Create `RedisCacheConfig` with these exact settings:

```java
package com.xhl.aicodegenerate.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisCacheConfig {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);
    static final String CACHE_KEY_PREFIX = "zero-code:cache:";

    @Bean
    public RedisCacheConfiguration redisCacheConfiguration(ObjectMapper objectMapper) {
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.xhl.aicodegenerate")
                .allowIfSubType("com.mybatisflex.core.paginate")
                .allowIfSubType("java.util")
                .build();
        ObjectMapper redisObjectMapper = objectMapper.copy();
        redisObjectMapper.activateDefaultTyping(
                validator, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(CACHE_TTL)
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> CACHE_KEY_PREFIX + cacheName + "::")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(StringRedisSerializer.UTF_8))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer));
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                     RedisCacheConfiguration redisCacheConfiguration) {
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(redisCacheConfiguration)
                .build();
    }
}
```

- [ ] **Step 5: Enable caching in the application**

Add the import and annotation to `AiCodeGenerateApplication`:

```java
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@EnableAspectJAutoProxy(exposeProxy = true)
```

- [ ] **Step 6: Run the configuration tests and verify GREEN**

Run: `./mvnw -Dtest=RedisCacheConfigTest test`

Expected: 2 tests pass and the serializer round-trip restores `BaseResponse` and `Page` types.

- [ ] **Step 7: Compile the application**

Run: `./mvnw -DskipTests compile`

Expected: build succeeds with the new cache starter and configuration.

- [ ] **Step 8: Commit cache infrastructure**

```bash
git add pom.xml \
  src/main/java/com/xhl/aicodegenerate/AiCodeGenerateApplication.java \
  src/main/java/com/xhl/aicodegenerate/config/RedisCacheConfig.java \
  src/test/java/com/xhl/aicodegenerate/config/RedisCacheConfigTest.java
git commit -m "feat: configure redis cache manager"
```

---

### Task 3: Cache the first ten featured-app pages

**Files:**
- Create: `src/test/java/com/xhl/aicodegenerate/controller/AppControllerCacheTest.java`
- Modify: `src/main/java/com/xhl/aicodegenerate/controller/AppController.java:149-167`

**Interfaces:**
- Consumes: Spring bean `cacheKeyUtils`, cache name `featuredApp`, and existing Controller dependencies.
- Produces: cached behavior on `listFeaturedAppVOByPage(AppQueryRequest, HttpServletRequest)`.

- [ ] **Step 1: Write focused Spring AOP cache tests**

Create this focused Spring AOP test. It uses the real cache-key utility and Spring cache interceptor, while replacing Redis with an in-memory `CacheManager` so it can assert Controller boundary behavior deterministically:

```java
package com.xhl.aicodegenerate.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.xhl.aicodegenerate.common.BaseResponse;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.model.dto.app.AppQueryRequest;
import com.xhl.aicodegenerate.model.vo.AppVO;
import com.xhl.aicodegenerate.service.AppService;
import com.xhl.aicodegenerate.service.UserService;
import com.xhl.aicodegenerate.utils.CacheKeyUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(AppControllerCacheTest.TestConfig.class)
class AppControllerCacheTest {

    @Autowired
    private AppController controller;

    @Autowired
    private AppService appService;

    @Autowired
    private UserService userService;

    @Autowired
    private CacheManager cacheManager;

    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        cacheManager.getCache("featuredApp").clear();
        reset(appService, userService);
        request = mock(HttpServletRequest.class);
        User user = new User();
        user.setId(1L);
        when(userService.getLoginUser(request)).thenReturn(user);
        when(appService.getQueryWrapper(any(AppQueryRequest.class))).thenReturn(QueryWrapper.create());
        Page<App> databasePage = new Page<>(1, 6, 0);
        databasePage.setRecords(List.of());
        when(appService.page(any(Page.class), any(QueryWrapper.class))).thenReturn(databasePage);
        when(appService.getAppVOList(any())).thenReturn(List.of());
    }

    @Test
    void shouldCacheEqualFeaturedQueriesWithinFirstTenPages() {
        controller.listFeaturedAppVOByPage(query(1), request);
        controller.listFeaturedAppVOByPage(query(1), request);

        verify(appService, times(1)).page(any(Page.class), any(QueryWrapper.class));
        verify(userService, times(1)).getLoginUser(request);
    }

    @Test
    void shouldBypassCacheAfterPageTen() {
        controller.listFeaturedAppVOByPage(query(11), request);
        controller.listFeaturedAppVOByPage(query(11), request);

        verify(appService, times(2)).page(any(Page.class), any(QueryWrapper.class));
        verify(userService, times(2)).getLoginUser(request);
    }

    @Test
    void shouldReturnWarmCacheWithoutEnteringControllerBody() {
        BaseResponse<Page<AppVO>> warmResponse = controller.listFeaturedAppVOByPage(query(1), request);
        reset(userService, appService);
        when(userService.getLoginUser(request)).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        BaseResponse<Page<AppVO>> cachedResponse = controller.listFeaturedAppVOByPage(query(1), request);

        assertThat(cachedResponse).usingRecursiveComparison().isEqualTo(warmResponse);
        verifyNoInteractions(userService, appService);
    }

    private AppQueryRequest query(int pageNum) {
        AppQueryRequest query = new AppQueryRequest();
        query.setPageNum(pageNum);
        query.setPageSize(6);
        query.setSortField("createTime");
        query.setSortOrder("descend");
        query.setAppName("");
        return query;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        CacheKeyUtils cacheKeyUtils(ObjectMapper objectMapper) {
            return new CacheKeyUtils(objectMapper);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("featuredApp");
        }

        @Bean
        AppService appService() {
            return mock(AppService.class);
        }

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        AppController appController() {
            return new AppController();
        }
    }
}
```

- [ ] **Step 2: Run the Controller cache test and verify RED**

Run: `./mvnw -Dtest=AppControllerCacheTest test`

Expected: the first-page database invocation count is 2 because `@Cacheable` is not present.

- [ ] **Step 3: Add the cache annotation**

Add the import and annotation directly above the featured endpoint:

```java
import org.springframework.cache.annotation.Cacheable;

@Cacheable(
        cacheNames = "featuredApp",
        key = "@cacheKeyUtils.generateKey(#appQueryRequest)",
        condition = "#appQueryRequest.pageNum >= 1 && #appQueryRequest.pageNum <= 10"
)
@PostMapping("/list/page/featured")
public BaseResponse<Page<AppVO>> listFeaturedAppVOByPage(
        @RequestBody AppQueryRequest appQueryRequest,
        HttpServletRequest request) {
```

Do not add any `@CacheEvict` annotations.

- [ ] **Step 4: Run the Controller cache test and verify GREEN**

Run: `./mvnw -Dtest=AppControllerCacheTest test`

Expected: all three cache-boundary tests pass.

- [ ] **Step 5: Run all new tests together**

Run: `./mvnw -Dtest=CacheKeyUtilsTest,RedisCacheConfigTest,AppControllerCacheTest test`

Expected: all new tests pass.

- [ ] **Step 6: Commit endpoint caching**

```bash
git add src/main/java/com/xhl/aicodegenerate/controller/AppController.java \
  src/test/java/com/xhl/aicodegenerate/controller/AppControllerCacheTest.java
git commit -m "feat: cache featured app pages"
```

---

### Task 4: Redis and endpoint performance verification

**Files:**
- No production files.
- Record measured results in the final handoff.

**Interfaces:**
- Consumes: local Redis, MySQL, backend on port 8123, and an authenticated session cookie for cold-cache population.
- Produces: Redis connectivity evidence, a stored cache key with TTL, cold/hot latency samples, and a calculated speedup.

- [ ] **Step 1: Verify Redis connectivity**

Run: `redis-cli -h localhost -p 6379 PING`

Expected: `PONG`. If the configured Redis password is non-empty, use `REDISCLI_AUTH` without printing the password.

- [ ] **Step 2: Start the backend without exposing secrets**

Run: `./mvnw spring-boot:run`

Wait for the application to report port 8123 as started. Do not print `application-local.yml` credentials.

- [ ] **Step 3: Populate a cold cache entry**

Use the existing valid cookie file if it still represents an authenticated session:

```bash
curl -sS -o /tmp/featured-cold.json \
  -w '%{time_total}\n' \
  -b ../cookies.txt \
  -H 'Content-Type: application/json' \
  -d '{"pageNum":1,"pageSize":6,"sortField":"createTime","sortOrder":"descend","appName":"cache-benchmark-20260816"}' \
  http://localhost:8123/api/app/list/page/featured
```

Verify the response code field is `0`. If the session is invalid, report that authenticated cold-cache benchmarking requires a fresh login rather than inventing credentials.

- [ ] **Step 4: Verify key and TTL**

Run a non-destructive scan limited to the application cache prefix:

```bash
redis-cli --scan --pattern 'zero-code:cache:featuredApp::*'
```

Read the TTL without touching unrelated Redis keys:

```bash
featured_cache_key=$(redis-cli --scan --pattern 'zero-code:cache:featuredApp::*' | head -1)
redis-cli TTL "$featured_cache_key"
```

Verify the value is greater than 0 and at most 600 seconds.

- [ ] **Step 5: Measure warm cache samples**

Run the same request ten times and calculate the median of samples 5 and 6:

```bash
for i in {1..10}; do
  curl -sS -o /dev/null \
    -w '%{time_total}\n' \
    -b ../cookies.txt \
    -H 'Content-Type: application/json' \
    -d '{"pageNum":1,"pageSize":6,"sortField":"createTime","sortOrder":"descend","appName":"cache-benchmark-20260816"}' \
    http://localhost:8123/api/app/list/page/featured
done | sort -n | awk 'NR == 5 { lower = $1 } NR == 6 { print (lower + $1) / 2 }'
```

Keep response bodies out of timing output. Make one additional body-returning request and compare its JSON data with `/tmp/featured-cold.json`.

- [ ] **Step 6: Calculate speedup**

Compute:

```text
speedup = cold-cache response time / median warm-cache response time
```

Report raw cold time, median warm time, speedup, sample count, and the limitation that this is a local environment measurement.

- [ ] **Step 7: Stop only the backend process started by this task**

Send an interrupt to that exact Maven process/session. Do not stop Redis, MySQL, or unrelated Java processes.

---

### Task 5: Full regression verification and handoff

**Files:**
- Review all modified production and test files.

**Interfaces:**
- Consumes: completed cache implementation.
- Produces: verified build, test evidence, and source-level flow explanation.

- [ ] **Step 1: Run the deterministic local test suite**

Exclude tests that make real external AI or image API calls. Run this exact suite:

```bash
./mvnw -Dtest=CacheKeyUtilsTest,RedisCacheConfigTest,AppControllerCacheTest,\
DatabaseLoadingChatMemoryStoreTest,StreamHandlerExecutorTest,MultiFileCodeParserTest,\
ChatHistoryOrderUtilsTest,WorkflowContextTest,CodeGenWorkflowRoutingTest test
```

Expected: every selected deterministic test passes with zero failures and zero errors.

- [ ] **Step 2: Run package verification without external integration tests**

Run: `./mvnw -DskipTests package`

Expected: build succeeds.

- [ ] **Step 3: Inspect the final diff and repository status**

Run:

```bash
git diff --check
git status --short
git log --oneline -5
```

Confirm only cache implementation files and pre-existing user changes are present. Do not include or modify unrelated `.DS_Store`, AI concurrency test, workflow documents, or factory comment changes.

- [ ] **Step 4: Explain the final runtime flow**

The handoff must connect these source locations in order:

1. `@EnableCaching` registers cache interception.
2. `@Cacheable` checks the page condition and hashes the query.
3. `RedisCacheManager` builds the physical key, reads JSON, and enforces the 10-minute TTL.
4. Cache hit returns before the Controller body.
5. Cache miss executes login validation, MySQL pagination, VO conversion, then writes the successful result.
6. Pages after 10 always bypass Redis.
7. Data changes become visible after natural expiration because there is no active eviction.

- [ ] **Step 5: Report verification evidence**

Include exact test counts, build status, Redis PING/key/TTL result, measured performance ratio or the precise environmental blocker that prevented measurement.
