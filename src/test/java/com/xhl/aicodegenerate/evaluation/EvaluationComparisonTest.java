package com.xhl.aicodegenerate.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.xhl.aicodegenerate.evaluation.EvaluationBrowser.Status;

class EvaluationComparisonTest {
    @TempDir Path temporary;

    @Test void comparesFixedCasesButKeepsUsageGapsAndConfigurationChangesExplicit() throws Exception {
        Path baseline = create("baseline", "data", "assertions", "tree", "model-a", true);
        Path candidate = create("candidate", "data", "assertions", "tree", "model-b", false);
        var before = directoryDigests(baseline);
        var comparison = EvaluationComparison.compare(baseline, candidate);
        assertEquals(1, comparison.cases().size());
        var item = comparison.cases().getFirst();
        assertTrue(item.qualityComparable());
        assertTrue(item.durationComparable());
        assertFalse(item.usageComparable());
        assertTrue(item.reasons().stream().anyMatch(reason -> reason.contains("Token")));
        assertTrue(comparison.changes().stream().anyMatch(change -> change.contains("模型")));
        assertEquals(before, directoryDigests(baseline), "比较只能读取输入报告");
    }

    @Test void changedDataAssertionsAndSourceCannotBePresentedAsStrictlyComparable() throws Exception {
        Path baseline = create("baseline", "data", "assertions", "tree", "model", true);
        Path candidate = create("candidate", "data-2", "assertions-2", "tree-2", "model", true);
        var comparison = EvaluationComparison.compare(baseline, candidate);
        assertFalse(comparison.cases().getFirst().qualityComparable());
        assertFalse(comparison.cases().getFirst().durationComparable());
        assertTrue(comparison.changes().stream().anyMatch(change -> change.contains("数据集")));
        assertTrue(comparison.changes().stream().anyMatch(change -> change.contains("源码")));
    }

    @Test void missingCompletionTamperedEvidenceAndEscapingPathsAreRejected() throws Exception {
        Path baseline = create("baseline", "data", "assertions", "tree", "model", true);
        Path candidate = create("candidate", "data", "assertions", "tree", "model", true);
        Files.writeString(candidate.resolve("cases/B01/browser.png"), "被修改的证据");
        assertThrows(java.io.IOException.class, () -> EvaluationComparison.compare(baseline, candidate));
        Files.delete(candidate.resolve("complete.json"));
        assertThrows(java.io.IOException.class, () -> EvaluationComparison.compare(baseline, candidate));
        var completion = EvaluationReport.JSON.readTree(baseline.resolve("complete.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)completion.get("files")).put("../private.txt", "hash");
        Files.writeString(baseline.resolve("complete.json"), completion.toString());
        assertThrows(java.io.IOException.class, () -> EvaluationComparison.compare(baseline, baseline));
    }

    @Test void unexecutedSampleHasNoComparableQualityEvenWhenItsSpecificationMatches() throws Exception {
        Path baseline = create("baseline", "data", "assertions", "tree", "model", true);
        Path candidate = create("candidate", "data", "assertions", "tree", "model", false, Status.SKIPPED);
        var comparison = EvaluationComparison.compare(baseline, candidate);
        assertFalse(comparison.cases().getFirst().qualityComparable());
        assertFalse(comparison.cases().getFirst().durationComparable());
    }

    private Path create(String name, String data, String assertions, String tree, String model, boolean usage) throws Exception {
        return create(name, data, assertions, tree, model, usage, Status.PASS);
    }

    private Path create(String name, String data, String assertions, String tree, String model, boolean usage, Status verdict) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name));
        var manifest = new EvaluationReport.Manifest("1", UUID.randomUUID().toString(), "fixture", "all", "2026-11-07T00:00:00Z",
                data, assertions, new EvaluationReport.Source("commit", tree, true, false, "未保存受测源码"), "prompt", Map.of("model", model), Map.of("java", "21"), 100, 60, List.of("B01"), List.of());
        var report = new EvaluationReport(root, manifest);
        Path output = Files.createDirectories(root.resolve("cases/B01/output"));
        Files.writeString(output.resolve("index.html"), "<p>比较测试产物</p>");
        Files.writeString(output.getParent().resolve("browser.png"), "受控截图文件");
        var trace = com.xhl.aicodegenerate.entity.GenerationTrace.builder().traceId("trace").traceKind("GENERATION")
                .recordingStatus("COMPLETE").executionStatus("SUCCEEDED").modelCalls(1)
                .inputReportedCalls(usage ? 1 : 0).outputReportedCalls(1).knownInputTokens(usage ? 11L : null).knownOutputTokens(7L).build();
        var sample = EvaluationDataset.load(Path.of("evaluation")).cases().getFirst();
        var result = new EvaluationCaseRunner.Result(sample, "request", 1L, "task", "SUCCEEDED", Status.NOT_APPLICABLE,
                new EvaluationBrowser.Result(Status.PASS, Status.PASS, List.of(), true, "156", "browser.png", List.of()),
                verdict, "COMPLETE", true, verdict == Status.PASS, 120, 1, null,
                new EvaluationCaseRunner.Artifact("output", com.xhl.aicodegenerate.observability.TraceArtifacts.digest(output), 1, 20, false),
                List.of(new EvaluationCaseRunner.TraceEvidence(trace, List.of())), List.of());
        report.writeCase(result); report.finish(List.of(result), 1, null);
        return root;
    }

    private Map<String, String> directoryDigests(Path root) throws Exception {
        Map<String, String> result = new java.util.TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) result.put(root.relativize(path).toString(), EvaluationReport.fileDigest(path));
        }
        return result;
    }
}
