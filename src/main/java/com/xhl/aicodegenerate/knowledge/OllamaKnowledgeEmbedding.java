package com.xhl.aicodegenerate.knowledge;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.embedding.Embedding;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** 薄 HTTP 适配：明确禁止 Ollama 截断，不引入默认行为不同的第二套客户端。 */
@Component
public class OllamaKnowledgeEmbedding {
    private final KnowledgeProperties properties;
    private final RestClient client;
    private String digest;
    private int dimensions;

    public record Result(Embedding vector, Long tokens, String profile) { }

    public OllamaKnowledgeEmbedding(KnowledgeProperties properties) {
        this.properties=properties;
        var timeout=Duration.ofSeconds(properties.getTimeoutSeconds());
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client=RestClient.builder().baseUrl(properties.getOllamaUrl()).requestFactory(factory).build();
    }

    public Result embed(String text,boolean query) {
        if(text==null||text.isBlank())throw new KnowledgeException("EMBEDDING_INPUT_INVALID","没有可向量化的文本");
        try {
            String before=currentDigest();checkDigest(before);
            String prefix=properties.getEmbeddingModel().startsWith("nomic-embed-text")?(query?"search_query: ":"search_document: "):"";
            JsonNode response=client.post().uri("/api/embed").body(Map.of("model",properties.getEmbeddingModel(),"input",prefix+text,"truncate",false)).retrieve().body(JsonNode.class);
            if(response==null||!response.path("embeddings").isArray()||response.path("embeddings").size()!=1)throw invalidVector();
            JsonNode vector=response.path("embeddings").get(0);
            if(!vector.isArray()||vector.isEmpty())throw invalidVector();
            float[] values=new float[vector.size()];double norm=0;
            for(int i=0;i<values.length;i++){
                if(!vector.get(i).isNumber())throw invalidVector();
                values[i]=(float)vector.get(i).asDouble();
                if(!Float.isFinite(values[i]))throw invalidVector();
                norm+=(double)values[i]*values[i];
            }
            if(norm==0)throw invalidVector();
            checkDigest(currentDigest());checkDimensions(values.length);
            Long tokens=response.has("prompt_eval_count")&&response.path("prompt_eval_count").canConvertToLong()&&response.path("prompt_eval_count").asLong()>=0?response.path("prompt_eval_count").asLong():null;
            return new Result(Embedding.from(values),tokens,profile());
        } catch(KnowledgeException error){throw error;}
        catch(RestClientResponseException error){
            String body=error.getResponseBodyAsString().toLowerCase(Locale.ROOT);
            if(error.getStatusCode().value()==400&&(body.contains("context length")||body.contains("input length")))
                throw new KnowledgeException("EMBEDDING_TOO_LONG","文本超过模型窗口，需要继续分块");
            throw new KnowledgeException("EMBEDDING_UNAVAILABLE","本地向量模型暂不可用，请检查 Ollama 和模型配置");
        } catch(RuntimeException error){
            throw new KnowledgeException("EMBEDDING_UNAVAILABLE","本地向量模型暂不可用，请检查 Ollama 和模型配置");
        }
    }

    public synchronized String profile() {
        if(digest==null||dimensions==0)throw new KnowledgeException("EMBEDDING_UNINITIALIZED","向量模型尚未完成校验");
        return DigestUtil.sha256Hex(digest+":"+properties.getEmbeddingModel()+":"+dimensions+":"+properties.getChunkSize()+":"+properties.getChunkOverlap()+":prefix-policy-v1");
    }

    private String currentDigest() {
        JsonNode tags=client.get().uri("/api/tags").retrieve().body(JsonNode.class);
        if(tags!=null)for(JsonNode model:tags.path("models")){
            if(properties.getEmbeddingModel().equals(model.path("name").asText())&&!model.path("digest").asText().isBlank())return model.path("digest").asText();
        }
        throw new KnowledgeException("EMBEDDING_MODEL_MISSING","未找到配置的本地 Embedding 模型，请先拉取该模型");
    }

    private synchronized void checkDigest(String current) {
        if(digest==null)digest=current;
        else if(!digest.equals(current))throw new KnowledgeException("EMBEDDING_PROFILE_CHANGED","模型已变化，请重启并重建知识索引后使用");
    }
    private synchronized void checkDimensions(int current) {
        if(dimensions==0)dimensions=current;
        else if(dimensions!=current)throw new KnowledgeException("EMBEDDING_PROFILE_CHANGED","向量维度发生变化，请重建知识索引");
    }
    private static KnowledgeException invalidVector(){return new KnowledgeException("EMBEDDING_INVALID_RESPONSE","向量模型响应不完整，文档未入库");}
}
