package com.xhl.aicodegenerate.evaluation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationCaseRunnerTest {
    @TempDir Path temporary;

    @AfterEach void allowCleanup() throws Exception {
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.toList()) if (!Files.isSymbolicLink(path)) path.toFile().setWritable(true, true);
        }
    }

    @Test void runsRealCasesAndPreservesFailureAndTraceGapSemantics() throws Exception {
        String url = System.getProperty("evaluation.test.mysql.url");
        assertNotNull(url, "必须显式提供隔离 MySQL 地址");
        var original = EvaluationDatabaseConfig.from(Map.of("EVAL_JDBC_URL", url, "EVAL_JDBC_USER", "root", "EVAL_JDBC_PASSWORD", ""));
        String schema = "generation_evaluation_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var admin = new JdbcTemplate(new DriverManagerDataSource(url.replace("/" + original.schema(), "/"), "root", ""));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
        var config = EvaluationDatabaseConfig.from(Map.of("EVAL_JDBC_URL", url.replace("/" + original.schema(), "/" + schema),
                "EVAL_JDBC_USER", "root", "EVAL_JDBC_PASSWORD", ""));
        try (var database = EvaluationDatabase.open(config, Path.of("."))) {
            var data = EvaluationDataset.load(Path.of("evaluation")).freeze(temporary.resolve("inputs"));
            var budget = new EvaluationBudget(200, Duration.ofMinutes(3));
            var runner = new EvaluationCaseRunner(database.dataSource(), temporary.resolve("runtime"),
                    temporary.resolve("cases"), data, budget);
            var reviewChecks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
            for (String id : new String[]{"B01", "B08", "E01", "E08", "E10", "E12"}) {
                var sample = sample(data, id);
                var fixture = new EvaluationFixtureModels(sample, data.fixture(sample));
                var result = runner.run(sample, fixture.models(budget), fixture);
                assertEquals(EvaluationBrowser.Status.PASS, result.verdict(), id + ": " + result.diagnostics());
                assertEquals(sample.expectedOutcome(), result.generationOutcome());
                assertTrue(result.evidenceComplete(), id + ": " + result.diagnostics());
                assertFalse(result.traces().isEmpty());
                assertEquals("E12".equals(id) ? "PARTIAL" : "COMPLETE", result.traceCompleteness());
                assertEquals(!java.util.Set.of("E08", "E12").contains(id), result.comparisonEligible(), "完整比较必须排除取消后未返回 Token 的调用");
                if (id.startsWith("B") || "E12".equals(id)) {
                    assertNotNull(result.artifact());
                    assertEquals("output", result.artifact().relativePath());
                    assertEquals(EvaluationBrowser.Status.PASS, result.browser().business());
                    assertTrue(Files.isRegularFile(temporary.resolve("cases").resolve(id).resolve("browser.png")));
                }
                if ("E08".equals(id)) assertTrue(fixture.streamCancelled());
                if ("B08".equals(id)) assertNotNull(result.initialSnapshotDigest());
                if ("modify".equals(sample.operation())) reviewChecks.add(() -> assertTrue(result.traces().stream()
                        .noneMatch(trace -> "CREATE".equals(trace.root().getTraceKind())), "修改用例从固定应用开始，不重复评测创建路由"));
                if ("E01".equals(id)) reviewChecks.add(() -> {
                    var steps = result.traces().stream().filter(trace -> "GENERATION".equals(trace.root().getTraceKind()))
                            .flatMap(trace -> trace.steps().stream()).toList();
                    var attempts = steps.stream().filter(step -> "native_generation".equals(step.getStepName())).toList();
                    assertEquals(2, attempts.size());
                    for (var attempt : attempts) assertEquals(1, steps.stream().filter(step -> "MODEL".equals(step.getStepKind())
                            && attempt.getSpanId().equals(step.getParentSpanId())).count(), "每次重生成应有各自的模型子步骤");
                });
            }
            var sample = sample(data, "B02");
            var fixture = new EvaluationFixtureModels(sample, data.fixture(sample));
            ChatModel wrongRoute = new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("MULTI_FILE")).build();
                }
            };
            var normal = fixture.models(budget);
            var result = runner.run(sample, EvaluationModelSet.budgeted(wrongRoute, wrongRoute,
                    normal.standard(), normal.reasoning(), budget), fixture);
            assertEquals("ROUTE_MISMATCH", result.generationOutcome());
            assertEquals(EvaluationBrowser.Status.FAIL, result.verdict());
            assertNull(result.taskId());
            assertEquals("multi_file", new JdbcTemplate(database.dataSource()).queryForObject(
                    "SELECT codeGenType FROM app WHERE id=?", String.class, result.appId()));
            var modify = sample(data, "B07");
            var modifyFixture = new EvaluationFixtureModels(modify, data.fixture(modify));
            var modifyModels = modifyFixture.models(budget);
            var modified = runner.run(modify, new EvaluationModelSet(wrongRoute, wrongRoute,
                    modifyModels.standard(), modifyModels.reasoning()), modifyFixture);
            reviewChecks.add(() -> assertEquals(EvaluationBrowser.Status.PASS, modified.verdict(), "修改不能受随机创建路由影响"));
            var shortBudget = new EvaluationBudget(100, Duration.ofSeconds(2));
            var slowFixture = new EvaluationFixtureModels(sample(data, "E07"), data.fixture(sample(data, "E07")));
            var shortRunner = new EvaluationCaseRunner(database.dataSource(), temporary.resolve("deadline-runtime"),
                    temporary.resolve("deadline-cases"), data, shortBudget);
            var deadline = shortRunner.run(sample(data, "B03"), slowFixture.models(shortBudget), slowFixture);
            assertEquals(EvaluationBudget.StopReason.DEADLINE, shortBudget.stopReason());
            assertEquals(EvaluationBrowser.Status.CANCELLED, deadline.verdict());
            assertFalse(deadline.comparisonEligible());
            assertTrue(slowFixture.streamCancelled(), "批次截止必须沿真实任务停止路径关闭未结束模型流");
            var routingBudget = new EvaluationBudget(10, Duration.ofSeconds(20));
            ChatModel cancelledRoute = new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    routingBudget.requestStop();
                    routingBudget.checkActive();
                    throw new AssertionError("停止后不得返回路由结果");
                }
            };
            var routingRunner = new EvaluationCaseRunner(database.dataSource(), temporary.resolve("routing-stop-runtime"),
                    temporary.resolve("routing-stop-cases"), data, routingBudget);
            var routingStopped = routingRunner.run(sample(data, "B01"), EvaluationModelSet.budgeted(cancelledRoute, cancelledRoute,
                    normal.standard(), normal.reasoning(), routingBudget), null);
            assertEquals(EvaluationBrowser.Status.CANCELLED, routingStopped.verdict(), "创建服务包装预算异常后，仍应保留主动停止语义");
            assertNull(routingStopped.appId());
            assertNull(routingStopped.taskId());
            assertFalse(routingStopped.comparisonEligible());
            budget.requestStop();
            var skippedSample = sample(data, "B02");
            var skippedFixture = new EvaluationFixtureModels(skippedSample, data.fixture(skippedSample));
            var skipped = runner.run(skippedSample, skippedFixture.models(budget), skippedFixture);
            assertEquals(EvaluationBrowser.Status.SKIPPED, skipped.verdict());
            assertNull(skipped.appId());
            assertEquals(0, skipped.modelCalls());
            assertAll(reviewChecks);
        } finally {
            admin.execute("DROP DATABASE " + schema);
        }
    }

    private EvaluationDataset.Case sample(EvaluationDataset dataset, String id) {
        return dataset.cases().stream().filter(item -> id.equals(item.caseId())).findFirst().orElseThrow();
    }
}
