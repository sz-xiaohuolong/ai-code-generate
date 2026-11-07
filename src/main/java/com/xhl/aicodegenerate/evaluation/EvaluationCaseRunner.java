package com.xhl.aicodegenerate.evaluation;

import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.GenerationTraceStep;
import com.xhl.aicodegenerate.generation.GenerationTask;
import com.xhl.aicodegenerate.generation.TaskStatus;
import com.xhl.aicodegenerate.model.dto.app.AppAddRequest;
import com.xhl.aicodegenerate.observability.TraceArtifacts;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import static com.xhl.aicodegenerate.evaluation.EvaluationBrowser.Status;

/** 串行执行一条已冻结用例，保留真实任务结果、独立断言与记录缺口。 */
public final class EvaluationCaseRunner {
    public record Artifact(String relativePath, String digest, long files, long bytes, boolean draft) { }
    public record TraceEvidence(GenerationTrace root, List<GenerationTraceStep> steps) {
        public TraceEvidence { steps = List.copyOf(steps); }
    }
    public record Usage(long actualCalls, long tracedCalls, Long knownInputTokens, Long knownOutputTokens,
            long inputReportedCalls, long outputReportedCalls, Double inputCoverage, Double outputCoverage, boolean complete) { }
    public record Result(EvaluationDataset.Case sample, String requestId, Long appId, String taskId,
            String generationOutcome, Status build, EvaluationBrowser.Result browser,
            Status verdict, String traceCompleteness, boolean evidenceComplete,
            boolean comparisonEligible, long durationMs, long modelCalls, String initialSnapshotDigest,
            Artifact artifact, List<TraceEvidence> traces, List<String> diagnostics) {
        public Result { traces = List.copyOf(traces); diagnostics = List.copyOf(diagnostics); }
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public Usage usage() { return EvaluationCaseRunner.usage(traces, modelCalls); }
    }
    private final DataSource database;
    private final Path runtimeRoot;
    private final Path casesRoot;
    private final EvaluationDataset dataset;
    private final EvaluationBudget budget;

    public EvaluationCaseRunner(DataSource database, Path runtime, Path cases, EvaluationDataset dataset, EvaluationBudget budget) {
        this.database = database; this.runtimeRoot = runtime; this.casesRoot = cases; this.dataset = dataset; this.budget = budget;
    }

    public Result run(EvaluationDataset.Case sample, EvaluationModelSet models, EvaluationFixtureModels fixture) {
        if (!dataset.cases().contains(sample)) throw new EvaluationConfigurationException("只能执行已冻结数据集中的用例");
        State state = new State(sample);
        long start = System.nanoTime();
        long calls = budget.modelCalls();
        try {
            budget.checkActive();
            Files.createDirectories(casesRoot);
            Files.createDirectory(state.directory);
            Duration taskLimit = "BUILD_TIMEOUT".equals(sample.fault()) ? Duration.ofSeconds(5) : Duration.ofMinutes(20);
            if (budget.remaining().compareTo(taskLimit) < 0) taskLimit = budget.remaining();
            try (var runtime = EvaluationRuntime.open(database, runtimeRoot.resolve(sample.caseId()), models, taskLimit,
                    "TRACE_FAILURE".equals(sample.fault()))) {
                try {
                    execute(runtime, state, fixture);
                } catch (EvaluationBudget.Exhausted error) {
                    state.stopped = true;
                    state.diagnostics.add(error.getMessage());
                } catch (IOException error) {
                    state.evidenceComplete = false;
                    state.executionError = true;
                    state.diagnostics.add("产物或必要证据未完整保存");
                } catch (RuntimeException error) {
                    // 创建路由会包装底层模型异常；以批次的停止事实保留取消或预算耗尽语义。
                    if (budget.stopReason() != null) {
                        state.stopped = true;
                        state.diagnostics.add("批次已停止：" + budget.stopReason());
                    } else {
                        state.executionError = true;
                        state.diagnostics.add("用例创建或执行异常：" + error.getClass().getSimpleName());
                    }
                } finally {
                    settleTask(runtime, state);
                    captureTraces(runtime, state);
                }
                collectArtifacts(runtime, state);
                state.verdict = verdict(state, fixture);
            }
        } catch (EvaluationBudget.Exhausted error) {
            state.verdict = state.appId == null ? Status.SKIPPED : Status.CANCELLED;
            state.stopped = true;
            state.diagnostics.add(error.getMessage());
        } catch (IOException | RuntimeException error) {
            state.verdict = Status.ERROR;
            state.evidenceComplete = false;
            state.diagnostics.add("用例环境或证据处理未完整完成：" + error.getClass().getSimpleName());
        }
        boolean comparable = state.evidenceComplete && "COMPLETE".equals(state.traceCompleteness)
                && !state.stopped && state.verdict != Status.ERROR && state.verdict != Status.SKIPPED && state.verdict != Status.CANCELLED
                && usage(state.traces, budget.modelCalls() - calls).complete();
        return new Result(sample, state.requestId, state.appId, state.task == null ? null : state.task.getId(),
                state.outcome, state.build, state.browser, state.verdict, state.traceCompleteness, state.evidenceComplete,
                comparable, Duration.ofNanos(System.nanoTime() - start).toMillis(), budget.modelCalls() - calls,
                state.initialDigest, state.artifact, state.traces, state.diagnostics);
    }

    private void execute(EvaluationRuntime runtime, State state, EvaluationFixtureModels fixture) throws IOException {
        var request = new AppAddRequest();
        request.setInitPrompt(state.sample.initialPrompt() == null ? state.sample.prompt() : state.sample.initialPrompt());
        request.setGenerationMode(state.sample.generationMode());
        if ("modify".equals(state.sample.operation())) {
            // 修改能力从固定类型和初始需求开始，不让新建路由的随机选择改变受测输入。
            var app = com.xhl.aicodegenerate.entity.App.builder().appName("评测初始应用 " + state.sample.caseId())
                    .initPrompt(state.sample.initialPrompt()).codeGenType(state.sample.codeGenType())
                    .generationMode(state.sample.generationMode()).userId(runtime.user().getId()).build();
            if (!runtime.apps().save(app)) throw new IllegalStateException("无法准备评测初始应用");
            state.appId = app.getId();
            state.diagnostics.add("从固定类型、初始需求和初始产物准备修改应用，未调用创建路由");
        } else {
            state.requestId = UUID.randomUUID().toString();
            state.appId = runtime.apps().addApp(request, runtime.user(), state.requestId);
            if (!state.sample.codeGenType().equals(runtime.apps().getById(state.appId).getCodeGenType())) {
                state.outcome = "ROUTE_MISMATCH";
                state.diagnostics.add("创建路由结果与用例预期代码类型不匹配，下游生成未执行");
                return;
            }
        }
        if (state.sample.initialArtifactRef() != null) {
            Path initial = dataset.initialArtifact(state.sample);
            state.initialDigest = EvaluationArtifacts.freeze(initial, state.directory.resolve("initial")).digest();
            Path target = runtime.workspace().published(state.appId, state.sample.codeGenType());
            try (var paths = Files.walk(initial)) {
                for (Path path : paths.toList()) {
                    Path copy = target.resolve(initial.relativize(path));
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(copy);
                    else {
                        // 输入已经冻结并校验；业务初始目录需要可写，不能继承证据副本的只读权限。
                        Files.write(copy, Files.readAllBytes(path), StandardOpenOption.CREATE_NEW);
                    }
                }
            }
        }
        budget.checkActive();
        state.task = runtime.tasks().start(state.appId, state.sample.prompt(), UUID.randomUUID().toString(), runtime.user());
        boolean stopSent = false;
        while (!state.task.getStatus().terminal()) {
            budget.checkActive();
            if (!stopSent && "CANCEL_STREAM".equals(state.sample.fault()) && fixture != null && fixture.waitingForCancellation()) {
                runtime.tasks().stop(state.task.getId(), runtime.user());
                stopSent = true;
            }
            pause();
            state.task = runtime.tasks().get(state.task.getId(), runtime.user());
        }
        state.outcome = state.task.getStatus().name();
        budget.checkActive();
    }

    private void settleTask(EvaluationRuntime runtime, State state) {
        if (state.task == null) return;
        try {
            if (!state.task.getStatus().terminal()) {
                runtime.tasks().stop(state.task.getId(), runtime.user());
                long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                do {
                    pause();
                    state.task = runtime.tasks().get(state.task.getId(), runtime.user());
                } while (!state.task.getStatus().terminal() && System.nanoTime() < deadline);
            }
            state.outcome = state.task.getStatus().name();
            if (!state.task.getStatus().terminal()) {
                state.executionError = true;
                state.evidenceComplete = false;
                state.diagnostics.add("停止后的任务仍未确认终态，不保存可变草稿为固定产物");
            }
        } catch (RuntimeException error) {
            state.executionError = true;
            state.evidenceComplete = false;
            state.diagnostics.add("无法确认任务停止与资源清理");
        }
    }

    private void captureTraces(EvaluationRuntime runtime, State state) {
        try {
            GenerationTrace creation = state.requestId == null ? null : awaitTrace(runtime, () -> runtime.traces().byRequest(state.requestId));
            GenerationTrace generation = state.task == null ? null : awaitTrace(runtime, () -> runtime.traces().byTask(state.task.getId()));
            boolean complete = ("modify".equals(state.sample.operation()) || creation != null)
                    && (state.task == null || generation != null);
            for (GenerationTrace root : new GenerationTrace[]{creation, generation}) {
                if (root == null) continue;
                List<GenerationTraceStep> steps = new ArrayList<>();
                long cursor = 0;
                while (true) {
                    var page = runtime.steps().list(root.getTraceId(), cursor, 200);
                    steps.addAll(page);
                    if (page.size() < 200) break;
                    if (steps.size() >= 10_000) { complete = false; state.diagnostics.add("追踪步骤超过报告上限"); break; }
                    cursor = page.getLast().getStepNo();
                }
                state.traces.add(new TraceEvidence(root, steps));
                complete &= "COMPLETE".equals(root.getRecordingStatus()) && !"RUNNING".equals(root.getExecutionStatus());
            }
            state.traceCompleteness = state.traces.isEmpty() ? "UNAVAILABLE" : complete ? "COMPLETE" : "PARTIAL";
            if (!complete) state.diagnostics.add("追踪记录不完整，不纳入完整用量和耗时比较");
        } catch (RuntimeException error) {
            state.traceCompleteness = "PARTIAL";
            state.diagnostics.add("追踪读取失败，不纳入完整比较");
        }
    }

    private GenerationTrace awaitTrace(EvaluationRuntime runtime, Supplier<GenerationTrace> query) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        GenerationTrace result;
        do {
            runtime.recorder().awaitIdle(Duration.ofMillis(100));
            result = query.get();
            if (result != null && !"RUNNING".equals(result.getExecutionStatus()) && !"COLLECTING".equals(result.getRecordingStatus())) return result;
            pause();
        } while (System.nanoTime() < deadline);
        return result;
    }

    private void collectArtifacts(EvaluationRuntime runtime, State state) throws IOException {
        boolean vue = "vue_project".equals(state.sample.codeGenType());
        var generationSteps = state.traces.stream().filter(t -> "GENERATION".equals(t.root().getTraceKind()))
                .flatMap(t -> t.steps().stream()).toList();
        var builds = generationSteps.stream().filter(step -> "BUILD".equals(step.getStepKind())).toList();
        state.build = !vue ? Status.NOT_APPLICABLE : builds.isEmpty() ? Status.SKIPPED
                : "SUCCEEDED".equals(builds.getLast().getStatus()) ? Status.PASS
                : isEnvironment(builds.getLast()) ? Status.ERROR : Status.FAIL;
        if (state.task == null || !state.task.getStatus().terminal()) return;
        boolean success = state.task.getStatus() == TaskStatus.SUCCEEDED;
        if (success && vue && builds.isEmpty()) state.build = Status.ERROR;
        Path source = runtime.workspace().taskRoot(state.appId, state.task.getId());
        if (success && !state.task.getId().equals(runtime.workspace().publishedVersion(state.appId))) {
            throw new IOException("任务成功版本与评测产物不一致");
        }
        boolean hasFiles = false;
        if (Files.isDirectory(source)) {
            try (var files = Files.walk(source)) { hasFiles = files.anyMatch(Files::isRegularFile); }
        }
        if (success || hasFiles) {
            String name = success ? "output" : "draft";
            var snapshot = EvaluationArtifacts.freeze(source, state.directory.resolve(name));
            state.artifact = new Artifact(name, snapshot.digest(), snapshot.files(), snapshot.bytes(), !success);
            for (TraceEvidence trace : state.traces) {
                if (success && "GENERATION".equals(trace.root().getTraceKind()) && trace.root().getArtifactDigest() != null
                        && !snapshot.digest().equals(trace.root().getArtifactDigest())) throw new IOException("快照摘要与任务追踪不一致");
            }
        }
        if ("CANCEL_STREAM".equals(state.sample.fault())) {
            String preserved = TraceArtifacts.digest(runtime.workspace().published(state.appId, state.sample.codeGenType()));
            state.initialPreserved = state.initialDigest != null && state.initialDigest.equals(preserved);
        }
        if (success && !state.stopped) {
            state.browser = new EvaluationBrowser(Duration.ofSeconds(4)).verify(state.directory.resolve("output"), vue,
                    dataset.assertions(state.sample), state.directory, budget);
            state.evidenceComplete &= state.browser.evidenceComplete();
            state.diagnostics.addAll(state.browser.diagnostics());
        } else if (success) {
            state.evidenceComplete = false;
            state.diagnostics.add("批次已停止，成功产物尚未执行独立浏览器验收");
        }
    }

    private Status verdict(State state, EvaluationFixtureModels fixture) {
        if (state.stopped) return Status.CANCELLED;
        if (state.executionError || !state.evidenceComplete) return Status.ERROR;
        if ("ROUTE_MISMATCH".equals(state.outcome)) return Status.FAIL;
        if (!state.sample.expectedOutcome().equals(state.outcome)) return environmentFailure(state) ? Status.ERROR : Status.FAIL;
        boolean injectedGap = "TRACE_FAILURE".equals(state.sample.fault());
        if (!injectedGap && !"COMPLETE".equals(state.traceCompleteness)) return Status.ERROR;
        if (state.browser != null) {
            if (state.browser.load() != Status.PASS) return state.browser.load();
            if (state.browser.business() != Status.PASS && state.browser.business() != Status.NOT_APPLICABLE) return state.browser.business();
        }
        if (state.sample.fault() == null) return state.build == Status.ERROR ? Status.ERROR : Status.PASS;
        var steps = state.traces.stream().filter(t -> "GENERATION".equals(t.root().getTraceKind()))
                .flatMap(t -> t.steps().stream()).toList();
        boolean expected = switch (state.sample.fault()) {
            case "NATIVE_REGEN" -> steps.stream().filter(s -> "native_generation".equals(s.getStepName())).count() >= 2;
            case "BUILD_REPAIR" -> steps.stream().anyMatch(s -> "BUILD".equals(s.getStepKind()) && "FAILED".equals(s.getStatus()))
                    && steps.stream().anyMatch(s -> "BUILD".equals(s.getStepKind()) && "SUCCEEDED".equals(s.getStatus()));
            case "MODEL_FAILURE" -> steps.stream().anyMatch(s -> "MODEL".equals(s.getStepKind()) && "FAILED".equals(s.getStatus()));
            case "CANCEL_STREAM" -> fixture != null && fixture.streamCancelled() && state.initialPreserved;
            case "BUILD_TIMEOUT" -> steps.stream().anyMatch(s -> "BUILD".equals(s.getStepKind()));
            case "TRACE_FAILURE" -> fixture != null && "PARTIAL".equals(state.traceCompleteness);
            default -> false;
        };
        if (!expected) state.diagnostics.add("工程故障未满足预先规定的路径断言");
        return expected ? Status.PASS : Status.FAIL;
    }

    private boolean environmentFailure(State state) {
        return state.traces.stream().flatMap(t -> t.steps().stream()).anyMatch(this::isEnvironment);
    }

    private boolean isEnvironment(GenerationTraceStep step) {
        if (step.getSafeMetadata() == null) return false;
        try { return "ENVIRONMENT".equals(new com.fasterxml.jackson.databind.ObjectMapper().readTree(step.getSafeMetadata()).path("buildKind").asText()); }
        catch (IOException error) { return false; }
    }

    private static void pause() {
        try { Thread.sleep(25); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("评测等待被中断", error); }
    }

    private static Usage usage(List<TraceEvidence> traces, long actualCalls) {
        long input = 0, output = 0, inputCalls = 0, outputCalls = 0, calls = 0;
        boolean inputKnown = false, outputKnown = false, valid = true;
        for (var item : traces) {
            var root = item.root();
            calls = Math.addExact(calls, root.getModelCalls());
            inputCalls = Math.addExact(inputCalls, root.getInputReportedCalls());
            outputCalls = Math.addExact(outputCalls, root.getOutputReportedCalls());
            if (root.getKnownInputTokens() != null) { inputKnown = true; input = Math.addExact(input, root.getKnownInputTokens()); }
            if (root.getKnownOutputTokens() != null) { outputKnown = true; output = Math.addExact(output, root.getKnownOutputTokens()); }
            valid &= root.getInputReportedCalls() <= root.getModelCalls() && root.getOutputReportedCalls() <= root.getModelCalls()
                    && (root.getInputReportedCalls() == 0 || root.getKnownInputTokens() != null)
                    && (root.getOutputReportedCalls() == 0 || root.getKnownOutputTokens() != null);
        }
        boolean noCalls = actualCalls == 0 && calls == 0;
        Long knownInput = inputKnown || noCalls ? input : null;
        Long knownOutput = outputKnown || noCalls ? output : null;
        boolean complete = valid && calls == actualCalls && inputCalls == calls && outputCalls == calls
                && (noCalls || knownInput != null && knownOutput != null);
        return new Usage(actualCalls, calls, knownInput, knownOutput, inputCalls, outputCalls,
                calls == 0 ? null : (double) inputCalls / calls, calls == 0 ? null : (double) outputCalls / calls, complete);
    }

    private final class State {
        final EvaluationDataset.Case sample;
        final Path directory;
        final List<TraceEvidence> traces = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();
        String requestId;
        Long appId;
        GenerationTask task;
        String outcome = "NOT_RUN";
        Status build = Status.SKIPPED;
        Status verdict = Status.ERROR;
        String traceCompleteness = "UNAVAILABLE";
        boolean evidenceComplete = true;
        boolean executionError;
        boolean stopped;
        boolean initialPreserved;
        String initialDigest;
        Artifact artifact;
        EvaluationBrowser.Result browser;
        State(EvaluationDataset.Case sample) { this.sample = sample; this.directory = casesRoot.resolve(sample.caseId()); }
    }
}
