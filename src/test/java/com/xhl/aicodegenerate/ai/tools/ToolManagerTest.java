package com.xhl.aicodegenerate.ai.tools;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

class ToolManagerTest {

    @Test
    void registerAndLookupTools() {
        ToolManager toolManager = new ToolManager();
        ReflectionTestUtils.setField(toolManager, "tools",
                new BaseTool[]{new FileWriteTool(), new FileReadTool(), new FileModifyTool(),
                        new FileDeleteTool(), new FileDirReadTool()});
        toolManager.init();

        Assertions.assertNotNull(toolManager.getTool("writeFile"));
        Assertions.assertEquals("写入文件", toolManager.getTool("writeFile").getDisplayName());
        Assertions.assertEquals("读取目录结构", toolManager.getTool("readDir").getDisplayName());
        Assertions.assertNull(toolManager.getTool("notExist"));
        Assertions.assertEquals(5, toolManager.getAllTools().length);
    }

    @Test
    void allContextAwareToolsAcceptAppChatMemoryId() {
        AppChatMemoryId memoryId = new AppChatMemoryId(Long.MAX_VALUE, 1L);
        List<ToolInvocation> invocations = List.of(
                new ToolInvocation(new FileReadTool(), "readFile",
                        "{\"relativeFilePath\":\"missing.txt\"}"),
                new ToolInvocation(new FileModifyTool(), "modifyFile",
                        "{\"relativeFilePath\":\"missing.txt\",\"oldContent\":\"old\",\"newContent\":\"new\"}"),
                new ToolInvocation(new FileDeleteTool(), "deleteFile",
                        "{\"relativeFilePath\":\"missing.txt\"}"),
                new ToolInvocation(new FileDirReadTool(), "readDir",
                        "{\"relativeDirPath\":\"missing\"}")
        );

        for (ToolInvocation invocation : invocations) {
            Method toolMethod = findToolMethod(invocation.tool());
            String result = Assertions.assertDoesNotThrow(() ->
                            new DefaultToolExecutor(invocation.tool(), toolMethod)
                                    .execute(ToolExecutionRequest.builder()
                                            .id("test-" + invocation.toolName())
                                            .name(invocation.toolName())
                                            .arguments(invocation.arguments())
                                            .build(), memoryId),
                    invocation.toolName() + " 必须能够接收 AppChatMemoryId");

            Assertions.assertNotEquals("argument type mismatch", result,
                    invocation.toolName() + " 必须能够接收 AppChatMemoryId");
        }
    }

    private Method findToolMethod(BaseTool tool) {
        return List.of(tool.getClass().getDeclaredMethods()).stream()
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .findFirst()
                .orElseThrow();
    }

    private record ToolInvocation(BaseTool tool, String toolName, String arguments) {
    }
}
