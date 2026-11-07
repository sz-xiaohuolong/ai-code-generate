package com.xhl.aicodegenerate.evaluation;

import com.xhl.aicodegenerate.observability.TraceArtifacts;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

/** 将结束写入的任务产物固定为独立证据，摘要与追踪中的产物口径一致。 */
public final class EvaluationArtifacts {
    private static final Set<String> EXCLUDED = Set.of("node_modules", ".git");
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAX_FILES = 10_000;
    private EvaluationArtifacts() { }
    public record Snapshot(String digest, long files, long bytes) { }

    public static Snapshot freeze(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("待保存产物不是普通目录");
        }
        Path root = source.toRealPath();
        Path target = destination.toAbsolutePath().normalize();
        EvaluationWorkspace.rejectLinks(target);
        if (target.startsWith(root) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("快照不能覆盖旧证据或写入来源目录");
        }
        String originalDigest = TraceArtifacts.digest(root);
        Files.createDirectories(target.getParent());
        Path staging = Files.createDirectory(target.getParent().resolve(".artifact-" + UUID.randomUUID() + ".tmp"));
        long[] totals = new long[2];
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private int entries;
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (!directory.equals(root) && EXCLUDED.contains(directory.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                    if (++entries > 20_000) throw new IOException("产物目录数量超过快照上限");
                    if (!directory.equals(root)) Files.createDirectory(staging.resolve(root.relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw new IOException("产物包含符号链接或特殊文件");
                    if (++totals[0] > MAX_FILES) throw new IOException("产物文件数量超过快照上限");
                    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                         var output = Files.newOutputStream(staging.resolve(root.relativize(file)), StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            totals[1] += count;
                            if (totals[1] > MAX_BYTES || Thread.currentThread().isInterrupted()) throw new IOException("产物快照未完整保存");
                            output.write(buffer, 0, count);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            String digest = TraceArtifacts.digest(staging);
            if (!originalDigest.equals(digest)) throw new IOException("复制期间产物发生变化，不能声明固定快照");
            seal(staging);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return new Snapshot(digest, totals[0], totals[1]);
        } finally {
            if (Files.exists(staging)) discardStaging(staging);
        }
    }

    /** 证据目录为本次运行独占；POSIX 下同时撤去文件和目录写权限，其他平台使用文件只读属性。 */
    static void seal(Path directory) throws IOException {
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("只读证据不能包含符号链接");
                if (posix) {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "r-x------" : "r--------"));
                } else if (Files.isRegularFile(path) && !path.toFile().setReadOnly()) {
                    throw new IOException("无法设置产物快照为只读");
                }
            }
        }
    }

    private static void discardStaging(Path staging) throws IOException {
        try (var paths = Files.walk(staging)) {
            var entries = paths.toList();
            for (Path path : entries) path.toFile().setWritable(true, true);
            for (Path path : entries.reversed()) Files.delete(path);
        }
    }
}
