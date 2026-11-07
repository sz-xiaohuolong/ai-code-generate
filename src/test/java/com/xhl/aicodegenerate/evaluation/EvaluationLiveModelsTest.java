package com.xhl.aicodegenerate.evaluation;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 SDK 与本地 HTTP/SSE 协议，证明出站计数与请求上限，不访问付费供应商。 */
class EvaluationLiveModelsTest {
    @Test void everySlotSharesWireBudgetAndNoExtraRequestEscapesLimit() throws Exception {
        var calls = new AtomicInteger();
        var server = server(calls, 200);
        var budget = new EvaluationBudget(4, Duration.ofSeconds(10));
        try (var live = EvaluationLiveModels.from(environment(server), budget)) {
            var models = live.models();
            assertEquals("完成", models.primary().chat(request()).aiMessage().text());
            assertEquals("完成", models.routing().chat(request()).aiMessage().text());
            assertEquals("完成", streaming(models.standard()).get(3, TimeUnit.SECONDS).aiMessage().text());
            assertEquals("完成", streaming(models.reasoning()).get(3, TimeUnit.SECONDS).aiMessage().text());
            assertThrows(RuntimeException.class, () -> models.primary().chat(request()));
            assertEquals(4, calls.get());
            assertEquals(4, budget.modelCalls());
            assertFalse(live.metadata().toString().contains("must-not-leak"));
        } finally { server.stop(0); }
    }

    @Test void providerFailuresDoNotTriggerHiddenRetryAndRedirectsAreNotFollowed() throws Exception {
        for (int status : new int[]{503, 307}) {
            var calls = new AtomicInteger();
            var server = server(calls, status);
            var budget = new EvaluationBudget(10, Duration.ofSeconds(10));
            try (var live = EvaluationLiveModels.from(environment(server), budget)) {
                assertThrows(RuntimeException.class, () -> live.models().primary().chat(request()));
                assertThrows(Exception.class, () -> streaming(live.models().standard()).get(3, TimeUnit.SECONDS));
                assertEquals(2, calls.get(), "失败或重定向不得绕过每次出站预占");
                assertEquals(2, budget.modelCalls());
            } finally { server.stop(0); }
        }
    }

    @Test void deadlineClosesIncompleteStreamAndRejectsFurtherRequests() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(": waiting\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var budget = new EvaluationBudget(5, Duration.ofMillis(700));
        try (var live = EvaluationLiveModels.from(environment(server), budget)) {
            assertThrows(Exception.class, () -> streaming(live.models().standard()).get(3, TimeUnit.SECONDS));
            assertThrows(RuntimeException.class, () -> live.models().routing().chat(request()));
            assertEquals(1, calls.get());
            assertEquals(EvaluationBudget.StopReason.DEADLINE, budget.stopReason());
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void configurationCannotInheritBusinessCredentialsOrHideSecretsInUrl() {
        var budget = new EvaluationBudget(1, Duration.ofSeconds(10));
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationLiveModels.from(Map.of(), budget));
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationLiveModels.from(Map.of(
                "EVAL_MODEL_BASE_URL", "https://user:password@example.com/v1", "EVAL_MODEL_API_KEY", "x", "EVAL_MODEL_NAME", "model"), budget));
    }

    @Test void delayedTimeoutCannotAcceptExpiredBodyOrInterruptTheNextOperation() throws Exception {
        var received = new CountDownLatch(1);
        var releaseBody = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var finished = new CompletableFuture<Void>();
        var succeeded = new AtomicBoolean();
        var lateInterrupt = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(' ');
                exchange.getResponseBody().flush();
                received.countDown();
                releaseBody.await(6, TimeUnit.SECONDS);
                exchange.getResponseBody().write("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var budget = new EvaluationBudget(5, Duration.ofSeconds(20));
        var env = new java.util.HashMap<>(environment(server));
        env.put("EVAL_MODEL_TIMEOUT_SECONDS", "2");
        try (var live = EvaluationLiveModels.from(env, budget)) {
            Thread.startVirtualThread(() -> {
                try { live.models().primary().chat(request()); succeeded.set(true); }
                catch (RuntimeException expected) { /* 超时应在本次请求结束。 */ }
                returned.countDown();
                try { Thread.sleep(600); }
                catch (InterruptedException error) { lateInterrupt.set(true); }
                finally { finished.complete(null); }
            });
            assertTrue(received.await(3, TimeUnit.SECONDS));
            synchronized (budget) {
                // 放大计时线程与请求收尾的竞争：计时器不能把中断留给后续业务操作。
                Thread.sleep(2300);
                releaseBody.countDown();
                assertTrue(returned.await(3, TimeUnit.SECONDS));
            }
            finished.get(3, TimeUnit.SECONDS);
            assertFalse(succeeded.get(), "超过时限后才读完正文，不能返回成功");
            assertFalse(lateInterrupt.get(), "前一次请求计时器不能中断下一步操作");
        } finally { releaseBody.countDown(); server.stop(0); }
    }

    private static HttpServer server(AtomicInteger calls, int status) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean streaming = cn.hutool.json.JSONUtil.parseObj(request).getBool("stream", false);
                String body = streaming
                        ? "data: {\"id\":\"local\",\"model\":\"local\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        : "{\"id\":\"local\",\"model\":\"local\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"完成\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
                if (status == 307) exchange.getResponseHeaders().set("Location", "/unexpected-redirect");
                exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start(); return server;
    }
    private static Map<String, String> environment(HttpServer server) {
        return Map.of("EVAL_MODEL_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "EVAL_MODEL_API_KEY", "must-not-leak", "EVAL_MODEL_NAME", "local");
    }
    private static ChatRequest request() { return ChatRequest.builder().messages(UserMessage.from("协议检查")).build(); }
    private static CompletableFuture<ChatResponse> streaming(StreamingChatModel model) {
        var future = new CompletableFuture<ChatResponse>();
        try {
            model.chat(request(), new StreamingChatResponseHandler() {
                @Override public void onCompleteResponse(ChatResponse response) { future.complete(response); }
                @Override public void onError(Throwable error) { future.completeExceptionally(error); }
            });
        } catch (RuntimeException error) { future.completeExceptionally(error); }
        return future;
    }
}
