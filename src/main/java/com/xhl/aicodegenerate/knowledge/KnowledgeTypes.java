package com.xhl.aicodegenerate.knowledge;

import java.util.List;

/** 内部存储值与对外只读视图；文件原文不能作为管理列表响应。 */
public final class KnowledgeTypes {
    private KnowledgeTypes() { }
    public record Space(long ownerId, String collectionKey, String profile) { }
    public record Version(long id, long documentId, long ownerId, String name, int versionNo, String status,
                          long byteSize, byte[] raw, String text, String profile, int attempt,
                          String buildId, String publishedBuildId, int chunkCount) { }
    public record Operation(long id, long ownerId, String type, String requestId, String requestHash,
                            long documentId, long versionId, int attempt, String buildId, String status) { }
    public record Work(Operation operation, Version version, String collectionKey, boolean newlyCreated) { }
    public record DocumentView(long id, String name, Long activeVersionId, Integer activeVersionNo,
                               Long pendingVersionId, Integer pendingVersionNo, String status,
                               String errorCode, long revision, long byteSize, long createdAt) { }
    public record Quota(long usedBytes, int documents, long maxFileBytes, long maxTotalBytes, int maxDocuments) { }
    public record Source(long documentId, long versionId, int versionNo, String documentName,
                         String buildId, String chunkId, String text, double score) { }
    public record Reference(String taskId, long appId, Long userMessageId, Long aiMessageId,
                            String state, long createdAt, List<Source> items) { }
}
