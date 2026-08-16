package com.xhl.aicodegenerate.ratelimit.aspect;

import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.ratelimit.annotation.RateLimit;
import com.xhl.aicodegenerate.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 将 {@link RateLimit} 转换为 Redisson 分布式令牌桶检查。
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    private static final Duration LIMITER_KEEP_ALIVE = Duration.ofHours(1);

    private final RedissonClient redissonClient;

    private final UserService userService;

    public RateLimitAspect(RedissonClient redissonClient, UserService userService) {
        this.redissonClient = redissonClient;
        this.userService = userService;
    }

    @Before("@annotation(rateLimit)")
    public void doBefore(JoinPoint point, RateLimit rateLimit) {
        String key = generateRateLimitKey(point, rateLimit);
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        rateLimiter.trySetRate(
                RateType.OVERALL,
                rateLimit.rate(),
                Duration.ofSeconds(rateLimit.rateInterval()),
                LIMITER_KEEP_ALIVE);
        if (!rateLimiter.tryAcquire(1)) {
            log.warn("触发分布式限流: key={}, rate={}, interval={}s",
                    key, rateLimit.rate(), rateLimit.rateInterval());
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, rateLimit.message());
        }
    }

    private String generateRateLimitKey(JoinPoint point, RateLimit rateLimit) {
        StringBuilder key = new StringBuilder("rate_limit:");
        if (StrUtil.isNotBlank(rateLimit.key())) {
            key.append(rateLimit.key()).append(':');
        }
        switch (rateLimit.limitType()) {
            case API -> appendApiKey(key, point);
            case USER -> appendUserOrIpKey(key);
            case IP -> key.append("ip:").append(getClientIp());
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的限流类型");
        }
        return key.toString();
    }

    private void appendApiKey(StringBuilder key, JoinPoint point) {
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        key.append("api:")
                .append(method.getDeclaringClass().getSimpleName())
                .append('.')
                .append(method.getName());
    }

    private void appendUserOrIpKey(StringBuilder key) {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            try {
                User loginUser = userService.getLoginUser(request);
                key.append("user:").append(loginUser.getId());
                return;
            } catch (BusinessException ignored) {
                // 未登录时按 IP 限流，避免匿名请求绕过保护。
            }
        }
        key.append("ip:").append(getClientIp());
    }

    private String getClientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "unknown";
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.substring(0, ip.indexOf(',')).trim();
        }
        return StrUtil.blankToDefault(ip, "unknown");
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
