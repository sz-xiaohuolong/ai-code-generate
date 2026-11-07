package com.xhl.aicodegenerate.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;

/** 每次运行创建独立目录；输入、源码、业务产物和公开资源均不能作为报告目录。 */
public record EvaluationWorkspace(String runId, Path directory, Path runtime) {
    private static final List<String> RESERVED = List.of(
            "tmp", "src", "evaluation", "ai-code-frontend", "sql", "scripts", "target", ".git", ".mvn");

    public static EvaluationWorkspace create(Path repository, Path output) {
        try {
            Path repo = repository.toRealPath();
            Path requested = output.toAbsolutePath().normalize();
            rejectLinks(requested);
            Path existing = requested;
            while (!Files.exists(existing)) existing = existing.getParent();
            Path destination = existing.toRealPath().resolve(existing.relativize(requested)).normalize();
            if (repo.startsWith(destination) || RESERVED.stream().anyMatch(name -> destination.startsWith(repo.resolve(name)))) {
                throw new EvaluationConfigurationException("报告目录不能指向仓库根目录、源码、输入数据、业务产物或前端资源目录");
            }
            Files.createDirectories(destination);
            String runId = UUID.randomUUID().toString();
            Path run = destination.resolve(runId);
            if (Files.getFileStore(destination).supportsFileAttributeView("posix")) {
                Files.createDirectory(run, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(run);
            }
            return new EvaluationWorkspace(runId, run, Files.createDirectory(run.resolve("runtime")));
        } catch (IOException error) {
            throw new EvaluationConfigurationException("无法创建独立评测目录，请检查路径与写权限");
        }
    }

    static void rejectLinks(Path path) {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            // macOS 的 /var、/tmp 系统别名由 toRealPath 统一；自定义目录不允许符号链接。
            boolean systemAlias = current.equals(Path.of("/var")) || current.equals(Path.of("/tmp"));
            if (Files.isSymbolicLink(current) && !systemAlias) {
                throw new EvaluationConfigurationException("评测输出路径不能经过自定义符号链接");
            }
        }
    }
}
