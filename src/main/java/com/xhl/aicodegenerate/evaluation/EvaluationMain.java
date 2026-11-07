package com.xhl.aicodegenerate.evaluation;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 面向维护者的独立命令入口，不启动业务应用或全量测试。 */
public final class EvaluationMain {
    private EvaluationMain() { }
    public static void main(String[] args) { System.exit(execute(args, Path.of("."), System.getenv(), System.out, System.err)); }

    static int execute(String[] args, Path repository, Map<String, String> environment, PrintStream out, PrintStream error) {
        EvaluationOptions options;
        try {
            options = EvaluationOptions.parse(args, repository);
            if (options.command() == EvaluationOptions.Command.HELP) { help(out); return 0; }
            if (options.command() == EvaluationOptions.Command.COMPARE) {
                var result = EvaluationComparison.compare(options.baseline(), options.candidate());
                out.println(EvaluationReport.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                return 0;
            }
        } catch (IOException | RuntimeException failure) {
            error.println("评测参数或输入报告无效：" + safe(failure));
            return 3;
        }
        EvaluationWorkspace workspace = null;
        boolean started = false;
        try {
            var config = EvaluationDatabaseConfig.from(environment);
            var original = EvaluationDataset.load(repository.resolve("evaluation"));
            var selection = original.select(options.mode(), options.suite());
            workspace = EvaluationWorkspace.create(repository, options.output());
            var dataset = original.freeze(workspace.directory().resolve("inputs"));
            var source = EvaluationProvenance.source(repository);
            var runtimeEnvironment = EvaluationProvenance.environment(repository);
            String promptVersion = EvaluationProvenance.promptVersion(repository);
            var budget = new EvaluationBudget(options.maxModelCalls(), options.deadline());
            EvaluationLiveModels live = options.mode() == EvaluationOptions.Mode.LIVE ? EvaluationLiveModels.from(environment, budget) : null;
            try (live; var database = EvaluationDatabase.open(config, repository)) {
                Map<String, Object> model = live == null
                        ? Map.of("protocolVersion", "1", "model", "deterministic-fixture", "usage", "人工协议夹具", "externalTools", "受控空集合")
                        : live.metadata();
                var manifest = new EvaluationReport.Manifest("1", workspace.runId(), options.mode().name().toLowerCase(Locale.ROOT),
                        options.suite().name().toLowerCase(Locale.ROOT), Instant.now().toString(), dataset.datasetDigest(), dataset.assertionDigest(),
                        source, promptVersion, model, runtimeEnvironment, options.maxModelCalls(), options.deadline().toSeconds(),
                        selection.selected().stream().map(EvaluationDataset.Case::caseId).toList(), selection.excluded());
                var report = new EvaluationReport(workspace.directory(), manifest);
                var runner = new EvaluationCaseRunner(database.dataSource(), workspace.runtime(), workspace.directory().resolve("cases"), dataset, budget);
                var finished = new CountDownLatch(1);
                Thread shutdown = new Thread(() -> {
                    budget.requestStop();
                    if (live != null) live.close();
                    try { finished.await(30, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }, "evaluation-stop");
                Runtime.getRuntime().addShutdownHook(shutdown);
                started = true;
                out.println("评测运行：" + workspace.runId() + "；模式：" + manifest.mode() + "；入选用例：" + selection.selected().size());
                List<EvaluationCaseRunner.Result> results = new ArrayList<>();
                String stopReason = null;
                try {
                    // 质量断言失败继续完成固定样本；运行／证据故障停止后续生成，并为余下用例保存未执行结果。
                    for (var sample : selection.selected()) {
                        EvaluationFixtureModels fixture = live == null ? new EvaluationFixtureModels(sample, dataset.fixture(sample)) : null;
                        var models = fixture == null ? live.models() : fixture.models(budget);
                        var result = runner.run(sample, models, fixture);
                        results.add(result);
                        report.writeCase(result);
                        out.println(sample.caseId() + "：" + result.verdict() + "；生成 " + result.generationOutcome());
                        if (result.verdict() == EvaluationBrowser.Status.ERROR && budget.stopReason() == null) {
                            stopReason = "EXECUTION_OR_EVIDENCE_ERROR";
                            budget.requestStop();
                        }
                    }
                    if (stopReason == null && budget.stopReason() != null) stopReason = budget.stopReason().name();
                    var outcome = report.finish(results, budget.modelCalls(), stopReason);
                    out.println("报告：" + workspace.directory().resolve("report.html"));
                    out.println("退出码：" + outcome.exitCode());
                    return outcome.exitCode();
                } finally {
                    finished.countDown();
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                    catch (IllegalStateException shuttingDown) { /* 进程关闭期间由已注册的钩子等待当前清理。 */ }
                }
            }
        } catch (IOException | RuntimeException failure) {
            error.println((started ? "评测未完整完成：" : "启动校验失败：") + safe(failure));
            if (workspace != null) error.println("已取得的证据保留在：" + workspace.directory());
            return started ? 2 : 3;
        }
    }

    private static String safe(Exception failure) {
        return failure instanceof EvaluationConfigurationException ? failure.getMessage()
                : "请检查参数、隔离环境与证据文件（" + failure.getClass().getSimpleName() + "）";
    }

    private static void help(PrintStream out) {
        out.println("""
                生成质量独立评测
                  ./scripts/evaluate.sh [--mode fixture|live] [--suite all|development|holdout] [--output 目录]
                  ./scripts/evaluate.sh --mode live --max-model-calls 正整数 --deadline-seconds 正整数
                  ./scripts/evaluate.sh compare --baseline 运行目录 --candidate 运行目录
                缺省：fixture、all、1000 次模型调用、1800 秒、输出到 .evidence/evaluation 下的新运行目录。
                自定义调用数和总时限必须成对提供。compare 只读取并核验已导出报告，在标准输出返回 JSON。
                运行前：Java 21、Git、Node/npm、redis-server、Chrome，以及专用 MySQL 空库或已标记评测库。
                必须显式设置 EVAL_JDBC_URL、EVAL_JDBC_USER、EVAL_JDBC_PASSWORD（允许显式空密码）；
                库名必须为 generation_evaluation 或 generation_evaluation_加受限后缀，命令不会创建或删除数据库。
                live 另需 EVAL_MODEL_BASE_URL、EVAL_MODEL_API_KEY、EVAL_MODEL_NAME；
                可选 EVAL_ROUTING_MODEL_NAME、EVAL_REASONING_MODEL_NAME、EVAL_MODEL_MAX_OUTPUT_TOKENS、EVAL_MODEL_TIMEOUT_SECONDS。
                fixture 不使用真实模型或外部素材工具；Vue 的 npm 安装仍可能访问依赖仓库。
                退出码：0=全部符合预期；1=完整报告中存在质量失败；2=预算、停止、环境或证据未完整完成；3=启动前校验失败。
                """);
    }
}
