package com.xhl.aicodegenerate.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalServiceTest {
    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final OllamaKnowledgeEmbedding embedding = mock(OllamaKnowledgeEmbedding.class);
    private final ChromaKnowledgeIndex index = mock(ChromaKnowledgeIndex.class);
    private final KnowledgeProperties properties = new KnowledgeProperties();
    private final KnowledgeRetrievalService service = new KnowledgeRetrievalService(store, embedding, index, properties);

    @Test void noDocumentsStopsBeforeAnyEmbeddingAndQueryCannotCarryScope() {
        when(store.activeVersions(7)).thenReturn(List.of());
        assertEquals("KNOWLEDGE_EMPTY", assertThrows(KnowledgeException.class, () -> service.search(7, "订单接口")).getReasonCode());
        verifyNoInteractions(embedding, index);
        assertThrows(KnowledgeException.class, () -> service.search(7, " "));
    }

    @Test void bindsPublishedBuildsAndNeverFillsContextWithUnvalidatedHits() {
        var version = new KnowledgeTypes.Version(2, 1, 7, "规则.md", 1, "READY", 20, null, "订单接口", "profile", 1, "build", "build", 1);
        when(store.activeVersions(7)).thenReturn(List.of(version));
        when(store.space(7)).thenReturn(new KnowledgeTypes.Space(7, "private-collection", "profile"));
        var vector = Embedding.from(new float[]{1, 0});
        when(embedding.embed("订单", true)).thenReturn(new OllamaKnowledgeEmbedding.Result(vector, 1L, "profile"));
        var source = new KnowledgeTypes.Source(1, 2, 1, "规则.md", "build", "chunk", "订单接口", 0.9);
        when(index.search("private-collection", List.of("build"), "profile", vector)).thenReturn(List.of(source));
        when(store.captureSources(7, List.of(source))).thenReturn(List.of());
        assertEquals("KNOWLEDGE_NO_MATCH", assertThrows(KnowledgeException.class, () -> service.search(7, "订单")).getReasonCode());
        verify(index, times(2)).search("private-collection", List.of("build"), "profile", vector);
        when(store.captureSources(7, List.of(source))).thenReturn(List.of(source));
        assertEquals(List.of(source), service.search(7, "订单"));
        properties.setMaxContextChars(2);
        assertEquals("KNOWLEDGE_NO_MATCH", assertThrows(KnowledgeException.class, () -> service.search(7, "订单")).getReasonCode());
    }
}
