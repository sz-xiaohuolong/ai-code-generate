package com.xhl.aicodegenerate.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static com.xhl.aicodegenerate.knowledge.KnowledgeTypes.*;

/** 有界异步入库；数据库持久化操作事实，进程退出后的处理由过期租约转为可重试失败。 */
@Slf4j
@Service
public class KnowledgeDocumentService implements AutoCloseable {
    private final KnowledgeStore store;
    private final OllamaKnowledgeEmbedding embedding;
    private final ChromaKnowledgeIndex index;
    private final KnowledgeProperties properties;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService maintenance;
    private long cleanupCursor;

    public KnowledgeDocumentService(KnowledgeStore store, OllamaKnowledgeEmbedding embedding,
                                    ChromaKnowledgeIndex index, KnowledgeProperties properties) {
        this.store = store;
        this.embedding = embedding;
        this.index = index;
        this.properties = properties;
        workers = new ThreadPoolExecutor(properties.getWorkers(), properties.getWorkers(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon().name("knowledge-index-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        maintenance = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("knowledge-maintenance").factory());
    }

    @PostConstruct
    void start() { maintenance.scheduleWithFixedDelay(this::maintain, 30, 60, TimeUnit.SECONDS); }

    public Work upload(long ownerId, String name, byte[] bytes, String requestId, Long documentId, Long revision) {
        requireEnabled();
        return enqueue(store.upload(ownerId, name, bytes, requestId, documentId, revision));
    }

    public Work retry(long ownerId, long documentId, long versionId, String requestId) {
        requireEnabled();
        return enqueue(store.retry(ownerId, documentId, versionId, requestId));
    }

    private Work enqueue(Work work) {
        if (work.newlyCreated()) {
            try { workers.execute(() -> process(work)); }
            catch (RejectedExecutionException error) { store.fail(work.operation(), "PROCESSING_BUSY"); }
        }
        return work;
    }

    void process(Work work) {
        boolean published = false;
        try {
            List<Source> all = new ArrayList<>(), pending = new ArrayList<>();
            List<Embedding> vectors = new ArrayList<>();
            String[] profile = {null};
            for (KnowledgeText.Chunk chunk : KnowledgeText.chunks(work.version().text(), properties.getChunkSize(), properties.getChunkOverlap())) {
                embedChunk(work, chunk.text(), chunk.start(), all, pending, vectors, profile);
            }
            flush(work, profile[0], pending, vectors);
            requireCurrent(work);
            index.verify(work.collectionKey(), work.operation().buildId(), all);
            requireCurrent(work);
            published = store.publish(work.operation(), profile[0], all.size());
        } catch (RuntimeException error) {
            String code = error instanceof KnowledgeException known ? known.getReasonCode() : "PROCESSING_FAILED";
            try { store.fail(work.operation(), code); }
            catch (RuntimeException ignored) { log.warn("知识文档失败状态暂未保存，等待租约恢复：operationId={}", work.operation().id()); }
        } finally {
            if (!published) {
                try { index.removeBuild(work.collectionKey(), work.operation().buildId()); }
                catch (RuntimeException ignored) { log.warn("知识索引清理待重试：operationId={}", work.operation().id()); }
            }
        }
    }

    private void embedChunk(Work work, String text, int start, List<Source> all, List<Source> pending,
                            List<Embedding> vectors, String[] profile) {
        requireCurrent(work);
        // 空白仅保留在原文中，不构造无语义向量；片段位置仍使用原文坐标。
        if (text.isBlank()) return;
        OllamaKnowledgeEmbedding.Result result;
        try { result = embedding.embed(text, false); }
        catch (KnowledgeException error) {
            if (!"EMBEDDING_TOO_LONG".equals(error.getReasonCode()) || text.codePointCount(0, text.length()) <= 1) throw error;
            int middle = text.offsetByCodePoints(0, text.codePointCount(0, text.length()) / 2);
            embedChunk(work, text.substring(0, middle), start, all, pending, vectors, profile);
            embedChunk(work, text.substring(middle), start + middle, all, pending, vectors, profile);
            return;
        }
        if (profile[0] != null && !profile[0].equals(result.profile())) throw new KnowledgeException("EMBEDDING_PROFILE_CHANGED", "向量模型配置已变化");
        profile[0] = result.profile();
        var version = work.version();
        var source = new Source(version.documentId(), version.id(), version.versionNo(), version.name(), work.operation().buildId(),
                work.operation().buildId() + ":" + start + ":" + (start + text.length()), text, 0);
        all.add(source); pending.add(source); vectors.add(result.vector());
        if (pending.size() >= 64) flush(work, profile[0], pending, vectors);
    }

    private void flush(Work work, String profile, List<Source> pending, List<Embedding> vectors) {
        if (pending.isEmpty()) return;
        requireCurrent(work);
        index.add(work.collectionKey(), profile, List.copyOf(pending), List.copyOf(vectors));
        pending.clear(); vectors.clear();
    }

    private void requireCurrent(Work work) {
        if (Thread.currentThread().isInterrupted() || !store.isCurrent(work.operation()))
            throw new KnowledgeException("PROCESSING_EXPIRED", "文档已删除或处理已超时，请重试");
    }

    private void maintain() {
        if (!properties.isEnabled()) return;
        try {
            for (Work work : store.expiredProcessing(50)) store.fail(work.operation(), "PROCESSING_EXPIRED");
            List<Work> obsolete = store.obsoleteBuilds(cleanupCursor, 20);
            for (Work work : obsolete) {
                try { index.removeBuild(work.collectionKey(), work.operation().buildId()); }
                catch (RuntimeException ignored) { log.warn("知识索引清理待下一轮重试：operationId={}", work.operation().id()); }
                cleanupCursor = work.operation().id();
            }
            if (obsolete.size() < 20) cleanupCursor = 0;
            store.clearDeletedApplicationReferences();
        } catch (RuntimeException error) { log.warn("知识库维护暂未完成，请检查业务表及依赖服务"); }
    }

    public void requireEnabled() {
        if (!properties.isEnabled()) throw new KnowledgeException("KNOWLEDGE_DISABLED", "知识库功能尚未配置启用");
    }

    @Override @PreDestroy
    public void close() { maintenance.shutdownNow(); workers.shutdownNow(); }
}
