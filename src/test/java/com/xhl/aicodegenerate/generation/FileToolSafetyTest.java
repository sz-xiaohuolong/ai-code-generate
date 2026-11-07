package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.ai.tools.FileModifyTool;
import com.xhl.aicodegenerate.ai.tools.FileWriteTool;
import com.xhl.aicodegenerate.constant.AppConstant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileToolSafetyTest {
    @TempDir Path outside;

    @Test void traceRecordsOutcomesWithoutReadingBodyAsAnErrorOrSavingIt() throws Exception {
        var steps = new java.util.concurrent.CopyOnWriteArrayList<com.xhl.aicodegenerate.entity.GenerationTraceStep>();
        Files.writeString(outside.resolve("content.txt"), "错误：这只是文件正文 secret-value");
        try (var recorder = new com.xhl.aicodegenerate.observability.GenerationTraceRecorder((trace, step) -> {
            if (step != null) { steps.removeIf(old -> old.getSpanId().equals(step.getSpanId())); steps.add(step); }
        }, java.time.Clock.systemUTC(), 64);
             var run = new GenerationExecution("file-trace", outside, java.time.Duration.ofSeconds(5), () -> true)) {
            run.attachTrace(recorder.startGeneration(run.id(), 1L, 1L, "standard", "vue_project"));
            var id = new AppChatMemoryId(1L, 1L, run.id());
            var reader = new com.xhl.aicodegenerate.ai.tools.FileReadTool();
            assertTrue(reader.readFile("content.txt", id).contains("secret-value"));
            reader.readFile("missing.txt", id);
            new FileWriteTool().writeFile("../escape.txt", "secret-value", id);
            assertTrue(recorder.awaitIdle(java.time.Duration.ofSeconds(2)));
            assertEquals(3, steps.size());
            assertEquals("SUCCEEDED", steps.get(0).getStatus());
            assertEquals("FAILED", steps.get(1).getStatus());
            assertEquals("FAILED", steps.get(2).getStatus());
            assertTrue(steps.get(0).getSafeMetadata().contains("readFile"));
            assertFalse(steps.toString().contains("secret-value"));
            assertFalse(steps.toString().contains("../escape.txt"));
        }
    }

    @Test
    void absolutePathMustNotWriteOutsideApplication() throws Exception {
        Path target = outside.resolve("outside.txt");
        new FileWriteTool().writeFile(target.toString(), "不应写入", new AppChatMemoryId(981000001L, 1L));
        assertFalse(Files.exists(target), "绝对路径不能绕过应用目录");
    }

    @Test
    void traversalMustNotEscapeApplication() throws Exception {
        String name = "p0-safety-" + System.nanoTime() + ".txt";
        Path target = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).resolve(name);
        try {
            new FileWriteTool().writeFile("../" + name, "不应写入", new AppChatMemoryId(981000002L, 1L));
            assertFalse(Files.exists(target), "相对路径不能越界");
        } finally { Files.deleteIfExists(target); }
    }

    @Test void symbolicLinkMustNotWriteOutsideTask() throws Exception {
        Path taskRoot = outside.resolve("draft"); Files.createDirectories(taskRoot);
        Path external = outside.resolve("external"); Files.createDirectories(external);
        Files.createSymbolicLink(taskRoot.resolve("link"), external);
        try (var run = new GenerationExecution("symlink", taskRoot, java.time.Duration.ofSeconds(5), () -> true)) {
            new FileWriteTool().writeFile("link/escape.txt", "不能写入", new AppChatMemoryId(1L,1L,run.id()));
            assertFalse(Files.exists(external.resolve("escape.txt")));
        }
    }
    @Test
    void ambiguousPatchMustLeaveOriginalFileIntact() throws Exception {
        Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_981000003");
        Files.createDirectories(root);
        Path target = root.resolve("patch.txt");
        try {
            Files.writeString(target, "相同内容\n相同内容");
            String result = new FileModifyTool().modifyFile("patch.txt", "相同内容", "新内容", new AppChatMemoryId(981000003L, 1L));
            assertEquals("相同内容\n相同内容", Files.readString(target));
            assertTrue(result.contains("唯一"));
        } finally { Files.deleteIfExists(target); Files.deleteIfExists(root); }
    }
}
