package com.xhl.aicodegenerate.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationProvenanceTest {
    @TempDir Path temporary;

    @Test void fingerprintsActualSourceWithoutExportingLocalSecretsAndMarksDirtyTree() throws Exception {
        git("init", "-q");
        Files.createDirectories(temporary.resolve("src/main/java"));
        Files.writeString(temporary.resolve("src/main/java/App.java"), "class App {}\n");
        Files.writeString(temporary.resolve(".gitignore"), "application-local.yml\n.evidence/\n");
        Files.writeString(temporary.resolve("application-local.yml"), "api-key: SECRET_MUST_NOT_APPEAR\n");
        git("add", ".");
        git("-c", "user.name=评测测试", "-c", "user.email=evaluation@example.invalid", "commit", "-qm", "受控初始版本");
        var clean = EvaluationProvenance.source(temporary);
        assertFalse(clean.dirty());
        assertTrue(clean.recoverable());
        assertEquals(64, clean.treeDigest().length());
        Files.writeString(temporary.resolve("application-local.yml"), "api-key: ANOTHER_SECRET\n");
        assertEquals(clean.treeDigest(), EvaluationProvenance.source(temporary).treeDigest());
        Files.writeString(temporary.resolve("src/main/java/App.java"), "class App { int value; }\n");
        var changed = EvaluationProvenance.source(temporary);
        assertTrue(changed.dirty());
        assertFalse(changed.recoverable());
        assertNotEquals(clean.treeDigest(), changed.treeDigest());
        assertFalse(EvaluationReport.JSON.writeValueAsString(changed).contains("SECRET"));
        assertTrue(changed.limitation().contains("未保存"));
        Files.writeString(temporary.resolve("README.md"), "文档变化不改源代码摘要");
        assertEquals(changed.treeDigest(), EvaluationProvenance.source(temporary).treeDigest());
    }

    private void git(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>(); command.add("git"); command.addAll(java.util.List.of(arguments));
        var process = new ProcessBuilder(command).directory(temporary.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
