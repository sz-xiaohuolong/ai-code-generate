package com.xhl.aicodegenerate.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 真实本地依赖的链路测试，阈值设为零仅验证隔离和版本发布，不代表中文相关性通过。 */
@EnabledIfSystemProperty(named = "knowledge.test.real", matches = "true")
class KnowledgeRealIndexTest {
    @Test void realOllamaAndChromaPreserveOwnerAndVersionBoundaries() {
        String url = System.getProperty("knowledge.test.mysql.url");
        assertNotNull(url); assertTrue(url.contains("/knowledge_mvp_test"));
        var properties = new KnowledgeProperties();
        properties.setChromaUrl(System.getProperty("knowledge.test.chroma.url"));
        properties.setMinScore(0);
        var store = new KnowledgeStore(new DriverManagerDataSource(url, "root", ""), properties);
        var embedding = new OllamaKnowledgeEmbedding(properties);
        var index = new ChromaKnowledgeIndex(properties);
        var retrieval = new KnowledgeRetrievalService(store, embedding, index, properties);
        long owner = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        String text = "订单取消接口为 POST /internal/orders/{orderNo}/cancel，已发货订单禁止取消。";
        var first = store.upload(owner, "订单.md", text.getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString(), null, null);
        var other = store.upload(owner + 1, "订单.md", text.getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString(), null, null);
        try (var documents = new KnowledgeDocumentService(store, embedding, index, properties)) {
            documents.process(first); documents.process(other);
            assertEquals(1, store.activeVersions(owner).size());
            assertTrue(retrieval.search(owner, "订单取消接口").stream().allMatch(s -> s.documentId() == first.version().documentId()));
            var doc = store.list(owner).getFirst();
            var replacement = store.upload(owner, "订单.md", "新版订单查询接口 GET /v2/orders。".getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString(), doc.id(), doc.revision());
            assertEquals(first.version().id(), store.activeVersions(owner).getFirst().id());
            documents.process(replacement);
            assertEquals(replacement.version().id(), store.activeVersions(owner).getFirst().id());
            assertTrue(retrieval.search(owner, "订单查询接口").stream().allMatch(s -> s.versionId() == replacement.version().id()));
            store.delete(owner, first.version().documentId());
            assertEquals("KNOWLEDGE_EMPTY", assertThrows(KnowledgeException.class, () -> retrieval.search(owner, "订单")).getReasonCode());
            assertEquals(other.version().id(), retrieval.search(owner + 1, "订单取消").getFirst().versionId());
            index.removeBuild(first.collectionKey(), replacement.operation().buildId());
        } finally {
            store.delete(owner, first.version().documentId()); store.delete(owner + 1, other.version().documentId());
            index.removeBuild(first.collectionKey(), first.operation().buildId()); index.removeBuild(other.collectionKey(), other.operation().buildId());
        }
    }
}
