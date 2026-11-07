package com.xhl.aicodegenerate.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实本地 BGE 与旧模型共存测试；只访问显式指定的隔离库。 */
@EnabledIfSystemProperty(named = "knowledge.test.real", matches = "true")
class BgeKnowledgeRealIndexTest {
    @Test
    void retrievesChineseDocumentsAndRejectsAnIndexFromThePreviousModel() {
        String url = System.getProperty("knowledge.test.mysql.url");
        assertNotNull(url);
        assertTrue(url.contains("/knowledge_mvp_test"));
        var properties = new KnowledgeProperties();
        properties.setChromaUrl(System.getProperty("knowledge.test.chroma.url"));
        var store = new KnowledgeStore(new DriverManagerDataSource(url, "root", ""), properties);
        var embedding = new OllamaKnowledgeEmbedding(properties);
        var index = new ChromaKnowledgeIndex(properties);
        var retrieval = new KnowledgeRetrievalService(store, embedding, index, properties);
        long owner = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        var work = new ArrayList<KnowledgeTypes.Work>();
        var documents = List.of(
                "星海商城订单列表接口为 GET /internal/orders，分页参数 pageIndex、pageSize。响应列表字段 orderNo、buyerName、totalAmount、status。取消订单使用 POST /internal/orders/{orderNo}/cancel，已发货订单禁止取消。",
                "员工报销需提供发票，单笔超过五千元须部门经理审批，财务每周五统一付款。",
                "晨露花店会员积分每消费十元积一分，会员生日赠送鲜花优惠券，不支持兑换现金。");
        try (var service = new KnowledgeDocumentService(store, embedding, index, properties)) {
            for (int i = 0; i < documents.size(); i++) {
                var item = upload(store, owner, "资料" + i + ".md", documents.get(i));
                work.add(item);
                service.process(item);
            }
            assertEquals(3, store.activeVersions(owner).size());
            assertEquals(1024, embedding.embed("订单", true).vector().dimension());
            assertEquals(work.get(0).version().documentId(), retrieval.search(owner,
                    "做个商城订单列表，显示买家和金额并提供取消按钮。").getFirst().documentId());
            assertEquals(work.get(1).version().documentId(), retrieval.search(owner,
                    "报销金额五千元以上怎么审批？").getFirst().documentId());
            for (String query : List.of("生成展示太阳系行星公转周期的科普页面。", "为宠物医院设计疫苗预约系统，支持猫狗疫苗预约。")) {
                assertEquals("KNOWLEDGE_NO_MATCH", assertThrows(KnowledgeException.class,
                        () -> retrieval.search(owner, query)).getReasonCode());
            }

            var oldProperties = new KnowledgeProperties();
            oldProperties.setEmbeddingModel("nomic-embed-text:latest");
            oldProperties.setChromaUrl(properties.getChromaUrl());
            var oldEmbedding = new OllamaKnowledgeEmbedding(oldProperties);
            var oldWork = upload(store, owner + 1, "旧模型资料.md", documents.getFirst());
            work.add(oldWork);
            try (var oldService = new KnowledgeDocumentService(store, oldEmbedding, index, oldProperties)) {
                oldService.process(oldWork);
            }
            assertEquals(1, store.activeVersions(owner + 1).size());
            assertNotEquals(oldEmbedding.profile(), embedding.profile());
            assertEquals("EMBEDDING_PROFILE_CHANGED", assertThrows(KnowledgeException.class,
                    () -> retrieval.search(owner + 1, "订单接口")).getReasonCode());
            // 模型更换也不能通过替换文档把新向量发布进旧模型空间。
            var oldDocument = store.list(owner + 1).getFirst();
            var replacement = store.upload(owner + 1, "替换.md", documents.getFirst().getBytes(StandardCharsets.UTF_8),
                    UUID.randomUUID().toString(), oldDocument.id(), oldDocument.revision());
            work.add(replacement);
            service.process(replacement);
            assertEquals(oldWork.version().id(), store.activeVersions(owner + 1).getFirst().id());
            assertEquals(oldEmbedding.profile(), store.space(owner + 1).profile());
        } finally {
            for (var item : work) {
                store.delete(item.version().ownerId(), item.version().documentId());
                index.removeBuild(item.collectionKey(), item.operation().buildId());
            }
        }
    }

    private KnowledgeTypes.Work upload(KnowledgeStore store, long owner, String name, String text) {
        return store.upload(owner, name, text.getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString(), null, null);
    }
}
