package com.xhl.aicodegenerate.evaluation;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationOptionsTest {
    @Test void safeDefaultSelectsAllControlledCasesAndNeverEnablesLiveImplicitly() {
        var defaults = EvaluationOptions.parse(new String[0], Path.of("."));
        assertEquals(EvaluationOptions.Mode.FIXTURE, defaults.mode());
        assertEquals(EvaluationOptions.Suite.ALL, defaults.suite());
        assertEquals(EvaluationOptions.Command.RUN, defaults.command());
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationOptions.parse(
                new String[]{"--mode", "live"}, Path.of(".")));
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationOptions.parse(
                new String[]{"--max-model-calls", "30"}, Path.of(".")));
    }

    @Test void liveRequiresExplicitPositiveLimitsAndRejectsTyposDuplicatesAndOverflow() {
        var options = EvaluationOptions.parse(new String[]{"--mode", "live", "--max-model-calls", "30",
                "--deadline-seconds", "1200", "--suite", "holdout"}, Path.of("."));
        assertEquals(EvaluationOptions.Mode.LIVE, options.mode());
        assertEquals(30, options.maxModelCalls());
        assertEquals(1200, options.deadline().toSeconds());
        for (String[] invalid : new String[][]{
                {"--mode", "lvie"}, {"--mode", "fixture", "--mode", "live"}, {"--suite", "unknown"},
                {"--mode", "live", "--max-model-calls", "0", "--deadline-seconds", "10"},
                {"--mode", "live", "--max-model-calls", "1", "--deadline-seconds", "-1"},
                {"--mode", "live", "--max-model-calls", "1", "--deadline-seconds", "9223372036854775807"},
                {"--output"}, {"--api-key", "not-accepted"}}) {
            assertThrows(EvaluationConfigurationException.class, () -> EvaluationOptions.parse(invalid, Path.of(".")));
        }
    }

    @Test void compareIsReadOnlyAndRejectsGenerationFlagsOrMissingRun() {
        var compare = EvaluationOptions.parse(new String[]{"compare", "--baseline", "run-a", "--candidate", "run-b"}, Path.of("/tmp/evaluation"));
        assertEquals(EvaluationOptions.Command.COMPARE, compare.command());
        assertEquals(Path.of("/tmp/evaluation/run-a"), compare.baseline());
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationOptions.parse(
                new String[]{"compare", "--baseline", "run-a"}, Path.of(".")));
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationOptions.parse(
                new String[]{"compare", "--baseline", "a", "--candidate", "b", "--mode", "live"}, Path.of(".")));
    }
}
