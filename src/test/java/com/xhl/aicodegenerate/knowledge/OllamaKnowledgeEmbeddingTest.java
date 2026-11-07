package com.xhl.aicodegenerate.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 HTTP 客户端核验协议，服务端不调用模型。 */
class OllamaKnowledgeEmbeddingTest {
    @ParameterizedTest
    @ValueSource(strings = {"bge-m3:latest", "nomic-embed-text:latest"})
    void forbidsTruncationAndUsesTheSelectedModelsPrefixPolicy(String model) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var json=new ObjectMapper();var request=new AtomicReference<JsonNode>();
        server.createContext("/api/tags",exchange->{
            byte[] result=json.writeValueAsBytes(java.util.Map.of("models", java.util.List.of(java.util.Map.of("name", model, "digest", "fixed-digest"))));
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();
        });
        server.createContext("/api/embed",exchange->{
            request.set(json.readTree(exchange.getRequestBody()));
            byte[] result="{\"embeddings\":[[0.1,0.2,0.3]],\"prompt_eval_count\":8}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();
        });
        server.start();
        try {
            var properties=new KnowledgeProperties(); properties.setOllamaUrl("http://127.0.0.1:"+server.getAddress().getPort());
            // BGE 使用默认配置；旧模型仅在显式配置时使用其专用前缀。
            boolean nomic = model.startsWith("nomic-embed-text");
            if (nomic) properties.setEmbeddingModel(model);
            var embedding=new OllamaKnowledgeEmbedding(properties);
            var result=assertDoesNotThrow(() -> embedding.embed("接口说明",false), "应能使用已安装的选定模型完成向量化");
            assertFalse(request.get().path("truncate").asBoolean(true));
            assertEquals(model,request.get().path("model").asText());
            assertEquals(nomic ? "search_document: 接口说明" : "接口说明",request.get().path("input").asText());
            assertEquals(3,result.vector().dimension());
            assertEquals(8,result.tokens());
            String profile=embedding.profile();
            embedding.embed("订单接口",true);
            assertEquals(nomic ? "search_query: 订单接口" : "订单接口",request.get().path("input").asText());
            assertEquals(profile,embedding.profile());
        } finally { server.stop(0); }
    }
}
