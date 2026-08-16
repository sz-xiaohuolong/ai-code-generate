package com.xhl.aicodegenerate.ratelimit.aspect;

import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.ratelimit.annotation.RateLimit;
import com.xhl.aicodegenerate.ratelimit.enums.RateLimitType;
import com.xhl.aicodegenerate.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitAspectTest {

    private RedissonClient redissonClient;
    private RRateLimiter rateLimiter;
    private UserService userService;
    private RateLimitAspect aspect;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        rateLimiter = mock(RRateLimiter.class);
        userService = mock(UserService.class);
        aspect = new RateLimitAspect(redissonClient, userService);
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldRejectRequestWhenUserBucketHasNoPermit() throws Exception {
        MockHttpServletRequest request = bindRequest();
        User user = new User();
        user.setId(42L);
        when(userService.getLoginUser(request)).thenReturn(user);
        when(redissonClient.getRateLimiter("rate_limit:ai_chat:user:42")).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire(1)).thenReturn(false);
        RateLimit rateLimit = annotation("userLimited");

        assertThatThrownBy(() -> aspect.doBefore(joinPoint("userLimited"), rateLimit))
                .isInstanceOf(BusinessException.class)
                .hasMessage("AI 对话请求过于频繁，请稍后再试")
                .extracting("code")
                .isEqualTo(42900);

        verify(rateLimiter).trySetRate(
                RateType.OVERALL, 5, Duration.ofSeconds(60), Duration.ofHours(1));
    }

    @Test
    void shouldFallbackToFirstForwardedIpWhenUserIsAnonymous() throws Exception {
        MockHttpServletRequest request = bindRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");
        when(userService.getLoginUser(request))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        when(redissonClient.getRateLimiter("rate_limit:ai_chat:ip:203.0.113.10"))
                .thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire(1)).thenReturn(true);

        aspect.doBefore(joinPoint("userLimited"), annotation("userLimited"));

        verify(rateLimiter).tryAcquire(1);
    }

    @Test
    void shouldUseDeclaringClassAndMethodForApiLimit() throws Exception {
        bindRequest();
        String expectedKey = "rate_limit:api:RateLimitAspectTest.apiLimited";
        when(redissonClient.getRateLimiter(expectedKey)).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire(1)).thenReturn(true);

        aspect.doBefore(joinPoint("apiLimited"), annotation("apiLimited"));

        verify(redissonClient).getRateLimiter(expectedKey);
    }

    private MockHttpServletRequest bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));
        return request;
    }

    private JoinPoint joinPoint(String methodName) throws Exception {
        Method method = getClass().getDeclaredMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        JoinPoint point = mock(JoinPoint.class);
        when(point.getSignature()).thenReturn(signature);
        return point;
    }

    private RateLimit annotation(String methodName) throws Exception {
        return getClass().getDeclaredMethod(methodName).getAnnotation(RateLimit.class);
    }

    @RateLimit(key = "ai_chat", limitType = RateLimitType.USER, rate = 5, rateInterval = 60,
            message = "AI 对话请求过于频繁，请稍后再试")
    private void userLimited() {
    }

    @RateLimit(limitType = RateLimitType.API)
    private void apiLimited() {
    }
}
