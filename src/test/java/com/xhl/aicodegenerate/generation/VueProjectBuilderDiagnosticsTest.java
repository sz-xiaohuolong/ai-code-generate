package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class VueProjectBuilderDiagnosticsTest {
    @TempDir Path root;
    void project(String script) throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"name\":\"p0-fixture\",\"version\":\"1.0.0\",\"scripts\":{\"build\":\"node build.cjs\"}}");
        Files.writeString(root.resolve("build.cjs"), script);
    }
    @Test void capturesCompilerDiagnosticsWithoutBlockingOnLargeOutput() throws Exception {
        project("console.log('x'.repeat(200000)); console.error('src/App.vue:4:1 Unexpected closing tag'); process.exit(1)");
        try (var run = new GenerationExecution("诊断测试", root, Duration.ofSeconds(30), () -> true)) {
            var result = new VueProjectBuilder().buildDetailed(root, run);
            assertFalse(result.success()); assertEquals("build", result.stage()); assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("App.vue:4:1")); assertTrue(result.stdout().length() <= 16384);
            assertEquals(BuildResult.Kind.CODE, result.kind());
        }
    }
    @Test void successfulExitWithoutFreshDistIsFailure() throws Exception {
        project("console.log('假成功')");
        Files.createDirectories(root.resolve("dist")); Files.writeString(root.resolve("dist/index.html"), "旧产物");
        var result = new VueProjectBuilder().buildDetailed(root, null);
        assertFalse(result.success()); assertTrue(result.diagnostic().contains("dist/index.html"));
    }
    @Test void successfulBuildCreatesFreshArtifact() throws Exception {
        project("require('fs').mkdirSync('dist',{recursive:true}); require('fs').writeFileSync('dist/index.html','新页面')");
        assertTrue(new VueProjectBuilder().buildDetailed(root, null).success());
        assertEquals("新页面", Files.readString(root.resolve("dist/index.html")));
    }
}
