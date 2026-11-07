package com.xhl.aicodegenerate.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** 对已停止写入的固定任务目录计算摘要，仅输出相对引用和哈希。 */
public final class TraceArtifacts {
    private static final int MAX_FILES = 10_000;
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final Set<String> EXCLUDED = Set.of("node_modules", ".git");
    private TraceArtifacts() { }

    public static String digest(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && EXCLUDED.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) throw new IOException("产物包含符号链接，摘要未完成");
                if (attrs.isRegularFile()) {
                    if (files.size() >= MAX_FILES) throw new IOException("产物文件数量超过追踪摘要上限");
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (files.isEmpty()) throw new IOException("没有可关联的产物");
        files.sort(Comparator.comparing(path -> root.relativize(path).toString()));
        var digest = sha256();
        long bytes = 0;
        byte[] buffer = new byte[8192];
        for (Path file : files) {
            digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            var fileDigest = sha256();
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    bytes += count;
                    if (bytes > MAX_BYTES || Thread.currentThread().isInterrupted()) throw new IOException("产物摘要未完整完成");
                    fileDigest.update(buffer, 0, count);
                }
            }
            digest.update(fileDigest.digest());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static java.security.MessageDigest sha256() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("Java 运行时缺少 SHA-256", error);
        }
    }
}
