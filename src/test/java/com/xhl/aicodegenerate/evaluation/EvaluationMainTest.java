package com.xhl.aicodegenerate.evaluation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationMainTest {
    @TempDir Path temporary;

    @AfterEach void allowCleanup() throws Exception {
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.toList()) if (!Files.isSymbolicLink(path)) path.toFile().setWritable(true, true);
        }
    }

    @Test void helpAndInvalidLiveConfigurationNeedNoDatabaseOrProvider() {
        var output = new ByteArrayOutputStream(); var error = new ByteArrayOutputStream();
        assertEquals(0, EvaluationMain.execute(new String[]{"--help"}, Path.of("."), Map.of(), new PrintStream(output), new PrintStream(error)));
        assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("EVAL_JDBC_URL"));
        assertEquals(3, EvaluationMain.execute(new String[]{"--mode", "live"}, Path.of("."), Map.of("EVAL_MODEL_API_KEY", "SENSITIVE_MARKER"), new PrintStream(output), new PrintStream(error)));
        assertEquals(3, EvaluationMain.execute(new String[]{"compare", "--baseline", temporary.resolve("missing").toString(), "--candidate", temporary.resolve("missing").toString()},
                Path.of("."), Map.of(), new PrintStream(output), new PrintStream(error)));
        assertFalse(error.toString(java.nio.charset.StandardCharsets.UTF_8).contains("SENSITIVE_MARKER"));
    }

    @Test void modelBudgetStopsBatchAndReportsAllUnexecutedSamplesWithoutTouchingBusinessConfig() throws Exception {
        String url = System.getProperty("evaluation.test.mysql.url"); assertNotNull(url);
        var original = EvaluationDatabaseConfig.from(Map.of("EVAL_JDBC_URL", url, "EVAL_JDBC_USER", "root", "EVAL_JDBC_PASSWORD", ""));
        String schema = "generation_evaluation_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var admin = new JdbcTemplate(new DriverManagerDataSource(url.replace("/" + original.schema(), "/"), "root", ""));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
        try {
            var environment = Map.of("EVAL_JDBC_URL", url.replace("/" + original.schema(), "/" + schema), "EVAL_JDBC_USER", "root", "EVAL_JDBC_PASSWORD", "",
                    "EVAL_MODEL_API_KEY", "IGNORED_FIXTURE_SECRET", "EVAL_MODEL_BASE_URL", "https://must-not-call.invalid/v1");
            var out = new ByteArrayOutputStream(); var error = new ByteArrayOutputStream();
            int code = EvaluationMain.execute(new String[]{"--output", temporary.resolve("runs").toString(), "--max-model-calls", "1", "--deadline-seconds", "30"},
                    Path.of("."), environment, new PrintStream(out), new PrintStream(error));
            assertEquals(2, code, error.toString(java.nio.charset.StandardCharsets.UTF_8));
            Path run;
            try (var paths = Files.list(temporary.resolve("runs"))) { run = paths.findFirst().orElseThrow(); }
            var report = EvaluationComparison.read(run);
            assertEquals(24, report.cases().size());
            assertEquals(1, report.summary().modelCalls());
            assertEquals(23, report.cases().stream().filter(result -> result.verdict() == EvaluationBrowser.Status.SKIPPED).count());
            assertTrue(report.cases().stream().skip(1).allMatch(result -> result.appId() == null && result.modelCalls() == 0));
            assertFalse(Files.readString(run.resolve("run.json")).contains("IGNORED_FIXTURE_SECRET"));
            assertFalse(Files.readString(run.resolve("report.json")).contains("must-not-call.invalid"));
            assertTrue(Files.isRegularFile(run.resolve("complete.json")));
            Path blocked = temporary.resolve("not-a-directory");
            Files.writeString(blocked, "保留已有文件");
            assertEquals(3, EvaluationMain.execute(new String[]{"--output", blocked.toString()}, Path.of("."), environment,
                    new PrintStream(out), new PrintStream(error)), "输出入口无效应在启动校验阶段拒绝");
            assertEquals("保留已有文件", Files.readString(blocked));

            Path failingOutput = temporary.resolve("report-write-failure");
            var conflictInjected = new java.util.concurrent.atomic.AtomicBoolean();
            var failingOut = new PrintStream(new ByteArrayOutputStream()) {
                @Override public void println(String message) {
                    if (message.startsWith("评测运行：")) {
                        try (var paths = Files.list(failingOutput)) {
                            Files.createDirectory(paths.findFirst().orElseThrow().resolve("report.html"));
                            conflictInjected.set(true);
                        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    }
                }
            };
            assertEquals(2, EvaluationMain.execute(new String[]{"--output", failingOutput.toString(), "--max-model-calls", "1", "--deadline-seconds", "30"},
                    Path.of("."), environment, failingOut, new PrintStream(error)), "运行中报告写入失败必须返回未完整完成");
            assertTrue(conflictInjected.get());
            Path incomplete;
            try (var paths = Files.list(failingOutput)) { incomplete = paths.findFirst().orElseThrow(); }
            assertFalse(Files.exists(incomplete.resolve("complete.json")));
            assertTrue(Files.isRegularFile(incomplete.resolve("cases/B01/result.json")), "已取得的逐用例证据不能被清空");
        } finally { admin.execute("DROP DATABASE " + schema); }
    }
}
