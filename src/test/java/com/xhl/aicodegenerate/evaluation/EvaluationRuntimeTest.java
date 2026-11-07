package com.xhl.aicodegenerate.evaluation;

import com.xhl.aicodegenerate.generation.TaskStatus;
import com.xhl.aicodegenerate.model.dto.app.AppAddRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 仅替换模型与外部素材边界，真实创建、路由、任务、图、保存器、历史及 Chrome 共同执行。 */
class EvaluationRuntimeTest {
    @TempDir Path temporary;

    @Test void realCreationAndBothGenerationModesStayInIsolatedEnvironment() throws Exception {
        String url = System.getProperty("evaluation.test.mysql.url");
        assertNotNull(url, "必须显式配置专用 MySQL 测试地址");
        var original = EvaluationDatabaseConfig.from(Map.of("EVAL_JDBC_URL", url, "EVAL_JDBC_USER", "root", "EVAL_JDBC_PASSWORD", ""));
        String schema = "generation_evaluation_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var admin = new JdbcTemplate(new DriverManagerDataSource(url.replace("/" + original.schema(), "/"), "root", ""));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
        var config = EvaluationDatabaseConfig.from(Map.of("EVAL_JDBC_URL", url.replace("/" + original.schema(), "/" + schema),
                "EVAL_JDBC_USER", "root", "EVAL_JDBC_PASSWORD", ""));
        try (var database = EvaluationDatabase.open(config, Path.of("."))) {
            var budget = new EvaluationBudget(30, Duration.ofMinutes(2));
            String html = Files.readString(Path.of("evaluation/fixtures/counter/base/index.html"));
            ChatModel routing = new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("HTML")).build();
                }
            };
            StreamingChatModel generation = new StreamingChatModel() {
                @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    String system = request.messages().stream().filter(SystemMessage.class::isInstance)
                            .map(SystemMessage.class::cast).map(SystemMessage::text).findFirst().orElse("");
                    String output = system.contains("图片素材规划助手") ? "{\"contentImageTasks\":[],\"logoTasks\":[],\"diagramTasks\":[]}"
                            : system.contains("代码质量检查专家") ? "{\"isValid\":true,\"errors\":[],\"suggestions\":[]}"
                            : "```html\n" + html + "\n```";
                    handler.onPartialResponse(output);
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(output)).build());
                }
            };
            var models = EvaluationModelSet.budgeted(routing, routing, generation, generation, budget);
            for (String mode : new String[]{"standard", "workflow"}) {
                try (var runtime = EvaluationRuntime.open(database.dataSource(), temporary.resolve(mode), models, Duration.ofMinutes(1), false)) {
                    var request = new AppAddRequest(); request.setInitPrompt("创建单文件 HTML 计数器页面，按钮可以增加数字"); request.setGenerationMode(mode);
                    Long appId = runtime.apps().addApp(request, runtime.user());
                    assertEquals("html", runtime.apps().getById(appId).getCodeGenType());
                    var task = runtime.tasks().start(appId, request.getInitPrompt(), UUID.randomUUID().toString(), runtime.user());
                    var result = runtime.tasks().events(task.getId(), runtime.user()).blockLast(Duration.ofMinutes(1));
                    assertNotNull(result);
                    assertEquals(TaskStatus.SUCCEEDED, result.getStatus(), result.getMessage());
                    Path published = runtime.workspace().published(appId, "html");
                    assertTrue(published.startsWith(temporary));
                    assertEquals(html.strip(), Files.readString(published.resolve("index.html")).strip());
                    assertTrue(runtime.recorder().awaitIdle(Duration.ofSeconds(5)));
                    var trace = runtime.traces().find(result.getTraceId());
                    assertNotNull(trace);
                    assertEquals("COMPLETE", trace.getRecordingStatus());
                    assertEquals("SUCCEEDED", trace.getExecutionStatus());
                    assertNotNull(trace.getCreationTraceId());
                    var sql = new JdbcTemplate(database.dataSource());
                    for (int i = 0; i < 100 && sql.queryForObject("SELECT COUNT(*) FROM chat_history WHERE appId=?", Integer.class, appId) < 2; i++) Thread.sleep(20);
                    assertEquals(2, sql.queryForObject("SELECT COUNT(*) FROM chat_history WHERE appId=?", Integer.class, appId));
                    assertTrue(runtime.steps().list(trace.getTraceId(), 0, 100).stream().anyMatch(step -> "PREVIEW".equals(step.getStepKind())));
                }
            }
            assertEquals(6, budget.modelCalls(), "两次路由、两次代码生成、工作流素材规划与质检各一次");
            var dataset = EvaluationDataset.load(Path.of("evaluation"));
            for (String caseId : new String[]{"B03", "E01", "E03", "E04", "E05", "E07", "E09", "E11"}) {
                var sample = dataset.cases().stream().filter(item -> caseId.equals(item.caseId())).findFirst().orElseThrow();
                verifyFixture(database.dataSource(), dataset, sample);
            }
        } finally {
            admin.execute("DROP DATABASE " + schema);
        }
    }

    private void verifyFixture(javax.sql.DataSource dataSource, EvaluationDataset dataset, EvaluationDataset.Case sample) throws Exception {
        var model = new EvaluationFixtureModels(sample, dataset.fixture(sample));
        var budget = new EvaluationBudget(100, Duration.ofMinutes(2));
        Duration timeout = "BUILD_TIMEOUT".equals(sample.fault()) ? Duration.ofSeconds(5) : Duration.ofMinutes(1);
        try (var runtime = EvaluationRuntime.open(dataSource, temporary.resolve(sample.caseId()), model.models(budget), timeout,
                "TRACE_FAILURE".equals(sample.fault()))) {
            var request = new AppAddRequest();
            request.setInitPrompt(sample.initialPrompt() == null ? sample.prompt() : sample.initialPrompt());
            request.setGenerationMode(sample.generationMode());
            Long appId = runtime.apps().addApp(request, runtime.user());
            assertEquals(sample.codeGenType(), runtime.apps().getById(appId).getCodeGenType());
            Path initial = runtime.workspace().published(appId, sample.codeGenType());
            if (sample.initialArtifactRef() != null) {
                Path source = dataset.initialArtifact(sample);
                try (var walk = Files.walk(source)) {
                    for (Path path : walk.toList()) {
                        Path target = initial.resolve(source.relativize(path));
                        if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
                    }
                }
            }
            String initialHash = sample.initialArtifactRef() == null ? null
                    : com.xhl.aicodegenerate.observability.TraceArtifacts.digest(initial);
            var started = runtime.tasks().start(appId, sample.prompt(), UUID.randomUUID().toString(), runtime.user());
            if ("CANCEL_STREAM".equals(sample.fault())) {
                for (int i = 0; i < 1000 && !model.waitingForCancellation(); i++) Thread.sleep(10);
                assertTrue(model.waitingForCancellation());
                runtime.tasks().stop(started.getId(), runtime.user());
            }
            var task = runtime.tasks().events(started.getId(), runtime.user()).blockLast(Duration.ofMinutes(1));
            assertNotNull(task);
            assertEquals(sample.expectedOutcome(), task.getStatus().name(), sample.caseId() + ": " + task.getMessage());
            com.xhl.aicodegenerate.entity.GenerationTrace trace = null;
            for (int i = 0; i < 200; i++) {
                runtime.recorder().awaitIdle(Duration.ofMillis(100));
                trace = runtime.traces().find(task.getTraceId());
                if (trace != null && !"RUNNING".equals(trace.getExecutionStatus())) break;
                Thread.sleep(10);
            }
            assertNotNull(trace);
            var steps = runtime.steps().list(trace.getTraceId(), 0, 100);
            if ("BUILD_REPAIR".equals(sample.fault())) {
                var builds = steps.stream().filter(step -> "BUILD".equals(step.getStepKind())).toList();
                assertEquals(2, builds.size(), sample.caseId());
                assertEquals("FAILED", builds.getFirst().getStatus());
                assertEquals("SUCCEEDED", builds.getLast().getStatus());
                assertTrue(steps.stream().anyMatch(step -> "TOOL".equals(step.getStepKind())));
            }
            if ("CANCEL_STREAM".equals(sample.fault())) {
                assertTrue(model.streamCancelled());
                assertEquals(initialHash, com.xhl.aicodegenerate.observability.TraceArtifacts.digest(initial));
                assertEquals(initial, runtime.workspace().published(appId, sample.codeGenType()));
            }
            if ("BUILD_TIMEOUT".equals(sample.fault())) {
                assertTrue(steps.stream().anyMatch(step -> "BUILD".equals(step.getStepKind())), "超时必须实际进入构建路径");
            }
            assertEquals("TRACE_FAILURE".equals(sample.fault()) ? "PARTIAL" : "COMPLETE", trace.getRecordingStatus(), sample.caseId());
        }
    }
}
