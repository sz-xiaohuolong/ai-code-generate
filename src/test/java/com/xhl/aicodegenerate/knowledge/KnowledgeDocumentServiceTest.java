package com.xhl.aicodegenerate.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeDocumentServiceTest {
    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final OllamaKnowledgeEmbedding embedding = mock(OllamaKnowledgeEmbedding.class);
    private final ChromaKnowledgeIndex index = mock(ChromaKnowledgeIndex.class);
    private final KnowledgeProperties properties = new KnowledgeProperties();

    private KnowledgeTypes.Work work() {
        var operation = new KnowledgeTypes.Operation(1, 1, "UPLOAD", "request", "hash", 2, 3, 1, "build", "PROCESSING");
        var version = new KnowledgeTypes.Version(3, 2, 1, "规则.md", 1, "PROCESSING", 100,
                new byte[0], "中文资料完整保留不能截断", null, 1, "build", null, 0);
        return new KnowledgeTypes.Work(operation, version, "collection", true);
    }

    @Test void splitsOversizedEmbeddingAndVerifiesBeforePublishing() {
        var work = work();
        when(store.isCurrent(work.operation())).thenReturn(true);
        when(embedding.embed(anyString(), eq(false))).thenAnswer(call -> {
            String text = call.getArgument(0);
            if (text.length() > 4) throw new KnowledgeException("EMBEDDING_TOO_LONG", "超长");
            return new OllamaKnowledgeEmbedding.Result(Embedding.from(new float[]{1, 0}), 1L, "profile");
        });
        when(store.publish(eq(work.operation()), eq("profile"), anyInt())).thenReturn(true);
        try (var service = new KnowledgeDocumentService(store, embedding, index, properties)) {
            service.process(work);
        }
        @SuppressWarnings("unchecked") ArgumentCaptor<List<KnowledgeTypes.Source>> sources = ArgumentCaptor.forClass(List.class);
        var order = inOrder(index, store);
        order.verify(index).add(eq("collection"), eq("profile"), sources.capture(), anyList());
        order.verify(index).verify(eq("collection"), eq("build"), anyList());
        order.verify(store).publish(eq(work.operation()), eq("profile"), anyInt());
        assertEquals(work.version().text(), sources.getValue().stream().map(KnowledgeTypes.Source::text).reduce("", String::concat));
        verify(store, never()).fail(any(), anyString());
    }

    @Test void failureDoesNotPublishAndDeletedWorkCannotStartEmbedding() {
        var work = work();
        when(store.isCurrent(work.operation())).thenReturn(true);
        when(embedding.embed(anyString(), eq(false))).thenThrow(new KnowledgeException("EMBEDDING_UNAVAILABLE", "暂不可用"));
        try (var service = new KnowledgeDocumentService(store, embedding, index, properties)) { service.process(work); }
        verify(store).fail(work.operation(), "EMBEDDING_UNAVAILABLE");
        verify(store, never()).publish(any(), anyString(), anyInt());
        reset(embedding, store, index);
        when(store.isCurrent(work.operation())).thenReturn(false);
        try (var service = new KnowledgeDocumentService(store, embedding, index, properties)) { service.process(work); }
        verifyNoInteractions(embedding);
        verify(store, never()).publish(any(), anyString(), anyInt());
    }

    @Test void incompleteReadbackAndLatePublicationCleanOnlyThisBuild() {
        var work = work();
        when(store.isCurrent(work.operation())).thenReturn(true);
        when(embedding.embed(anyString(), eq(false))).thenReturn(new OllamaKnowledgeEmbedding.Result(Embedding.from(new float[]{1, 0}), 1L, "profile"));
        doThrow(new KnowledgeException("INDEX_INCOMPLETE", "索引不完整")).when(index).verify(anyString(), anyString(), anyList());
        try (var service = new KnowledgeDocumentService(store, embedding, index, properties)) { service.process(work); }
        verify(store).fail(work.operation(), "INDEX_INCOMPLETE");
        verify(store, never()).publish(any(), anyString(), anyInt());
        verify(index).removeBuild("collection", "build");
    }
    @Test void skipsWhitespaceOnlyChunksWithoutLosingOriginalPositions() {
        var original = work();
        String text = "订单接口" + " ".repeat(30) + "取消规则";
        var v = original.version();
        var version = new KnowledgeTypes.Version(v.id(), v.documentId(), v.ownerId(), v.name(), v.versionNo(),
                v.status(), v.byteSize(), v.raw(), text, v.profile(), v.attempt(), v.buildId(), v.publishedBuildId(), v.chunkCount());
        var work = new KnowledgeTypes.Work(original.operation(), version, original.collectionKey(), true);
        properties.setChunkSize(8); properties.setChunkOverlap(0);
        when(store.isCurrent(work.operation())).thenReturn(true);
        when(embedding.embed(anyString(), eq(false))).thenAnswer(call -> {
            if (((String) call.getArgument(0)).isBlank()) throw new KnowledgeException("EMPTY_EMBEDDING", "空白输入");
            return new OllamaKnowledgeEmbedding.Result(Embedding.from(new float[]{1, 0}), 1L, "profile");
        });
        when(store.publish(eq(work.operation()), eq("profile"), anyInt())).thenReturn(true);
        try (var service = new KnowledgeDocumentService(store, embedding, index, properties)) { service.process(work); }
        @SuppressWarnings("unchecked") ArgumentCaptor<List<KnowledgeTypes.Source>> sources = ArgumentCaptor.forClass(List.class);
        verify(index).verify(eq("collection"), eq("build"), sources.capture());
        assertTrue(sources.getValue().stream().noneMatch(source -> source.text().isBlank()));
        assertTrue(sources.getValue().stream().anyMatch(source -> source.text().contains("订单接口")));
        assertTrue(sources.getValue().stream().anyMatch(source -> source.text().contains("取消规则")));
        verify(store).publish(work.operation(), "profile", sources.getValue().size());
    }

}
