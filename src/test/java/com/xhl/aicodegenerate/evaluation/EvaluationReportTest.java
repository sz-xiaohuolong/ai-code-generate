package com.xhl.aicodegenerate.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.xhl.aicodegenerate.evaluation.EvaluationBrowser.Status;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationReportTest {
    @TempDir Path temporary;
    private final EvaluationDataset dataset = EvaluationDataset.load(Path.of("evaluation"));

    @Test void groupsExpectedFaultsAndEscapesUntrustedTextWithoutDroppingDenominators() throws Exception {
        var run = manifest(List.of("B01", "B02", "E11"));
        var report = new EvaluationReport(temporary, run);
        var good = result("B01", Status.PASS, "COMPLETE", true, true, List.of());
        var bad = result("B02", Status.FAIL, "COMPLETE", true, true, List.of("<script>alert('x')</script> & 错误"));
        var partial = result("E11", Status.PASS, "PARTIAL", true, false, List.of("预期追踪写入失败"));
        var cases = List.of(good, bad, partial);
        for (var item : cases) report.writeCase(item);
        var outcome = report.finish(cases, 9, null);
        assertEquals(1, outcome.exitCode());
        assertEquals(2, outcome.summary().business().planned());
        assertEquals(1, outcome.summary().business().failed());
        assertEquals(1, outcome.summary().engineering().passed());
        assertEquals(1, outcome.summary().engineering().traceMissing());
        assertEquals(0, outcome.summary().engineering().comparisonEligible());
        assertEquals("FIXTURE_PROTOCOL", outcome.summary().usageSource());
        String html = Files.readString(temporary.resolve("report.html"));
        assertFalse(html.contains("<script>alert"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("受控回归"));
        assertTrue(html.contains("B02"));
        assertTrue(Files.isRegularFile(temporary.resolve("complete.json")));
        var json = new ObjectMapper().readTree(temporary.resolve("report.json").toFile());
        assertEquals(3, json.path("cases").size());
        assertEquals(3, json.path("cases").get(0).path("usage").path("actualCalls").asLong());
        assertTrue(json.path("cases").get(0).path("usage").path("knownInputTokens").isNull());
        assertEquals(3, json.path("summary").path("generationOutcomes").path("SUCCEEDED").asInt());
        assertThrows(java.io.IOException.class, () -> report.finish(cases, 9, null));
    }

    @Test void missingEvidenceAndUnexecutedCasesRemainVisibleWithIncompleteExitCode() throws Exception {
        var report = new EvaluationReport(temporary, manifest(List.of("B01", "B02")));
        var missing = result("B01", Status.PASS, "PARTIAL", false, false, List.of("必要证据缺失"));
        var skipped = result("B02", Status.SKIPPED, "UNAVAILABLE", true, false, List.of("总时限已到"));
        report.writeCase(missing); report.writeCase(skipped);
        var outcome = report.finish(List.of(missing, skipped), 1, "DEADLINE");
        assertEquals(2, outcome.exitCode());
        assertEquals(2, outcome.summary().business().planned());
        assertEquals(1, outcome.summary().business().skipped());
        assertEquals(1, outcome.summary().business().evidenceMissing());
        var completion = new ObjectMapper().readTree(temporary.resolve("complete.json").toFile());
        assertFalse(completion.path("evidenceComplete").asBoolean());
        assertEquals("DEADLINE", completion.path("stopReason").asText());
    }

    @Test void writeFailureCannotLeaveACompleteManifestOrOverwriteExistingResults() throws Exception {
        var report = new EvaluationReport(temporary, manifest(List.of("B01")));
        var result = result("B01", Status.PASS, "COMPLETE", true, true, List.of());
        report.writeCase(result);
        assertThrows(java.io.IOException.class, () -> report.writeCase(result));
        Files.createDirectory(temporary.resolve("report.html"));
        assertThrows(java.io.IOException.class, () -> report.finish(List.of(result), 1, null));
        assertFalse(Files.exists(temporary.resolve("complete.json")));
        assertTrue(Files.isRegularFile(temporary.resolve("cases/B01/result.json")));
    }

    @Test void claimsOfEvidenceAreCheckedAgainstActualFilesAndAllSelectedCasesAreRequired() throws Exception {
        var report = new EvaluationReport(temporary, manifest(List.of("B01", "B02")));
        var result = result("B01", Status.PASS, "COMPLETE", true, true, List.of());
        report.writeCase(result);
        assertThrows(java.io.IOException.class, () -> report.finish(List.of(result), 1, null));
        assertFalse(Files.exists(temporary.resolve("complete.json")));
        Files.delete(temporary.resolve("cases/B01/result.json"));
        assertThrows(java.io.IOException.class, () -> report.finish(List.of(result,
                result("B02", Status.SKIPPED, "UNAVAILABLE", true, false, List.of())), 1, null));
    }

    @Test void distributionsKeepUnknownSamplesOutAndReportOddEvenMediansByGroup() throws Exception {
        var cases = List.of(
                measured("B01", Status.PASS, "COMPLETE", 10, 2L),
                measured("B02", Status.FAIL, "COMPLETE", 20, 6L),
                measured("B03", Status.PASS, "COMPLETE", 30, null),
                measured("B04", Status.PASS, "PARTIAL", 999, 999L),
                measured("B05", Status.SKIPPED, "UNAVAILABLE", 0, null),
                measured("E01", Status.PASS, "COMPLETE", 100, null),
                measured("E02", Status.PASS, "COMPLETE", 201, null));
        var report = new EvaluationReport(temporary, manifest(cases.stream().map(c -> c.sample().caseId()).toList()));
        for (var item : cases) report.writeCase(item);
        report.finish(cases, 18, null);
        var statistics = new ObjectMapper().readTree(temporary.resolve("report.json").toFile()).path("statistics");
        var business = statistics.path("business");
        assertEquals(3, business.path("durationMs").path("samples").asInt());
        assertEquals(10, business.path("durationMs").path("min").asLong());
        assertEquals(30, business.path("durationMs").path("max").asLong());
        assertEquals(20, business.path("durationMs").path("median").asDouble());
        assertEquals(2, business.path("inputTokens").path("samples").asInt());
        assertEquals(4, business.path("inputTokens").path("median").asDouble());
        assertEquals(8, business.path("outputTokens").path("median").asDouble());
        assertEquals(2, business.path("modelCalls").path("samples").asInt());
        assertEquals(3, business.path("modelCalls").path("median").asDouble());
        var engineering = statistics.path("engineering");
        assertEquals(150.5, engineering.path("durationMs").path("median").asDouble());
        assertEquals(0, engineering.path("inputTokens").path("samples").asInt());
        assertTrue(engineering.path("inputTokens").path("median").isNull());
        assertTrue(engineering.path("inputTokens").path("min").isNull());
        assertTrue(engineering.path("inputTokens").path("max").isNull());
        String html = Files.readString(temporary.resolve("report.html"));
        assertTrue(html.contains("范围与中位数"));
        assertTrue(html.contains("150.5"));
        assertTrue(html.contains("有效样本"));
    }

    private EvaluationCaseRunner.Result measured(String id, Status verdict, String completeness, long duration, Long tokens) throws Exception {
        var item = result(id, verdict, completeness, true, tokens != null, List.of());
        var root = com.xhl.aicodegenerate.entity.GenerationTrace.builder().modelCalls(3)
                .inputReportedCalls(tokens == null ? 0 : 3).outputReportedCalls(tokens == null ? 0 : 3)
                .knownInputTokens(tokens).knownOutputTokens(tokens == null ? null : tokens * 2).build();
        return new EvaluationCaseRunner.Result(item.sample(), item.requestId(), item.appId(), item.taskId(),
                item.generationOutcome(), item.build(), item.browser(), item.verdict(), item.traceCompleteness(),
                item.evidenceComplete(), item.comparisonEligible(), duration, item.modelCalls(), item.initialSnapshotDigest(),
                item.artifact(), List.of(new EvaluationCaseRunner.TraceEvidence(root, List.of())), item.diagnostics());
    }

    private EvaluationReport.Manifest manifest(List<String> ids) {
        return new EvaluationReport.Manifest("1", UUID.randomUUID().toString(), "fixture", "all", "2026-11-07T00:00:00Z",
                dataset.datasetDigest(), dataset.assertionDigest(), new EvaluationReport.Source("abc123", "tree", true, false,
                "未保存可恢复的受测源码快照"), "prompt", Map.of("model", "fixture"), Map.of("java", "21"), 100, 60, ids, List.of());
    }
    private EvaluationCaseRunner.Result result(String id, Status verdict, String trace, boolean evidence, boolean comparable, List<String> messages) throws Exception {
        var sample = dataset.cases().stream().filter(item -> id.equals(item.caseId())).findFirst().orElseThrow();
        EvaluationCaseRunner.Artifact artifact = null;
        EvaluationBrowser.Result browser = null;
        if (verdict != Status.SKIPPED) {
            Path directory = Files.createDirectories(temporary.resolve("cases").resolve(id).resolve("output"));
            Files.writeString(directory.resolve("index.html"), "<p>报告测试夹具</p>");
            artifact = new EvaluationCaseRunner.Artifact("output", com.xhl.aicodegenerate.observability.TraceArtifacts.digest(directory), 1, 29, false);
            Files.write(directory.getParent().resolve("browser.png"), new byte[]{1, 2, 3});
            browser = new EvaluationBrowser.Result(Status.PASS, verdict, List.of(), evidence, "test", "browser.png", List.of());
        }
        return new EvaluationCaseRunner.Result(sample, UUID.randomUUID().toString(), 1L, "task", verdict == Status.SKIPPED ? "NOT_RUN" : "SUCCEEDED", Status.NOT_APPLICABLE,
                browser, verdict, trace, evidence, comparable, 12, 3, null, artifact, List.of(), messages);
    }
}
