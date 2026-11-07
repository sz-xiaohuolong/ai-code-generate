package com.xhl.aicodegenerate.ai.tools;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import com.xhl.aicodegenerate.observability.TraceSession;
import java.io.IOException;
import java.nio.file.*;

/** 所有文件工具共享目录约束；符号链接不属于可操作的项目文件。 */
final class FileToolSupport {
    private FileToolSupport() { }
    @FunctionalInterface interface Operation { Outcome apply(Path path) throws IOException; }
    record Outcome(String text, String status) {
        static Outcome success(String text) { return new Outcome(text, "SUCCEEDED"); }
        static Outcome failed(String text) { return new Outcome(text, "FAILED"); }
        static Outcome skipped(String text) { return new Outcome(text, "SKIPPED"); }
    }

    static String execute(AppChatMemoryId id, String toolName, String relative, Operation action) {
        GenerationExecution run = id.getTaskId() == null ? null : GenerationExecution.require(id.getTaskId());
        Path root = run == null ? Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + id.getAppId()) : run.root();
        TraceSession.Step step = run == null ? null : run.traceStep(TraceSession.Kind.TOOL, toolName);
        if (step != null) step.metadata(java.util.Map.of("toolName", toolName, "relativePath", relative == null ? "" : relative));
        try {
            Outcome result = run == null ? action.apply(resolve(root, relative)) : run.files(() -> action.apply(resolve(root, relative)));
            if (step != null) {
                switch (result.status()) {
                    case "SUCCEEDED" -> step.succeed();
                    case "SKIPPED" -> step.skip();
                    default -> step.fail(new IllegalStateException("文件操作未满足条件"));
                }
            }
            return result.text();
        } catch (java.util.concurrent.CancellationException | GenerationExecution.BudgetExceeded e) {
            if (step != null) step.cancel();
            throw e;
        } catch (Exception e) {
            if (step != null) step.fail(e);
            return "文件操作失败：" + e.getMessage();
        }
    }

    static Path resolve(Path root, String relative) throws IOException {
        root = root.toAbsolutePath().normalize();
        Path supplied = Path.of(relative == null ? "" : relative);
        Path result = root.resolve(supplied).normalize();
        if (supplied.isAbsolute() || !result.startsWith(root)) throw new IOException("仅允许应用目录内的相对路径");
        for (Path current = root; current != null && current.startsWith(root); ) {
            if (Files.isSymbolicLink(current)) throw new IOException("不允许操作符号链接");
            if (current.equals(result)) break;
            int index = root.relativize(current).toString().isEmpty() ? 0 : root.relativize(current).getNameCount();
            Path remainder = root.relativize(result);
            current = current.resolve(remainder.getName(index));
        }
        return result;
    }
}
