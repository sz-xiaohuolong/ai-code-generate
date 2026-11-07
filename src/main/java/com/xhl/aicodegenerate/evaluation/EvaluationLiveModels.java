package com.xhl.aicodegenerate.evaluation;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 显式真实模型入口。HTTP 出站层计数，关闭 SDK 重试及重定向，所有槽位共用批次预算。 */
public final class EvaluationLiveModels implements AutoCloseable {
    private final Transport transport;
    private final EvaluationModelSet models;
    private final Map<String, Object> metadata;

    private EvaluationLiveModels(Transport transport, EvaluationModelSet models, Map<String, Object> metadata) {
        this.transport = transport; this.models = models; this.metadata = Map.copyOf(metadata);
    }

    public static EvaluationLiveModels from(Map<String, String> environment, EvaluationBudget budget) {
        String baseUrl = required(environment, "EVAL_MODEL_BASE_URL");
        String apiKey = required(environment, "EVAL_MODEL_API_KEY");
        String primary = modelName(required(environment, "EVAL_MODEL_NAME"));
        String routing = modelName(environment.getOrDefault("EVAL_ROUTING_MODEL_NAME", primary));
        String reasoning = modelName(environment.getOrDefault("EVAL_REASONING_MODEL_NAME", primary));
        URI base;
        try { base = URI.create(baseUrl); }
        catch (IllegalArgumentException error) { throw new EvaluationConfigurationException("评测模型地址无效"); }
        boolean loopback = Set.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost() == null ? "" : base.getHost());
        if (base.getHost() == null || base.getUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
                || !("https".equals(base.getScheme()) || ("http".equals(base.getScheme()) && loopback))) {
            throw new EvaluationConfigurationException("模型地址不能含凭据、查询或片段；非本机端点必须使用 HTTPS");
        }
        int tokens = integer(environment, "EVAL_MODEL_MAX_OUTPUT_TOKENS", 8192, 131072);
        int seconds = integer(environment, "EVAL_MODEL_TIMEOUT_SECONDS", 120, 600);
        String normalized = baseUrl.replaceAll("/+$", "");
        var transport = new Transport(URI.create(normalized + "/chat/completions"), budget);
        Duration timeout = Duration.ofSeconds(seconds);
        try {
            var primaryModel = synchronous(normalized, apiKey, primary, tokens, timeout, transport);
            var routingModel = routing.equals(primary) ? primaryModel
                    : synchronous(normalized, apiKey, routing, Math.min(tokens, 1024), timeout, transport);
            var standardModel = streaming(normalized, apiKey, primary, tokens, timeout, transport);
            var reasoningModel = reasoning.equals(primary) ? standardModel
                    : streaming(normalized, apiKey, reasoning, tokens, timeout, transport);
            // 真实模式只在 HTTP 出站处预占；不能再套 fixture 的模型级计数包装，避免重复计数。
            return new EvaluationLiveModels(transport, new EvaluationModelSet(primaryModel, routingModel, standardModel, reasoningModel),
                    Map.of("providerHost", base.getHost(), "endpointDigest", cn.hutool.crypto.digest.DigestUtil.sha256Hex(normalized),
                            "primaryModel", primary, "routingModel", routing, "reasoningModel", reasoning,
                            "temperature", 0.0, "maxOutputTokens", tokens, "requestTimeoutSeconds", seconds,
                            "sdkRetries", 0, "redirects", false));
        } catch (RuntimeException error) {
            transport.close();
            throw new EvaluationConfigurationException("无法初始化显式评测模型，请检查模型参数");
        }
    }

    private static OpenAiChatModel synchronous(String base, String key, String name, int tokens, Duration timeout, Transport transport) {
        return OpenAiChatModel.builder().baseUrl(base).apiKey(key).modelName(name).temperature(0.0).maxTokens(tokens)
                .timeout(timeout).maxRetries(0).logRequests(false).logResponses(false)
                .httpClientBuilder(transport.builder()).build();
    }
    private static OpenAiStreamingChatModel streaming(String base, String key, String name, int tokens, Duration timeout, Transport transport) {
        return OpenAiStreamingChatModel.builder().baseUrl(base).apiKey(key).modelName(name).temperature(0.0).maxTokens(tokens)
                .timeout(timeout).logRequests(false).logResponses(false).returnThinking(true).sendThinking(true, "reasoning_content")
                .accumulateToolCallId(false).httpClientBuilder(transport.builder()).build();
    }
    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) throw new EvaluationConfigurationException("真实评测缺少专用配置 " + key);
        return value;
    }
    private static String modelName(String value) {
        if (!value.matches("[A-Za-z0-9._:/-]{1,128}")) throw new EvaluationConfigurationException("评测模型名称格式无效");
        return value;
    }
    private static int integer(Map<String, String> environment, String name, int fallback, int maximum) {
        String value = environment.getOrDefault(name, Integer.toString(fallback));
        try {
            if (!value.matches("[0-9]{1,6}")) throw new NumberFormatException();
            int result = Integer.parseInt(value);
            if (result < 1 || result > maximum) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException error) { throw new EvaluationConfigurationException(name + " 超出允许范围"); }
    }
    public EvaluationModelSet models() { return models; }
    public Map<String, Object> metadata() { return metadata; }
    @Override public void close() { transport.close(); }

    /** 复用 Java 21 HTTP 与 LangChain4j SSE 解析器；只适配本评测使用的 JSON POST 协议。 */
    private static final class Transport implements AutoCloseable {
        private final URI endpoint;
        private final EvaluationBudget budget;
        private final java.net.http.HttpClient network = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("evaluation-model-deadline").factory());
        private final Set<InputStream> streams = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Transport(URI endpoint, EvaluationBudget budget) { this.endpoint = endpoint; this.budget = budget; }

        private HttpClientBuilder builder() {
            return new HttpClientBuilder() {
                private Duration connect = Duration.ofSeconds(10);
                private Duration read = Duration.ofSeconds(120);
                @Override public Duration connectTimeout() { return connect; }
                @Override public HttpClientBuilder connectTimeout(Duration value) { connect = value; return this; }
                @Override public Duration readTimeout() { return read; }
                @Override public HttpClientBuilder readTimeout(Duration value) { read = value; return this; }
                @Override public HttpClient build() {
                    Duration requestTimeout = read;
                    return new HttpClient() {
                        @Override public SuccessfulHttpResponse execute(HttpRequest request) {
                            return send(request, requestTimeout, null, null);
                        }
                        @Override public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                            if (closed.get()) { listener.onError(new IllegalStateException("评测模型环境已关闭")); return; }
                            workers.execute(() -> {
                                try { send(request, requestTimeout, parser, listener); }
                                catch (RuntimeException error) { listener.onError(error); }
                                finally { listener.onClose(); }
                            });
                        }
                    };
                }
            };
        }

        private SuccessfulHttpResponse send(HttpRequest request, Duration requestTimeout,
                ServerSentEventParser parser, ServerSentEventListener listener) {
            if (closed.get()) throw new IllegalStateException("评测模型环境已关闭");
            if (!"POST".equals(request.method().name()) || !endpoint.equals(URI.create(request.url()))) {
                throw new IllegalStateException("拒绝超出显式模型端点的请求");
            }
            budget.reserveModelCall();
            Duration remaining = budget.remaining();
            Duration timeout = remaining.compareTo(requestTimeout) < 0 ? remaining : requestTimeout;
            if (timeout.isZero()) { budget.checkActive(); throw new IllegalStateException("评测调用时限已到"); }
            var outbound = java.net.http.HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
            request.headers().forEach((name, values) -> values.forEach(value -> outbound.header(name, value)));
            Thread worker = Thread.currentThread();
            var bodyRef = new AtomicReference<InputStream>();
            var expired = new AtomicBoolean();
            var finished = new AtomicBoolean();
            Object lifecycle = new Object();
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            var deadline = timer.schedule(() -> {
                synchronized (lifecycle) {
                    if (finished.get()) return;
                    expired.set(true);
                    worker.interrupt();
                }
                closeStream(bodyRef.get());
                try { budget.checkActive(); } catch (EvaluationBudget.Exhausted ignored) { }
            }, timeout.toNanos(), TimeUnit.NANOSECONDS);
            try {
                var response = network.send(outbound.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                InputStream body = response.body();
                bodyRef.set(body); streams.add(body);
                if (expired.get()) throw new IllegalStateException("评测模型调用超时");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new HttpException(response.statusCode(), "评测模型端点返回 HTTP " + response.statusCode());
                }
                if (parser != null) {
                    listener.onOpen(SuccessfulHttpResponse.builder().statusCode(response.statusCode()).headers(response.headers().map()).build());
                    parser.parse(body, listener);
                    if (expired.get()) throw new IllegalStateException("评测模型调用超时");
                    return null;
                }
                byte[] bytes = body.readNBytes(32 * 1024 * 1024 + 1);
                if (bytes.length > 32 * 1024 * 1024) throw new IllegalStateException("模型响应超过评测读取上限");
                return SuccessfulHttpResponse.builder().statusCode(response.statusCode()).headers(response.headers().map())
                        .body(new String(bytes, StandardCharsets.UTF_8)).build();
            } catch (InterruptedException error) {
                if (!expired.get()) Thread.currentThread().interrupt();
                throw new IllegalStateException(expired.get() ? "评测模型调用超时" : "评测模型调用已中断");
            } catch (IOException error) {
                throw new IllegalStateException(expired.get() ? "评测模型调用超时" : "评测模型连接或响应读取失败");
            } finally {
                // cancel(false) 无法撤销已开始的计时回调；与中断动作互斥，避免污染调用者的下一项操作。
                synchronized (lifecycle) {
                    finished.set(true);
                    if (System.nanoTime() - deadlineNanos >= 0) expired.set(true);
                }
                deadline.cancel(false);
                InputStream body = bodyRef.get();
                if (body != null) { streams.remove(body); closeStream(body); }
                if (expired.get()) {
                    Thread.interrupted();
                    throw new IllegalStateException("评测模型调用超时");
                }
            }
        }

        private static void closeStream(InputStream stream) {
            if (stream != null) try { stream.close(); } catch (IOException ignored) { /* 仍由 HTTP 客户端和线程关闭回收。 */ }
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            timer.shutdownNow(); workers.shutdownNow(); streams.forEach(Transport::closeStream); network.shutdownNow();
            try {
                if (!workers.awaitTermination(5, TimeUnit.SECONDS) || !network.awaitTermination(Duration.ofSeconds(5))) {
                    throw new IllegalStateException("评测模型连接未能完整关闭");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("评测模型资源关闭被中断");
            }
        }
    }
}
