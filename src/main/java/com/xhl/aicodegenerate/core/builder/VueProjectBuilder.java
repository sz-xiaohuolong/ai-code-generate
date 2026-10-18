package com.xhl.aicodegenerate.core.builder;

import cn.hutool.crypto.digest.DigestUtil;
import com.xhl.aicodegenerate.generation.BuildResult;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 持续消费进程输出，保留有限诊断，并在取消和超时时回收进程树。 */
@Component
public class VueProjectBuilder {
    public boolean buildProject(String path) { return buildDetailed(Path.of(path), null).success(); }
    public void buildProjectAsync(String path) { Thread.ofVirtual().start(() -> buildProject(path)); }

    public BuildResult buildDetailed(Path root, GenerationExecution run) {
        try {
            check(run);
            if (!Files.isRegularFile(root.resolve("package.json")))
                return failure("install", "缺少 package.json", BuildResult.Kind.CODE);
            Path stamp = root.resolve(".generation-deps.sha256");
            String fingerprint = fingerprint(root);
            if (!Files.isDirectory(root.resolve("node_modules")) || !Files.isRegularFile(stamp)
                    || !Files.readString(stamp).equals(fingerprint)) {
                BuildResult install = command(root, List.of("npm", "install", "--ignore-scripts", "--no-audit", "--no-fund"), "install", 300, run);
                if (!install.success()) return install;
                Files.writeString(stamp, fingerprint(root));
            }
            removeDist(root.resolve("dist"));
            BuildResult build = command(root, List.of("npm", "run", "build"), "build", 180, run);
            if (build.success() && !Files.isRegularFile(root.resolve("dist/index.html")))
                return failure("build", "构建结束但未生成新的 dist/index.html", BuildResult.Kind.CODE);
            return build;
        } catch (java.util.concurrent.CancellationException | GenerationExecution.BudgetExceeded e) { throw e; }
        catch (IOException e) { return failure("environment", e.getMessage(), BuildResult.Kind.ENVIRONMENT); }
    }

    private String fingerprint(Path root) throws IOException {
        StringBuilder value = new StringBuilder();
        for (String name : List.of("package.json", "package-lock.json", "npm-shrinkwrap.json")) {
            Path file = root.resolve(name);
            if (Files.isRegularFile(file)) value.append(name).append(Files.readString(file));
        }
        return DigestUtil.sha256Hex(value.toString());
    }

    private BuildResult command(Path root, List<String> command, String stage, int seconds, GenerationExecution run) {
        Process process = null;
        var out = new Tail(); var err = new Tail();
        try {
            check(run);
            process = new ProcessBuilder(command).directory(root.toFile()).start();
            if (run != null) run.track(process);
            Thread stdout = pump(process.getInputStream(), out), stderr = pump(process.getErrorStream(), err);
            long budget = run == null ? Duration.ofSeconds(seconds).toNanos() : Math.min(run.remaining().toNanos(), Duration.ofSeconds(seconds).toNanos());
            long end = System.nanoTime() + budget;
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                check(run);
                if (System.nanoTime() >= end) {
                    GenerationExecution.terminate(process); stdout.join(2000); stderr.join(2000);
                    return new BuildResult(stage, -1, out.text(), err.text() + "\n执行超时", BuildResult.Kind.TIMEOUT, true);
                }
            }
            stdout.join(2000); stderr.join(2000); check(run);
            int code = process.exitValue();
            return new BuildResult(stage, code, out.text(), err.text(), code == 0 ? BuildResult.Kind.NONE : classify(stage, out.text() + err.text()), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("构建已停止");
        } catch (IOException e) { return failure(stage, "构建环境不可用：" + e.getMessage(), BuildResult.Kind.ENVIRONMENT); }
        finally {
            if (process != null) {
                if (run != null) run.untrack(process);
                else if (process.isAlive()) GenerationExecution.terminate(process);
            }
        }
    }

    private BuildResult.Kind classify(String stage, String diagnostic) {
        String text = diagnostic.toLowerCase(Locale.ROOT);
        if (List.of("econn", "enotfound", "eai_again", "etimedout", "e401", "e403", "enospc", "eacces", "certificate", "network")
                .stream().anyMatch(text::contains)) return BuildResult.Kind.ENVIRONMENT;
        if (stage.equals("install") && List.of("ejsonparse", "eresolve", "etarget", "enoent", "invalid package")
                .stream().noneMatch(text::contains)) return BuildResult.Kind.ENVIRONMENT;
        return BuildResult.Kind.CODE;
    }
    private BuildResult failure(String stage, String message, BuildResult.Kind kind) { return new BuildResult(stage, -1, "", message, kind, false); }
    private static void check(GenerationExecution run) {
        if (run != null) run.checkActive();
        else if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("构建已停止");
    }
    private Thread pump(InputStream input, Tail target) {
        return Thread.ofVirtual().start(() -> {
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048]; int count;
                while ((count = reader.read(buffer)) != -1) target.add(buffer, count);
            } catch (IOException ignored) { }
        });
    }
    private static final class Tail {
        private final StringBuilder value = new StringBuilder();
        synchronized void add(char[] chars, int count) { value.append(chars, 0, count); if (value.length() > 16384) value.delete(0, value.length() - 16384); }
        synchronized String text() { return value.toString(); }
    }
    private void removeDist(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException { Files.delete(directory); return FileVisitResult.CONTINUE; }
        });
    }
}
