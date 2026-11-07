package com.xhl.aicodegenerate.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 HTTP 客户端核验协议，服务端不调用模型。 */
class OllamaKnowledgeEmbeddingTest {
    @Test void forbidsTruncationAndPinsModelDigestAndDimensions() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var json=new ObjectMapper();var request=new AtomicReference<JsonNode>();
        server.createContext("/api/tags",exchange->{
            byte[] result="{\"models\":[{\"name\":\"nomic-embed-text:latest\",\"digest\":\"fixed-digest\"}]}".getBytes(StandardCharsets.UTF_8);
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
            var embedding=new OllamaKnowledgeEmbedding(properties);
            var result=embedding.embed("接口说明",false);
            assertFalse(request.get().path("truncate").asBoolean(true));
            assertEquals("search_document: 接口说明",request.get().path("input").asText());
            assertEquals(3,result.vector().dimension());
            assertEquals(8,result.tokens());
            String profile=embedding.profile();
            embedding.embed("订单接口",true);
            assertEquals("search_query: 订单接口",request.get().path("input").asText());
            assertEquals(profile,embedding.profile());
        } finally { server.stop(0); }
    }
}
