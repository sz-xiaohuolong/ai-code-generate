package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.ai.tools.ToolManager;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VueRepairServiceTest {
    @TempDir Path root;

    @Test void missingRequirementsStopsBeforeCallingModel() {
        var facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateAndSaveCodeStreamForWorkflow(anyString(), any(), any(), any())).thenReturn(Flux.empty());
        var service = new VueRepairService(facade, mock(ToolManager.class));
        try (var run = new GenerationExecution("缺失需求", root, Duration.ofSeconds(10), () -> true)) {
            var error = assertThrows(BusinessException.class,
                    () -> service.repair(new AppChatMemoryId(1L, 1L, run.id()), "缺少 package.json", text -> {}));
            assertTrue(error.getMessage().contains("需求"));
            verifyNoInteractions(facade);
        }
    }

    @Test void cancelledTaskCannotStartRepairModel() {
        var facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateAndSaveCodeStreamForWorkflow(anyString(), any(), any(), any())).thenReturn(Flux.empty());
        var service = new VueRepairService(facade, mock(ToolManager.class));
        try (var run = new GenerationExecution("停止修复", root, Duration.ofSeconds(10), () -> true)) {
            run.cancel("用户停止");
            assertThrows(CancellationException.class,
                    () -> service.repair(new AppChatMemoryId(1L, 1L, run.id()), "缺少 package.json", text -> {}));
            verifyNoInteractions(facade);
        }
    }
}
