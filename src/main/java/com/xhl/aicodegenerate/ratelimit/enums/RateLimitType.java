package com.xhl.aicodegenerate.ratelimit.enums;

/**
 * 分布式限流维度。
 */
public enum RateLimitType {

    /** 接口级别。 */
    API,

    /** 登录用户级别，未登录时降级为 IP。 */
    USER,

    /** 客户端 IP 级别。 */
    IP
}
