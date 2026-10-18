package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VueDeliveryServiceTest {
    @TempDir Path root;
    BuildResult error(String text) { return new BuildResult("build", 1, "", text, BuildResult.Kind.CODE, false); }
    @Test void fixesCompilerErrorAndVerifiesAgain() throws Exception {
        var builder = mock(VueProjectBuilder.class); var preview = mock(PreviewVerifier.class); var fixes = new AtomicInteger();
        when(builder.buildDetailed(any(), any())).thenReturn(error("App.vue:4:1 标签未闭合"), new BuildResult("build", 0, "", "", BuildResult.Kind.NONE, false));
        when(preview.verify(any(), eq(true), any())).thenReturn(new BuildResult("preview", 0, "", "", BuildResult.Kind.NONE, false));
        var service = new VueDeliveryService(builder, preview, (id, diagnostic, output) -> {
            assertTrue(diagnostic.contains("App.vue:4:1")); fixes.incrementAndGet();
        }, 2);
        try (var run = new GenerationExecution("修复测试", root, Duration.ofSeconds(20), () -> true)) {
            service.deliver(run, new AppChatMemoryId(1L, 1L, run.id()), text -> {}, event -> {});
            assertEquals(1, fixes.get());
        }
    }
    @Test void identicalErrorStopsWithoutSpendingAnotherRepair() {
        var builder = mock(VueProjectBuilder.class); var preview = mock(PreviewVerifier.class); var fixes = new AtomicInteger();
        when(builder.buildDetailed(any(), any())).thenReturn(error("App.vue:4:1 相同错误"));
        var service = new VueDeliveryService(builder, preview, (id, diagnostic, output) -> fixes.incrementAndGet(), 2);
        try (var run = new GenerationExecution("重复测试", root, Duration.ofSeconds(20), () -> true)) {
            assertThrows(com.xhl.aicodegenerate.exception.BusinessException.class,
                    () -> service.deliver(run, new AppChatMemoryId(1L, 1L, run.id()), text -> {}, event -> {}));
            assertEquals(1, fixes.get());
        }
    }
    @Test void differentErrorsStillStopAtTwoRepairs() {
        var builder = mock(VueProjectBuilder.class); var fixes = new AtomicInteger();
        when(builder.buildDetailed(any(), any())).thenReturn(error("第一处错误"), error("第二处错误"), error("第三处错误"));
        var service = new VueDeliveryService(builder, mock(PreviewVerifier.class), (id, diagnostic, output) -> fixes.incrementAndGet(), 2);
        try (var run = new GenerationExecution("两轮上限", root, Duration.ofSeconds(10), () -> true)) {
            assertThrows(com.xhl.aicodegenerate.exception.BusinessException.class,
                    () -> service.deliver(run, new AppChatMemoryId(1L,1L,run.id()), text -> {}, event -> {}));
            assertEquals(2, fixes.get());
        }
    }
    @Test void totalBudgetAlsoBoundsRepair() {
        var builder = mock(VueProjectBuilder.class); var fixes = new AtomicInteger();
        when(builder.buildDetailed(any(), any())).thenReturn(error("构建错误"));
        var service = new VueDeliveryService(builder, mock(PreviewVerifier.class), (id, diagnostic, output) -> {
            fixes.incrementAndGet(); java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
        }, 2);
        try (var run = new GenerationExecution("总预算", root, Duration.ofMillis(30), () -> true)) {
            assertThrows(GenerationExecution.BudgetExceeded.class,
                    () -> service.deliver(run, new AppChatMemoryId(1L,1L,run.id()), text -> {}, event -> {}));
            assertEquals(1, fixes.get());
        }
    }
    @Test void environmentFailureDoesNotAskModelToRewriteCode() {
        var builder = mock(VueProjectBuilder.class); var fixes = new AtomicInteger();
        when(builder.buildDetailed(any(), any())).thenReturn(new BuildResult("install", 1, "", "ECONNRESET", BuildResult.Kind.ENVIRONMENT, false));
        var service = new VueDeliveryService(builder, mock(PreviewVerifier.class), (id, diagnostic, output) -> fixes.incrementAndGet(), 2);
        try (var run = new GenerationExecution("环境测试", root, Duration.ofSeconds(20), () -> true)) {
            assertThrows(com.xhl.aicodegenerate.exception.BusinessException.class,
                    () -> service.deliver(run, new AppChatMemoryId(1L, 1L, run.id()), text -> {}, event -> {}));
            assertEquals(0, fixes.get());
        }
    }
}
