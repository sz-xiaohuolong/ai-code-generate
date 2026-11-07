package com.xhl.aicodegenerate.evaluation;

import com.xhl.aicodegenerate.observability.TraceArtifacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationArtifactsTest {
    @TempDir Path temporary;

    @AfterEach void allowTemporaryCleanup() throws Exception {
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.toList()) {
                if (!Files.isSymbolicLink(path)) path.toFile().setWritable(true, true);
            }
        }
    }

    @Test void snapshotPreservesTraceDigestExcludesDependenciesAndBecomesReadOnly() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("index.html"), "<p>原始产物</p>");
        Files.createDirectories(source.resolve("dist"));
        Files.writeString(source.resolve("dist/index.html"), "<p>构建产物</p>");
        Files.createDirectories(source.resolve("node_modules/pkg"));
        Files.writeString(source.resolve("node_modules/pkg/ignored.js"), "依赖不进入快照");
        Files.createDirectories(source.resolve(".git"));
        Files.writeString(source.resolve(".git/config"), "版本库不进入快照");
        String before = TraceArtifacts.digest(source);
        Path target = temporary.resolve("snapshot");
        var snapshot = EvaluationArtifacts.freeze(source, target);
        assertEquals(before, snapshot.digest());
        assertEquals(before, TraceArtifacts.digest(target));
        assertEquals(2, snapshot.files());
        assertFalse(Files.exists(target.resolve("node_modules")));
        assertFalse(Files.exists(target.resolve(".git")));
        Files.writeString(source.resolve("index.html"), "后续应用编辑");
        assertEquals("<p>原始产物</p>", Files.readString(target.resolve("index.html")));
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target.resolve("index.html"));
            assertFalse(permissions.contains(PosixFilePermission.OWNER_WRITE));
            assertFalse(Files.getPosixFilePermissions(target).contains(PosixFilePermission.OWNER_WRITE));
        }
        assertThrows(java.io.IOException.class, () -> EvaluationArtifacts.freeze(source, target));
    }

    @Test void linksAndMissingSourceCannotProduceSuccessfulSnapshot() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("linked"));
        Files.writeString(source.resolve("index.html"), "<p>页面</p>");
        Path privateFile = temporary.resolve("private.txt");
        Files.writeString(privateFile, "不可进入产物的文件");
        Files.createSymbolicLink(source.resolve("secret.txt"), privateFile);
        Path target = temporary.resolve("rejected");
        assertThrows(java.io.IOException.class, () -> EvaluationArtifacts.freeze(source, target));
        assertFalse(Files.exists(target));
        assertThrows(java.io.IOException.class, () -> EvaluationArtifacts.freeze(temporary.resolve("missing"), target));
    }
}
