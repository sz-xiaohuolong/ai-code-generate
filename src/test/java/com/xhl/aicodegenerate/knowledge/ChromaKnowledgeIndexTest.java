package com.xhl.aicodegenerate.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 对隔离 Chroma 执行真实过滤、读回、清理，避免模拟掩盖协议差异。 */
@EnabledIfSystemProperty(named = "knowledge.test.chroma.url", matches = ".+")
class ChromaKnowledgeIndexTest {
    @Test void filtersBeforeTopKAndDeletesOnlyOneBuild() {
        var properties = new KnowledgeProperties();
        properties.setChromaUrl(System.getProperty("knowledge.test.chroma.url"));
        properties.setCandidates(1);
        properties.setMinScore(0.65);
        var index = new ChromaKnowledgeIndex(properties);
        String first = "kb_" + UUID.randomUUID().toString().replace("-", "");
        String second = "kb_" + UUID.randomUUID().toString().replace("-", "");
        String build = UUID.randomUUID().toString();
        String old = UUID.randomUUID().toString();
        var vector = Embedding.from(new float[]{1, 0, 0});
        var source = new KnowledgeTypes.Source(1, 2, 1, "接口.md", build, build + ":0", "订单接口", 0);
        var obsolete = new KnowledgeTypes.Source(1, 3, 2, "旧接口.md", old, old + ":0", "旧订单接口", 0);
        try {
            index.add(first, "profile", List.of(source), List.of(Embedding.from(new float[]{0.8f, 0.6f, 0})));
            index.add(first, "profile", List.of(obsolete), List.of(vector));
            index.add(second, "profile", List.of(source), List.of(vector));
            index.verify(first, build, List.of(source));
            assertThrows(KnowledgeException.class, () -> index.verify(first, build, List.of(source, obsolete)));
            var results = index.search(first, List.of(build), "profile", vector);
            assertEquals(1, results.size());
            assertEquals("订单接口", results.getFirst().text());
            assertTrue(index.search(first, List.of(build), "other-profile", vector).isEmpty());
            assertTrue(index.search(first, List.of(), "profile", vector).isEmpty());
            index.removeBuild(first, build);
            assertTrue(index.search(first, List.of(build), "profile", vector).isEmpty());
            assertEquals(1, index.search(first, List.of(old), "profile", vector).size());
            assertEquals(1, index.search(second, List.of(build), "profile", vector).size());
        } finally {
            // 清理失败不能覆盖原始断言；这里只涉及一次性隔离容器。
            for (String[] pair : List.of(new String[]{first, build}, new String[]{first, old}, new String[]{second, build})) {
                try { index.removeBuild(pair[0], pair[1]); } catch (KnowledgeException ignored) { }
            }
        }
    }
}
