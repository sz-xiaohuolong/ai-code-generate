package com.xhl.aicodegenerate.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationWorkspaceTest {
    @TempDir Path temp;
    @Test void newTaskCopiesSourceWithoutTouchingLastSuccessfulVersion() throws Exception {
        var store = mock(RedisTaskStore.class);
        var workspace = new GenerationWorkspace(store, temp.resolve("tasks"), temp.resolve("legacy"));
        Path previous = temp.resolve("legacy/vue_project_44"); Files.createDirectories(previous.resolve("dist"));
        Files.writeString(previous.resolve("App.vue"), "上次成功源码");
        Files.writeString(previous.resolve("dist/index.html"), "上次可用预览");
        var task = new GenerationTask(); task.setId("new-task"); task.setAppId(44L); task.setCodeGenType("vue_project");
        Path current = workspace.prepare(task);
        assertEquals("上次成功源码", Files.readString(current.resolve("App.vue")));
        assertFalse(Files.exists(current.resolve("dist")));
        Files.writeString(current.resolve("App.vue"), "本轮失败修改");
        assertEquals("上次成功源码", Files.readString(workspace.published(44L, "vue_project").resolve("App.vue")));
        assertEquals("上次可用预览", Files.readString(previous.resolve("dist/index.html")));
    }
    @Test void publishedPointerSelectsValidatedTaskAndCopyRejectsLinks() throws Exception {
        var store = mock(RedisTaskStore.class);
        var workspace = new GenerationWorkspace(store, temp.resolve("tasks"), temp.resolve("legacy"));
        when(store.published(45L)).thenReturn("success-task");
        assertEquals(temp.resolve("tasks/45/success-task").toAbsolutePath(), workspace.published(45L, "html"));
        Path legacy = temp.resolve("legacy/html_46"); Files.createDirectories(legacy);
        Files.createSymbolicLink(legacy.resolve("secret"), temp);
        var task = new GenerationTask(); task.setId("new-task"); task.setAppId(46L); task.setCodeGenType("html");
        Path copy = workspace.prepare(task);
        assertFalse(Files.exists(copy.resolve("secret")));
    }
}
