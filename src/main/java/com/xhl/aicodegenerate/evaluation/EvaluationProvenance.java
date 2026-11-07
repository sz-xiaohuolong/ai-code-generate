package com.xhl.aicodegenerate.evaluation;

import com.xhl.aicodegenerate.observability.TraceArtifacts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 记录受测源代码与运行环境；不复制本地凭据或声称脏工作区可完全复现。 */
public final class EvaluationProvenance {
    private EvaluationProvenance() { }

    public static EvaluationReport.Source source(Path repository) {
        try {
            Path root = repository.toRealPath();
            String revision = command(root, List.of("git", "rev-parse", "HEAD"));
            String status = command(root, List.of("git", "status", "--porcelain", "--untracked-files=normal"));
            String names = command(root, List.of("git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"));
            if (revision == null || names == null || status == null) throw new IOException("无法读取受测 Git 状态");
            var digest = MessageDigest.getInstance("SHA-256");
            var files = Arrays.stream(names.split("\u0000")).filter(EvaluationProvenance::sourceFile).distinct().sorted().toList();
            if (files.isEmpty() || files.size() > 100_000) throw new IOException("受测源代码清单为空或超限");
            for (String name : files) {
                Path path = root.resolve(name).normalize();
                if (!path.startsWith(root)) throw new IOException("源码路径越界");
                digest.update(name.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    digest.update("DELETED".getBytes(StandardCharsets.UTF_8));
                } else {
                    digest.update(EvaluationReport.fileDigest(path).getBytes(StandardCharsets.US_ASCII));
                }
            }
            boolean dirty = !status.isBlank();
            return new EvaluationReport.Source(revision.strip(), HexFormat.of().formatHex(digest.digest()), dirty, !dirty,
                    dirty ? "未保存可恢复的受测源码快照；已记录工作树与实际编译产物摘要，不能声称完整复现" : "源代码可按已记录的 Git 提交恢复；外部环境仍须匹配");
        } catch (IOException | java.security.NoSuchAlgorithmException error) {
            throw new EvaluationConfigurationException("无法记录受测源代码版本与摘要，请检查 Git、源码路径和读取权限", error);
        }
    }

    private static boolean sourceFile(String name) {
        return name.startsWith("src/") || name.startsWith("evaluation/") || name.startsWith("scripts/")
                || name.startsWith("sql/") || name.startsWith(".mvn/") || name.equals("pom.xml")
                || name.equals("mvnw") || name.equals("mvnw.cmd");
    }

    public static String promptVersion(Path repository) {
        try { return TraceArtifacts.digest(repository.resolve("src/main/resources/prompt")); }
        catch (IOException error) { throw new EvaluationConfigurationException("无法固定 Prompt 模板版本", error); }
    }

    public static Map<String, String> environment(Path repository) {
        Map<String, String> values = new TreeMap<>();
        values.put("javaVersion", System.getProperty("java.version"));
        values.put("osName", System.getProperty("os.name"));
        values.put("osVersion", System.getProperty("os.version"));
        values.put("architecture", System.getProperty("os.arch"));
        values.put("seleniumVersion", new org.openqa.selenium.BuildInfo().getReleaseLabel());
        values.put("nodeVersion", version(repository, "node"));
        values.put("npmVersion", version(repository, "npm"));
        values.put("viewport", EvaluationBrowser.VIEWPORT_WIDTH + "x" + EvaluationBrowser.VIEWPORT_HEIGHT);
        values.put("browserVersionSource", "逐用例 browser.browserVersion 为实际 Chrome 版本");
        try {
            Path classes = Path.of(EvaluationProvenance.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            values.put("compiledTreeDigest", Files.isDirectory(classes) ? TraceArtifacts.digest(classes) : EvaluationReport.fileDigest(classes));
        } catch (Exception error) {
            throw new EvaluationConfigurationException("无法记录实际受测编译产物摘要", error);
        }
        return Map.copyOf(values);
    }

    private static String version(Path repository, String executable) {
        String result = command(repository, List.of(executable, "--version"));
        return result == null ? "UNAVAILABLE" : result.strip().substring(0, Math.min(result.strip().length(), 256));
    }

    /** 只运行固定的只读命令，参数不经过 shell；输出有界且不进入控制台或原始日志。 */
    private static String command(Path directory, List<String> command) {
        Process process = null;
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            process = new ProcessBuilder(command).directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process active = process;
            var output = readers.submit(() -> {
                try (var input = active.getInputStream()) { return input.readNBytes(8 * 1024 * 1024 + 1); }
            });
            try {
                byte[] bytes = output.get(10, TimeUnit.SECONDS);
                if (bytes.length > 8 * 1024 * 1024 || !process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
                return new String(bytes, StandardCharsets.UTF_8);
            } finally {
                process.destroyForcibly();
                output.cancel(true);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return null;
        } catch (Exception error) {
            return null;
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
