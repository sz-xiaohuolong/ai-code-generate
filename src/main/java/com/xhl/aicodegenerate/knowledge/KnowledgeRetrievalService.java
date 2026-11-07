package com.xhl.aicodegenerate.knowledge;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import static com.xhl.aicodegenerate.knowledge.KnowledgeTypes.*;

/** 不接受用户指定 collection；先限定用户及已发布批次，再检索并核对业务事实。 */
@Service
public class KnowledgeRetrievalService {
    private final KnowledgeStore store;
    private final OllamaKnowledgeEmbedding embedding;
    private final ChromaKnowledgeIndex index;
    private final KnowledgeProperties properties;

    public KnowledgeRetrievalService(KnowledgeStore store, OllamaKnowledgeEmbedding embedding,
                                     ChromaKnowledgeIndex index, KnowledgeProperties properties) {
        this.store = store; this.embedding = embedding; this.index = index; this.properties = properties;
    }

    public void requireAvailable(long ownerId) {
        if (!properties.isEnabled()) throw new KnowledgeException("KNOWLEDGE_DISABLED", "知识库功能尚未配置启用");
        if (store.activeVersions(ownerId).isEmpty()) throw new KnowledgeException("KNOWLEDGE_EMPTY", "没有可用资料，请上传文档或等待处理完成后重试");
    }

    public List<Source> search(long ownerId, String query) {
        if (query == null || query.isBlank() || query.length() > 2000)
            throw new KnowledgeException("KNOWLEDGE_QUERY_INVALID", "检索查询必须为 1～2000 字符");
        requireAvailable(ownerId);
        var result = embedding.embed(query.trim(), true);
        for (int attempt = 0; attempt < 2; attempt++) {
            var versions = store.activeVersions(ownerId);
            Space space = store.space(ownerId);
            if (versions.isEmpty() || space == null) throw new KnowledgeException("KNOWLEDGE_EMPTY", "资料已删除或尚未处理完成，请刷新后重试");
            if (!result.profile().equals(space.profile()) || versions.stream().anyMatch(v -> !result.profile().equals(v.profile())))
                throw new KnowledgeException("EMBEDDING_PROFILE_CHANGED", "向量模型配置与资料不一致，请重建索引后重试");
            List<Source> candidates = index.search(space.collectionKey(), versions.stream().map(Version::publishedBuildId).toList(), result.profile(), result.vector());
            List<Source> valid = store.captureSources(ownerId, candidates);
            List<Source> selected = new ArrayList<>();
            int chars = 0;
            for (Source source : valid) {
                if (selected.size() >= properties.getMaxFragments()) break;
                if (chars + source.text().length() > properties.getMaxContextChars()) continue;
                selected.add(source); chars += source.text().length();
            }
            if (!selected.isEmpty()) return List.copyOf(selected);
            // 只对版本切换导致的候选失效重查；空命中或预算不足不通过降阈值补齐。
            if (candidates.isEmpty() || !valid.isEmpty()) break;
        }
        throw new KnowledgeException("KNOWLEDGE_NO_MATCH", "未找到相关参考资料，已停止生成；请补充资料或关闭知识库后重新发送");
    }
}
