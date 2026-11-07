package com.xhl.aicodegenerate.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/** 容量是用户可见限制；检索参数需用当前模型的中文样本校准。 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties {
    private boolean enabled = true;
    private long maxFileBytes = 2 * 1024 * 1024;
    private long maxTotalBytes = 20 * 1024 * 1024;
    private int maxDocuments = 50;
    private String ollamaUrl = "http://127.0.0.1:11434";
    private String embeddingModel = "nomic-embed-text:latest";
    private String chromaUrl = "http://127.0.0.1:8000";
    private int chunkSize = 512;
    private int chunkOverlap = 64;
    private int candidates = 12;
    private int maxFragments = 6;
    private int maxContextChars = 6000;
    private double minScore = 0.65;
    private int timeoutSeconds = 60;
    private int workers = 2;

    @PostConstruct
    public void validate() {
        if (maxFileBytes <= 0 || maxFileBytes >= Integer.MAX_VALUE || maxTotalBytes < maxFileBytes || maxDocuments < 1
                || chunkSize < 4 || chunkOverlap < 0 || chunkOverlap >= chunkSize / 2
                || candidates < 1 || candidates > 100 || maxFragments < 1 || maxFragments > candidates
                || maxContextChars < chunkSize || !Double.isFinite(minScore) || minScore < 0 || minScore > 1
                || timeoutSeconds < 1 || timeoutSeconds > 300 || workers < 1 || workers > 16) {
            throw new IllegalArgumentException("知识库配置无效，请检查容量、分块、检索阈值和并发上限");
        }
    }
}
