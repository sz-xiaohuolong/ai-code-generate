package com.xhl.aicodegenerate.entity;

import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

/** 一次创建或生成的追踪索引；执行结果与记录完整性独立。 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_trace")
public class GenerationTrace {
    private String traceId;
    private String rootSpanId;
    private String traceKind;
    private String requestId;
    private String taskId;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private Long userId;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private Long appId;
    private String creationLinkState;
    private String creationTraceId;
    private String generationMode;
    private String codeGenType;
    private String executionStatus;
    private String recordingStatus;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant expiresAt;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private Long durationMs;
    private String serviceInstanceId;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private long modelCalls;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private long inputReportedCalls;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private long outputReportedCalls;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private Long knownInputTokens;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private Long knownOutputTokens;
    private String failureCategory;
    private String safeMessage;
    private String artifactRef;
    private String artifactDigest;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private long revision;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private long lastPersistedSequence;
}
