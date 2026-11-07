package com.xhl.aicodegenerate.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhl.aicodegenerate.ai.tools.ToolManager;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.generation.*;
import com.xhl.aicodegenerate.langgraph4j.CodeGenWorkflow;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 六组合共享入口的受控验证，实际供应商生成质量另行验收。 */
class KnowledgeRunnerMatrixTest {
    @ParameterizedTest
    @CsvSource({"standard,html", "standard,multi_file", "standard,vue_project", "workflow,html", "workflow,multi_file", "workflow,vue_project"})
    void preparesBeforeDispatchAndStopsEveryModeWhenRetrievalFails(String mode, String type) {
        var preparation = mock(KnowledgePreparation.class);
        var facade = mock(AiCodeGeneratorFacade.class);
        var workflow = mock(CodeGenWorkflow.class);
        var delivery = mock(VueDeliveryService.class);
        var verifier = mock(PreviewVerifier.class);
        ChatMemoryProvider memory = ignored -> MessageWindowChatMemory.withMaxMessages(20);
        var runner = new DefaultGenerationTaskRunner(memory, facade, workflow, delivery, verifier, mock(ToolManager.class), mock(TaskHistoryRecorder.class));
        ReflectionTestUtils.setField(runner, "knowledgePreparation", preparation);
        var task = new GenerationTask();
        task.setId(UUID.randomUUID().toString()); task.setAppId(11L); task.setUserId(7L);
        task.setCodeGenType(type); task.setGenerationMode(mode); task.setUseKnowledge(true); task.setPrompt("生成订单页面");
        try (var run = new GenerationExecution(task.getId(), Path.of("target/matrix"), Duration.ofSeconds(5), () -> true)) {
            run.requireKnowledge();
            when(preparation.prepare(eq(task.getId()), eq(7L), eq(11L), isNull(), isNull(), eq(run)))
                    .thenThrow(new KnowledgeException("KNOWLEDGE_NO_MATCH", "无命中"));
            assertThrows(KnowledgeException.class, () -> runner.run(task, run, ignored -> { }, ignored -> { }));
            verifyNoInteractions(facade, workflow, delivery, verifier);
        }
        task.setId(UUID.randomUUID().toString());
        try (var run = new GenerationExecution(task.getId(), Path.of("target/matrix"), Duration.ofSeconds(5), () -> true)) {
            run.requireKnowledge();
            var source = new KnowledgeTypes.Source(1, 2, 1, "接口.md", "build", "chunk", "GET /orders", 0.9);
            when(preparation.prepare(eq(task.getId()), eq(7L), eq(11L), isNull(), isNull(), eq(run)))
                    .thenReturn(new KnowledgeContext(mock(KnowledgeStore.class), task.getId(), 7, 11, List.of(source), new ObjectMapper()));
            // 用约定异常截住生成入口，明确证明分流前已完成准备；不冒充预览或真实模型验收。
            var reached = new IllegalStateException("受控生成入口");
            if ("workflow".equals(mode)) when(workflow.generateForTask(anyString(), any(), any(), any())).thenAnswer(call -> { run.assertGenerationAllowed(); throw reached; });
            else when(facade.generateAndSaveCodeStreamForWorkflow(anyString(), any(), any(), any())).thenAnswer(call -> { run.assertGenerationAllowed(); throw reached; });
            assertSame(reached, assertThrows(IllegalStateException.class, () -> runner.run(task, run, ignored -> { }, ignored -> { })));
            var order = inOrder(preparation, facade, workflow);
            order.verify(preparation).prepare(eq(task.getId()), eq(7L), eq(11L), isNull(), isNull(), eq(run));
            if ("workflow".equals(mode)) order.verify(workflow).generateForTask(anyString(), any(), any(), any());
            else order.verify(facade).generateAndSaveCodeStreamForWorkflow(anyString(), any(), any(), any());
        }
    }
}
