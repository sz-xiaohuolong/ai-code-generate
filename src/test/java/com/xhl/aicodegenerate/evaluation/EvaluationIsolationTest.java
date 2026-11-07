package com.xhl.aicodegenerate.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationIsolationTest {
    @TempDir Path root;

    @Test void databaseConfigurationCannotFallBackToBusinessDatabaseOrUrlSecrets() {
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationDatabaseConfig.from(Map.of()));
        for (String url : new String[]{"jdbc:mysql://localhost:3306/ai_code_generate", "jdbc:mysql://localhost:3306/generation_trace_test",
                "jdbc:mysql://user:secret@localhost:3306/generation_evaluation", "jdbc:mysql://localhost/generation_evaluation?password=secret",
                "jdbc:mysql://localhost/generation_evaluation?allowMultiQueries=true", "jdbc:mysql://localhost/generation_evaluation/extra"}) {
            assertThrows(EvaluationConfigurationException.class, () -> EvaluationDatabaseConfig.from(Map.of("EVAL_JDBC_URL", url, "EVAL_JDBC_USER", "eval", "EVAL_JDBC_PASSWORD", "")));
        }
        var config = EvaluationDatabaseConfig.from(Map.of("EVAL_JDBC_URL", "jdbc:mysql://127.0.0.1:23316/generation_evaluation?useSSL=false&allowPublicKeyRetrieval=true", "EVAL_JDBC_USER", "root", "EVAL_JDBC_PASSWORD", "must-not-print"));
        assertEquals("generation_evaluation", config.schema());
        assertFalse(config.toString().contains("must-not-print"));
    }

    @Test void everyRunGetsNewDirectoryAndPublicPathsAndSymlinksAreRejected() throws Exception {
        Path repo = Files.createDirectories(root.resolve("repo"));
        Path output = repo.resolve(".evidence/evaluation");
        var first = EvaluationWorkspace.create(repo, output);
        var second = EvaluationWorkspace.create(repo, output);
        assertNotEquals(first.runId(), second.runId());
        assertTrue(Files.isDirectory(first.runtime()));
        assertNotEquals(first.directory(), second.directory());
        Path deployment = Files.createDirectories(repo.resolve("tmp/code_deploy"));
        for (Path bad : new Path[]{repo, deployment, repo.resolve("ai-code-frontend/public/reports"), repo.resolve("evaluation/results")}) {
            assertThrows(EvaluationConfigurationException.class, () -> EvaluationWorkspace.create(repo, bad));
        }
        Path link = repo.resolve("linked-output"); Files.createSymbolicLink(link, deployment);
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationWorkspace.create(repo, link));
    }

    @Test void redisIsOwnedEphemeralProcessAndClosesWithoutTouchingOtherInstances() throws Exception {
        try (var redis = EvaluationRedis.start(root.resolve("redis"))) {
            assertTrue(ProcessHandle.of(redis.processId()).orElseThrow().isAlive());
            redis.template().opsForValue().set("isolation-check", "owned");
            assertEquals("owned", redis.template().opsForValue().get("isolation-check"));
            try (var second = EvaluationRedis.start(root.resolve("redis-second"))) {
                assertNotEquals(redis.port(), second.port());
                assertNull(second.template().opsForValue().get("isolation-check"));
                long pid = second.processId(); second.close();
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            }
            assertEquals("owned", redis.template().opsForValue().get("isolation-check"));
        }
    }
}
