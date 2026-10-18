package com.xhl.aicodegenerate.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class GenerationExecutionTest {
    @TempDir Path root;
    @Test void cancellationPreventsLaterFileOperations() {
        try (var run = new GenerationExecution("取消测试", root, Duration.ofSeconds(5), () -> true)) {
            run.cancel("用户停止");
            assertThrows(CancellationException.class, () -> run.files(() -> "不能执行"));
        }
    }
    @Test void lostOwnershipPreventsFileOperations() {
        try (var run = new GenerationExecution("失主测试", root, Duration.ofSeconds(5), () -> false)) {
            assertThrows(CancellationException.class, () -> run.files(() -> "不能执行"));
        }
    }
    @Test void cleansChildProcessAfterParentExits() throws Exception {
        Path pid = root.resolve("child.pid");
        String script = "const fs=require('fs'); const child=require('child_process').spawn('node',['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});fs.writeFileSync(process.argv[1],String(child.pid));child.unref();setTimeout(()=>process.exit(0),400)";
        Process parent = new ProcessBuilder("node", "-e", script, pid.toString()).start();
        ProcessHandle child = null;
        try (var run = new GenerationExecution("进程树", root, Duration.ofSeconds(10), () -> true)) {
            run.track(parent);
            assertTrue(parent.waitFor(3, java.util.concurrent.TimeUnit.SECONDS));
            child = ProcessHandle.of(Long.parseLong(java.nio.file.Files.readString(pid))).orElseThrow();
            assertTrue(child.isAlive());
            run.untrack(parent);
            assertFalse(child.isAlive(), "父进程退出后仍应回收后台子进程");
        } finally { if (child != null) child.destroyForcibly(); parent.destroyForcibly(); }
    }
    @Test void expiredBudgetPreventsWork() {
        try (var run = new GenerationExecution("超时测试", root, Duration.ZERO, () -> true)) {
            assertThrows(GenerationExecution.BudgetExceeded.class, run::checkActive);
        }
    }
}
