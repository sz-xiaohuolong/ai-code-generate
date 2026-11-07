package com.xhl.aicodegenerate.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationDatasetTest {
    @TempDir Path temporary;

    @org.junit.jupiter.api.AfterEach void allowTemporaryCleanup() throws Exception {
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.toList()) {
                if (!Files.isSymbolicLink(path)) path.toFile().setWritable(true, true);
            }
        }
    }

    @Test void allTwentyFourCasesMatchFrozenSplitsAndLiveExclusions() throws Exception {
        var data = EvaluationDataset.load(Path.of("evaluation"));
        assertEquals(24, data.cases().size());
        assertEquals(18, data.select(EvaluationOptions.Mode.FIXTURE, EvaluationOptions.Suite.DEVELOPMENT).selected().size());
        assertEquals(Set.of("B02", "B04", "B06", "B07", "B09", "B11"), data.select(EvaluationOptions.Mode.FIXTURE,
                EvaluationOptions.Suite.HOLDOUT).selected().stream().map(EvaluationDataset.Case::caseId).collect(Collectors.toSet()));
        var live = data.select(EvaluationOptions.Mode.LIVE, EvaluationOptions.Suite.ALL);
        assertEquals(12, live.selected().size()); assertEquals(12, live.excluded().size());
        assertTrue(live.excluded().stream().allMatch(item -> item.reason().contains("工程")));
        assertEquals(64, data.datasetDigest().length()); assertEquals(64, data.assertionDigest().length());
        for (var sample : data.cases()) {
            assertFalse(data.assertions(sample).checks().isEmpty() && "business".equals(sample.category()));
            if ("modify".equals(sample.operation())) {
                assertNotNull(sample.initialArtifactDigest()); assertFalse(sample.initialPrompt().isBlank());
                assertTrue(Files.isRegularFile(data.initialArtifact(sample).resolve("index.html")));
            }
        }
    }

    @Test void duplicateIdsTamperingAndPathsOutsideDatasetFailBeforeExecution() throws Exception {
        Path root = copyDataset();
        var json = new ObjectMapper();
        ObjectNode manifest = (ObjectNode) json.readTree(root.resolve("suite.json").toFile());
        ((ObjectNode) manifest.withArray("cases").get(1)).put("caseId", "B01");
        json.writeValue(root.resolve("suite.json").toFile(), manifest);
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationDataset.load(root));
        Files.copy(Path.of("evaluation/suite.json"), root.resolve("suite.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(root.resolve("fixtures/counter/base/index.html"), "篡改后的受控产物");
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationDataset.load(root));
        manifest = (ObjectNode) json.readTree(root.resolve("suite.json").toFile());
        ((ObjectNode) manifest.withArray("cases").get(0)).put("fixtureRef", "../private");
        json.writeValue(root.resolve("suite.json").toFile(), manifest);
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationDataset.load(root));
    }

    @Test void symlinkAndExecutableAssertionsCannotEnterSuite() throws Exception {
        Path root = copyDataset();
        Path target = root.resolve("assertions/B01.json");
        Files.delete(target);
        Path external = temporary.resolve("external.json"); Files.writeString(external, "{}");
        Files.createSymbolicLink(target, external);
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationDataset.load(root));
        Files.delete(target);
        Files.writeString(target, "{\"version\":\"1\",\"checks\":[{\"action\":\"executeScript\",\"selector\":\"body\",\"value\":\"arbitrary()\"}],\"engineering\":null}");
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationDataset.load(root));
    }

    @Test void frozenInputsRemainIndependentAndRejectChangedSource() throws Exception {
        Path root = copyDataset();
        var original = EvaluationDataset.load(root);
        var frozen = original.freeze(temporary.resolve("frozen"));
        assertEquals(original.datasetDigest(), frozen.datasetDigest());
        assertEquals(original.assertionDigest(), frozen.assertionDigest());
        assertNotEquals(original.fixture(original.cases().getFirst()), frozen.fixture(frozen.cases().getFirst()));
        Files.writeString(root.resolve("fixtures/counter/base/index.html"), "后续编辑");
        assertTrue(Files.readString(frozen.fixture(frozen.cases().getFirst()).resolve("index.html")).contains("data-testid"));
        assertThrows(EvaluationConfigurationException.class, () -> original.freeze(temporary.resolve("changed")));
        assertThrows(EvaluationConfigurationException.class, () -> frozen.freeze(temporary.resolve("frozen")));
        assertThrows(UnsupportedOperationException.class, () -> frozen.cases().getFirst().allowedExecutionModes().clear());
    }

    private Path copyDataset() throws Exception {
        Path target = temporary.resolve("dataset");
        try (var files = Files.walk(Path.of("evaluation"))) {
            for (Path file : files.toList()) {
                Path copy = target.resolve(Path.of("evaluation").relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(copy);
                else Files.copy(file, copy);
            }
        }
        return target;
    }
}
