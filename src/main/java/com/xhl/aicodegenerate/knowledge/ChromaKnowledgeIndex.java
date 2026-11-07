package com.xhl.aicodegenerate.knowledge;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import static com.xhl.aicodegenerate.knowledge.KnowledgeTypes.Source;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/** collection 必须来自业务库，调用方不能把模型或浏览器参数透传到此适配器。 */
@Component
public class ChromaKnowledgeIndex {
    private static final String COLLECTIONS = "/api/v2/tenants/default_tenant/databases/default_database/collections/";
    private final KnowledgeProperties properties;
    private final RestClient client;
    // ponytail: 只缓存连接适配器，最多 256 个；超出后重新连接，不缓存用户资料。
    private final Map<String, ChromaEmbeddingStore> stores = new LinkedHashMap<>(16, 0.75f, true);

    public ChromaKnowledgeIndex(KnowledgeProperties properties) {
        this.properties = properties;
        var timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        client = RestClient.builder().baseUrl(properties.getChromaUrl()).requestFactory(factory).build();
    }

    public void add(String collection, String profile, List<Source> sources, List<Embedding> vectors) {
        if (sources.isEmpty() || sources.size() != vectors.size()) throw incomplete();
        safely(() -> {
            List<TextSegment> segments = sources.stream().map(source -> TextSegment.from(source.text(),
                    Metadata.from(Map.of("documentId", Long.toString(source.documentId()),
                            "versionId", Long.toString(source.versionId()), "versionNo", source.versionNo(),
                            "documentName", source.documentName(), "buildId", source.buildId(),
                            "profile", profile, "hash", DigestUtil.sha256Hex(source.text()))))).toList();
            store(collection).addAll(sources.stream().map(Source::chunkId).toList(), vectors, segments);
            return null;
        });
    }

    /** SDK 没有按批次读回接口，补充 V2 get 校验完整 ID 集与正文哈希后才能发布。 */
    public void verify(String collection, String buildId, List<Source> expected) {
        safely(() -> {
            store(collection);
            JsonNode info = client.get().uri(COLLECTIONS + collection).retrieve().body(JsonNode.class);
            if (info == null || !info.path("id").isTextual()) throw incomplete();
            Map<String, String> wanted = new HashMap<>();
            for (Source source : expected) wanted.put(source.chunkId(), DigestUtil.sha256Hex(source.text()));
            if (wanted.size() != expected.size()) throw incomplete();
            Set<String> seen = new HashSet<>();
            for (int offset = 0; ; offset += 128) {
                JsonNode result = client.post().uri(COLLECTIONS + info.path("id").asText() + "/get")
                        .body(Map.of("where", Map.of("buildId", buildId), "limit", 128, "offset", offset,
                                "include", List.of("documents", "metadatas"))).retrieve().body(JsonNode.class);
                if (result == null || !result.path("ids").isArray()) throw incomplete();
                JsonNode ids = result.path("ids"), docs = result.path("documents"), metadata = result.path("metadatas");
                if (!docs.isArray() || !metadata.isArray() || docs.size() != ids.size() || metadata.size() != ids.size()) throw incomplete();
                for (int i = 0; i < ids.size(); i++) {
                    String id = ids.get(i).asText(), hash = wanted.get(id);
                    if (hash == null || !seen.add(id) || !docs.get(i).isTextual()
                            || !hash.equals(DigestUtil.sha256Hex(docs.get(i).asText()))
                            || !hash.equals(metadata.get(i).path("hash").asText())) throw incomplete();
                }
                if (ids.size() < 128) break;
            }
            if (seen.size() != wanted.size()) throw incomplete();
            return null;
        });
    }

    public List<Source> search(String collection, List<String> builds, String profile, Embedding query) {
        if (builds.isEmpty()) return List.of();
        return safely(() -> store(collection).search(EmbeddingSearchRequest.builder().queryEmbedding(query)
                .filter(metadataKey("buildId").isIn(builds).and(metadataKey("profile").isEqualTo(profile)))
                .maxResults(properties.getCandidates()).minScore(properties.getMinScore()).build()).matches()
                .stream().map(match -> {
                    TextSegment segment = match.embedded();
                    if (segment == null || !DigestUtil.sha256Hex(segment.text()).equals(segment.metadata().getString("hash"))) throw incomplete();
                    Metadata m = segment.metadata();
                    return new Source(Long.parseLong(m.getString("documentId")), Long.parseLong(m.getString("versionId")),
                            m.getInteger("versionNo"), m.getString("documentName"), m.getString("buildId"), match.embeddingId(), segment.text(), match.score());
                }).toList());
    }

    public void removeBuild(String collection, String buildId) {
        safely(() -> { store(collection).removeAll(metadataKey("buildId").isEqualTo(buildId)); return null; });
    }

    private synchronized ChromaEmbeddingStore store(String collection) {
        if (collection == null || !collection.matches("kb_[a-f0-9]{32}")) throw incomplete();
        ChromaEmbeddingStore existing = stores.get(collection);
        if (existing != null) return existing;
        var created = ChromaEmbeddingStore.builder().baseUrl(properties.getChromaUrl()).apiVersion(ChromaApiVersion.V2)
                .httpClientBuilder(dev.langchain4j.http.client.spring.restclient.SpringRestClient.builder())
                .tenantName("default_tenant").databaseName("default_database").collectionName(collection)
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds())).logRequests(false).logResponses(false).build();
        if (stores.size() >= 256) stores.remove(stores.keySet().iterator().next());
        stores.put(collection, created);
        return created;
    }

    private <T> T safely(Supplier<T> operation) {
        try { return operation.get(); }
        catch (KnowledgeException error) { throw error; }
        catch (RuntimeException error) { throw new KnowledgeException("INDEX_UNAVAILABLE", "知识索引暂不可用，请检查 Chroma 服务"); }
    }

    private static KnowledgeException incomplete() {
        return new KnowledgeException("INDEX_INCOMPLETE", "知识索引不完整，文档未发布，请重试处理");
    }
}
