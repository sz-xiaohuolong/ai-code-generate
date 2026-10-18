package com.xhl.aicodegenerate.generation;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Redis 中的聚合快照。输入摘要仅用于内部幂等比较，不作为鉴权凭据。 */
@Data
public class GenerationTask {
    private String id;
    private Long appId;
    private Long userId;
    private String clientRequestId;
    private String inputHash;
    private String prompt;
    private String codeGenType;
    private String generationMode;
    private TaskStatus status = TaskStatus.STARTING;
    private String stage = "准备生成";
    private String message = "正在准备任务";
    private String output = "";
    private String diagnostics;
    private Long userMessageId;
    private Long aiMessageId;
    private int repairCount;
    private int modelCalls;
    private int usageReportedCalls;
    private Long inputTokens;
    private Long outputTokens;
    private Long reasoningTokens;
    private long createdAt;
    private long updatedAt;
    private long deadlineAt;
    private long revision;
    private boolean previewAvailable;
    private List<Map<String, Object>> steps = new ArrayList<>();
}
