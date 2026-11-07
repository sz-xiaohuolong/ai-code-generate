package com.xhl.aicodegenerate.entity;

import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

/** 一次具体步骤的元数据；重试保留独立编号。 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_trace_step")
public class GenerationTraceStep {
    private String traceId;
    private String spanId;
    private String parentSpanId;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private long stepNo;
    private int attemptNo;
    private String stepKind;
    private String stepName;
    private String status;
    private Instant startedAt;
    private Instant finishedAt;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private Long durationMs;
    private String providerAlias;
    private String modelName;
    private String promptVersion;
    private String configDigest;
    private String finishReason;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private Long inputTokens;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private Long outputTokens;
    private String safeMetadata;
    private String errorCategory;
    private String safeMessage;
    @io.swagger.v3.oas.annotations.media.Schema(type = "string")
    private long revision;
}
