package com.xhl.aicodegenerate.ai;

import com.xhl.aicodegenerate.ai.tools.ToolManager;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import com.xhl.aicodegenerate.generation.VueRepairService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 执行真实服务工厂、输入护轨、请求裁剪及文件工具，只替换外部流式模型。 */
class VueRepairRequestTest {
    @TempDir Path root;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void everyToolContinuationKeepsIntentEvenForLongValidUserRequests(boolean longContext) throws Exception {
        String initial = "任务管理应用：任务列表、筛选、搜索、新增弹窗、状态切换、响应式布局。"
                + (longContext ? "任务布局需求。".repeat(2200) : "");
        String previous = "增加优先级筛选。" + (longContext ? "保留任务分类。".repeat(800) : "");
        String current = "把新增任务按钮改为蓝色";
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.vue"), "<template><h1>任务管理</h1></template>");
        var responses = new ArrayList<AiMessage>();
        for (int i = 0; i < 12; i++) responses.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("读取-" + i).name("readFile").arguments("{\"relativeFilePath\":\"src/App.vue\"}").build()));
        responses.add(AiMessage.from("检查完成"));
        var model = new AiToolContextPruningTest.ScriptedModel(responses.toArray(AiMessage[]::new));
        var facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", AiToolContextPruningTest.factory(model));
        try (var run = new GenerationExecution(UUID.randomUUID().toString(), root, Duration.ofSeconds(20), () -> true, initial)) {
            run.captureRequirements(List.of(UserMessage.from(previous), AiMessage.from("绿源乡野 · 生态农场")), current);
            new VueRepairService(facade, mock(ToolManager.class)).repair(new AppChatMemoryId(1L, 1L, run.id()),
                    "App.vue:4 标签未闭合", text -> {});
            assertEquals(13, model.requests.size());
            for (var request : model.requests) {
                var userMessages = request.messages().stream().filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast).map(UserMessage::singleText).toList();
                String sent = String.join("\n", userMessages);
                assertAll(() -> assertTrue(sent.contains(initial), "每轮模型请求保留完整初始需求"),
                        () -> assertTrue(sent.contains(previous), "每轮模型请求保留完整历史需求"),
                        () -> assertTrue(sent.contains(current), "每轮模型请求保留本轮要求"),
                        () -> assertFalse(sent.contains("绿源乡野")));
            }
        }
    }
}
