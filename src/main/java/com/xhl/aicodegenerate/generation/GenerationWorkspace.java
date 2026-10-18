package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.constant.AppConstant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** 草稿与成功版本目录分离，发布由任务仓储原子切换指针。 */
@Service
public class GenerationWorkspace {
    private final RedisTaskStore store;
    private final Path tasks;
    private final Path legacy;
    private static final Set<String> EXCLUDED = Set.of("node_modules", "dist", ".git", ".generation-ready");

    @Autowired public GenerationWorkspace(RedisTaskStore store) {
        this(store, Path.of(System.getProperty("user.dir"), "tmp/generation_tasks"), Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR));
    }
    GenerationWorkspace(RedisTaskStore store, Path tasks, Path legacy) {
        this.store = store; this.tasks = tasks.toAbsolutePath().normalize(); this.legacy = legacy.toAbsolutePath().normalize();
    }
    public Path taskRoot(Long appId, String taskId) {
        if (appId == null || appId <= 0 || taskId == null || !taskId.matches("[A-Za-z0-9-]{1,80}"))
            throw new IllegalArgumentException("无效任务目录");
        return tasks.resolve(appId.toString()).resolve(taskId);
    }
    public String publishedVersion(Long appId) { return store.published(appId); }
    public Path published(Long appId, String type) {
        if (com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum.getEnumByValue(type) == null)
            throw new IllegalArgumentException("无效代码类型");
        String version = store.published(appId);
        return version == null ? legacy.resolve(type + "_" + appId) : taskRoot(appId, version);
    }
    public Path version(Long appId, String type, String version) {
        if (com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum.getEnumByValue(type) == null || !store.isPublished(appId, version))
            throw new IllegalArgumentException("版本尚未通过交付检查");
        return taskRoot(appId, version);
    }
    public boolean hasPreview(Long appId, String type) {
        Path source = published(appId, type);
        return Files.isRegularFile(source.resolve("vue_project".equals(type) ? "dist/index.html" : "index.html"));
    }
    public Path prepare(GenerationTask task) throws IOException {
        Path target = taskRoot(task.getAppId(), task.getId());
        Files.createDirectories(target);
        Path source = published(task.getAppId(), task.getCodeGenType());
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) return target;
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source) && EXCLUDED.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(source.relativize(dir))); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        return target;
    }
}
