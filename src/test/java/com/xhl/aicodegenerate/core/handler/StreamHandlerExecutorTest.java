package com.xhl.aicodegenerate.core.handler;

import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import com.xhl.aicodegenerate.model.dto.ai.CodeGenStreamMessage;
import com.xhl.aicodegenerate.model.enums.ChatHistoryMessageTypeEnum;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.service.ChatHistoryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.file.Paths;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamHandlerExecutorTest {

    @Test
    void executeSimpleTextStream() {
        List<String> result = StreamHandlerExecutor.execute(
                Flux.just("hello", " world"),
                CodeGenTypeEnum.HTML
        ).collectList().block();

        Assertions.assertEquals(List.of("hello", " world"), result);
    }

    @Test
    void executeJsonMessageStream() {
        String arguments = JSONUtil.createObj()
                .set("relativeFilePath", "src/App.vue")
                .set("content", "<template><div>todo</div></template>")
                .toString();

        Flux<String> originFlux = Flux.just(
                JSONUtil.toJsonStr(CodeGenStreamMessage.aiResponse("为你生成代码：")),
                JSONUtil.toJsonStr(CodeGenStreamMessage.toolRequest("call_0", "writeFile", "{\"relativeFilePath\"")),
                JSONUtil.toJsonStr(CodeGenStreamMessage.toolExecuted("call_0", "writeFile", arguments, "文件写入成功: src/App.vue")),
                JSONUtil.toJsonStr(CodeGenStreamMessage.aiResponse("生成代码结束！"))
        );

        String content = String.join("", StreamHandlerExecutor.execute(originFlux, CodeGenTypeEnum.VUE_PROJECT)
                .collectList()
                .block());

        Assertions.assertTrue(content.contains("为你生成代码："));
        Assertions.assertTrue(content.contains("[选择工具] 写入文件"));
        Assertions.assertTrue(content.contains("[工具调用] 写入文件 src/App.vue"));
        Assertions.assertTrue(content.contains("```vue"));
        Assertions.assertTrue(content.contains("<template><div>todo</div></template>"));
        Assertions.assertTrue(content.contains("生成代码结束！"));
    }

    @Test
    void executeJsonMessageStreamAndPersistToolOutput() {
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        VueProjectBuilder vueProjectBuilder = mock(VueProjectBuilder.class);
        when(chatHistoryService.saveMessage(eq(1L), eq(2L), eq("\n\n[选择工具] 写入文件\n"),
                eq(ChatHistoryMessageTypeEnum.AI.getValue()))).thenReturn(100L);

        String arguments = JSONUtil.createObj()
                .set("relativeFilePath", "src/App.vue")
                .set("content", "<template><div>todo</div></template>")
                .toString();
        Flux<String> originFlux = Flux.just(
                JSONUtil.toJsonStr(CodeGenStreamMessage.toolRequest("call_0", "writeFile", "{}")),
                JSONUtil.toJsonStr(CodeGenStreamMessage.toolExecuted("call_0", "writeFile", arguments, "文件写入成功: src/App.vue")),
                JSONUtil.toJsonStr(CodeGenStreamMessage.aiResponse("生成代码结束！"))
        );

        new JsonMessageStreamHandler(chatHistoryService, 1L, 2L, vueProjectBuilder)
                .handle(originFlux)
                .collectList()
                .block();

        String persistedToolMessage = "\n\n[选择工具] 写入文件\n"
                + "\n\n[工具调用] 写入文件 src/App.vue"
                + "\n```vue\n<template><div>todo</div></template>\n```\n";
        verify(chatHistoryService, times(1)).saveMessage(1L, 2L, "\n\n[选择工具] 写入文件\n",
                ChatHistoryMessageTypeEnum.AI.getValue());
        verify(chatHistoryService, times(1)).updateMessage(100L, persistedToolMessage);
    }

    @Test
    void executeJsonMessageStreamAndBuildVueProjectOnComplete() {
        VueProjectBuilder vueProjectBuilder = mock(VueProjectBuilder.class);
        Flux<String> originFlux = Flux.just(
                JSONUtil.toJsonStr(CodeGenStreamMessage.aiResponse("生成代码结束！"))
        );

        new JsonMessageStreamHandler(null, 100L, 200L, vueProjectBuilder)
                .handle(originFlux)
                .collectList()
                .block();

        String expectedProjectPath = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_100").toString();
        verify(vueProjectBuilder, times(1)).buildProjectAsync(expectedProjectPath);
    }
}
