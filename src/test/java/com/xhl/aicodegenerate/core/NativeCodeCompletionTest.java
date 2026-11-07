package com.xhl.aicodegenerate.core;

import com.xhl.aicodegenerate.ai.AiCodeGeneratorService;
import com.xhl.aicodegenerate.ai.AiCodeGeneratorServiceFactory;
import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅替换外部模型，实际执行流式收集、解析、校验与任务目录保存。 */
class NativeCodeCompletionTest {
    @TempDir Path root;
    private static final String PAGE = "<!doctype html><html><body><button>开始游戏</button><script>const ready = true;</script></body></html>";

    @Test
    void truncatedHtmlIsRegeneratedWithinSameTask() throws Exception {
        var service = mock(AiCodeGeneratorService.class);
        var prompts = new ArrayList<String>();
        when(service.generateHtmlCodeStream(any(), anyString())).thenAnswer(call -> {
            prompts.add(call.getArgument(1));
            return Flux.just(prompts.size() == 1 ? "```html\n<html><body><script>ctx.shadowBl" : "```html\n" + PAGE + "\n```");
        });
        try (var run = run()) {
            var output = new StringBuilder();
            run.observe(output::append, event -> {
                if ("code_generator".equals(event.get("id"))) output.setLength(0);
            });
            execute(service, CodeGenTypeEnum.HTML, run).doOnNext(run::output).blockLast();

            assertEquals(PAGE, Files.readString(root.resolve("index.html")));
            assertEquals(2, prompts.size());
            assertTrue(prompts.get(1).contains("赛车需求"));
            assertTrue(prompts.get(1).contains("不完整"));
            assertFalse(output.toString().contains("ctx.shadowBl"), "修复结果不能混入上轮截断输出");
        }
    }

    @Test
    void multiFileReplyMissingAssetsIsRegeneratedWithRealStylesAndScript() throws Exception {
        var service = mock(AiCodeGeneratorService.class);
        String html = "<html><head><link rel='stylesheet' href='style.css'></head><body><button>开始游戏</button><script src='script.js'></script></body></html>";
        var prompts = new ArrayList<String>();
        when(service.generateMultiFileCodeStream(any(), anyString())).thenAnswer(call -> {
            prompts.add(call.getArgument(1));
            return Flux.just("```html\n" + html + "\n```" + (prompts.size() == 1 ? "" : "\n```css\n.logo { width: 32px; }\n```\n```javascript\ndocument.querySelector('button').onclick = () => alert('开始');\n```"));
        });
        try (var run = run()) {
            execute(service, CodeGenTypeEnum.MULTI_FILE, run).blockLast();

            assertEquals(".logo { width: 32px; }", Files.readString(root.resolve("style.css")));
            assertTrue(Files.readString(root.resolve("script.js")).contains("onclick"));
            assertEquals(2, prompts.size());
            assertTrue(prompts.get(1).contains("style.css"));
        }
    }

    @Test
    void repeatedIncompleteReplyStopsAndKeepsPreviousFiles() throws Exception {
        Files.writeString(root.resolve("index.html"), PAGE);
        var service = mock(AiCodeGeneratorService.class);
        var calls = new AtomicInteger();
        when(service.generateHtmlCodeStream(any(), anyString())).thenAnswer(call -> {
            calls.incrementAndGet();
            return Flux.just("<html><body><script>ctx.shadowBl");
        });
        try (var run = run()) {
            assertThrows(BusinessException.class, () -> execute(service, CodeGenTypeEnum.HTML, run).blockLast());
            assertEquals(2, calls.get(), "只允许一次完整性修复，不能无限重试");
            assertEquals(PAGE, Files.readString(root.resolve("index.html")));
        }
    }

    @Test
    void providerFailureIsNotRetriedAsCodeRepair() {
        var service = mock(AiCodeGeneratorService.class);
        var calls = new AtomicInteger();
        when(service.generateHtmlCodeStream(any(), anyString())).thenAnswer(call -> {
            calls.incrementAndGet();
            return Flux.error(new IllegalStateException("供应商不可用"));
        });
        try (var run = run()) {
            assertThrows(IllegalStateException.class, () -> execute(service, CodeGenTypeEnum.HTML, run).blockLast());
            assertEquals(1, calls.get());
            assertFalse(Files.exists(root.resolve("index.html")));
        }
    }

    @Test
    void cancellationBeforeRepairCannotStartAnotherModelCall() {
        var service = mock(AiCodeGeneratorService.class);
        var calls = new AtomicInteger();
        try (var run = run()) {
            when(service.generateHtmlCodeStream(any(), anyString())).thenAnswer(call -> {
                calls.incrementAndGet();
                return Flux.just("<html><body><script>ctx.shadowBl").doOnComplete(() -> run.cancel("用户停止"));
            });
            assertThrows(CancellationException.class, () -> execute(service, CodeGenTypeEnum.HTML, run).blockLast());
            assertEquals(1, calls.get());
            assertFalse(Files.exists(root.resolve("index.html")));
        }
    }

    private GenerationExecution run() {
        return new GenerationExecution(UUID.randomUUID().toString(), root, Duration.ofSeconds(10), () -> true);
    }

    private Flux<String> execute(AiCodeGeneratorService service, CodeGenTypeEnum type, GenerationExecution run) {
        var facade = new AiCodeGeneratorFacade();
        var factory = mock(AiCodeGeneratorServiceFactory.class);
        when(factory.createAiCodeGeneratorService(any(), any(), any())).thenReturn(service);
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
        return facade.generateAndSaveCodeStreamForWorkflow("赛车需求", type,
                new AppChatMemoryId(1L, 1L, run.id()), ignored -> dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(20));
    }
}
