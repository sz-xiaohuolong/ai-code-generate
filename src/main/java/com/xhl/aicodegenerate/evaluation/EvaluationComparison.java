package com.xhl.aicodegenerate.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 只读取已导出的报告，不初始化数据库、生成任务或模型；比较资格与原始数值分别呈现。 */
public final class EvaluationComparison {
    public record Measurements(Long durationMs, Long knownInputTokens, Long knownOutputTokens,
            long inputReportedCalls, long outputReportedCalls, long tracedCalls, long actualCalls) { }
    public record CaseDifference(String caseId, boolean qualityComparable, boolean durationComparable,
            boolean usageComparable, String baselineVerdict, String candidateVerdict, List<String> reasons,
            Measurements baseline, Measurements candidate) {
        public CaseDifference { reasons = List.copyOf(reasons); }
    }
    public record Result(String baselineRunId, String candidateRunId, List<String> changes,
            List<CaseDifference> cases, EvaluationReport.Summary baselineSummary, EvaluationReport.Summary candidateSummary) {
        public Result { changes = List.copyOf(changes); cases = List.copyOf(cases); }
    }
    private EvaluationComparison() { }

    public static Result compare(Path baseline, Path candidate) throws IOException {
        var left = read(baseline);
        var right = read(candidate);
        List<String> changes = new ArrayList<>();
        var a = left.run(); var b = right.run();
        boolean dataset = Objects.equals(a.datasetDigest(), b.datasetDigest());
        boolean assertions = Objects.equals(a.assertionDigest(), b.assertionDigest());
        boolean mode = Objects.equals(a.mode(), b.mode());
        boolean source = Objects.equals(a.source().treeDigest(), b.source().treeDigest());
        boolean environment = Objects.equals(a.environment(), b.environment());
        boolean budget = a.maxModelCalls() == b.maxModelCalls() && a.deadlineSeconds() == b.deadlineSeconds();
        if (!dataset) changes.add("数据集版本或内容不同，不能严格比较质量");
        if (!assertions) changes.add("验收器版本或断言不同，不能严格比较质量");
        if (!mode) changes.add("受控回归与真实模型评测不能混作质量提升");
        if (!source) changes.add("受测源码不同，只能并列观察，不能据此归因模型改进");
        if (!environment) changes.add("运行环境或实际编译产物不同，耗时不严格可比");
        if (!budget) changes.add("调用预算或总时限不同，停止条件不一致");
        if (!Objects.equals(a.modelConfiguration(), b.modelConfiguration())) changes.add("模型配置发生变化");
        if (!Objects.equals(a.promptVersion(), b.promptVersion())) changes.add("Prompt 模板版本发生变化");
        if (!a.source().recoverable() || !b.source().recoverable()) changes.add("至少一次运行未保存可恢复源码；摘要相同不等于已具备完整复现材料");
        if (left.exitCode() == 2 || right.exitCode() == 2) changes.add("至少一次运行未完整完成；取消、未执行或证据缺口仍计入原分母");
        if (!Objects.equals(a.selected(), b.selected())) changes.add("入选用例集合不同，缺少的用例单列，不调整原运行分母");
        var before = index(left.cases());
        var after = index(right.cases());
        Set<String> ids = new TreeSet<>(before.keySet()); ids.addAll(after.keySet());
        List<CaseDifference> cases = new ArrayList<>();
        for (String id : ids) {
            var x = before.get(id); var y = after.get(id);
            List<String> reasons = new ArrayList<>();
            boolean present = x != null && y != null;
            boolean sameSpec = present && Objects.equals(x.sample(), y.sample())
                    && Objects.equals(x.initialSnapshotDigest(), y.initialSnapshotDigest());
            boolean quality = dataset && assertions && mode && sameSpec && source && environment && budget;
            if (present && (!observed(x) || !observed(y))) {
                quality = false;
                reasons.add("至少一侧未完成可判定的质量检查，不能比较质量");
            }
            if (!present) reasons.add("用例没有同时出现在两次运行中");
            else if (!sameSpec) reasons.add("用例输入、断言版本或初始产物不同");
            if (!dataset || !assertions || !mode) reasons.add("运行规格不可比，保留原结果并列展示");
            if (!source || !environment || !budget) reasons.add("源码、环境或停止条件不同，禁止严格归因");
            if (present && !Objects.equals(browserVersion(x), browserVersion(y))) {
                quality = false; reasons.add("实际浏览器版本不同");
            }
            boolean duration = quality
                    && x.evidenceComplete() && y.evidenceComplete() && "COMPLETE".equals(x.traceCompleteness()) && "COMPLETE".equals(y.traceCompleteness());
            if (present && !duration) reasons.add("至少一侧缺少完整比较资格；不计算耗时或用量差值");
            Measurements m = x == null ? null : measurements(x);
            Measurements n = y == null ? null : measurements(y);
            boolean usage = duration && completeUsage(m) && completeUsage(n);
            if (present && (!completeUsage(m) || !completeUsage(n))) reasons.add("Token 分项上报有缺口；已知值不能当作完整总量");
            cases.add(new CaseDifference(id, quality, duration, usage,
                    x == null ? "NOT_SELECTED" : x.verdict().name(), y == null ? "NOT_SELECTED" : y.verdict().name(), reasons, m, n));
        }
        return new Result(a.runId(), b.runId(), changes, cases, left.summary(), right.summary());
    }

    static EvaluationReport.Report read(Path requested) throws IOException {
        try { EvaluationWorkspace.rejectLinks(requested.toAbsolutePath().normalize()); }
        catch (EvaluationConfigurationException error) { throw new IOException("报告路径不能经过自定义符号链接", error); }
        Path root = requested.toRealPath();
        var completed = EvaluationReport.JSON.readValue(readJson(root.resolve("complete.json")), EvaluationReport.Completion.class);
        if (!"1".equals(completed.schemaVersion()) || !completed.reportComplete() || completed.files() == null
                || completed.files().size() > 100_000 || !completed.files().keySet().containsAll(Set.of("run.json", "report.json", "report.html"))) {
            throw new IOException("缺少受支持的完整报告清单");
        }
        for (var item : completed.files().entrySet()) {
            Path path = evidencePath(root, item.getKey());
            if (!item.getValue().equals(EvaluationReport.fileDigest(path))) throw new IOException("报告证据缺失或摘要不匹配");
        }
        byte[] reportBytes = readJson(root.resolve("report.json"));
        var rawReport = EvaluationReport.JSON.readTree(reportBytes);
        var report = EvaluationReport.JSON.readValue(reportBytes, EvaluationReport.Report.class);
        if (report.run() == null || report.run().source() == null || !"1".equals(report.run().schemaVersion())
                || !Objects.equals(completed.runId(), report.run().runId()) || report.cases().size() > 24
                || !EvaluationReport.JSON.readTree(readJson(root.resolve("run.json")))
                .equals(rawReport.path("run"))) {
            throw new IOException("报告与运行清单不一致");
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < report.cases().size(); index++) {
            var result = report.cases().get(index);
            String id = result.sample().caseId();
            if (!id.matches("[BE](0[1-9]|1[0-2])") || !ids.add(id)
                    || !completed.files().containsKey("cases/" + id + "/result.json")
                    || !EvaluationReport.JSON.readTree(readJson(root.resolve("cases").resolve(id).resolve("result.json")))
                    .equals(rawReport.path("cases").get(index))) {
                throw new IOException("逐用例事实缺失、重复或与汇总不一致");
            }
            EvaluationReport.validateEvidence(root, result);
        }
        if (ids.isEmpty() || !ids.equals(new HashSet<>(report.run().selected()))) throw new IOException("报告未保留全部入选用例");
        return report;
    }

    private static Path evidencePath(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.contains("\\") || relative.contains(":")) throw new IOException("无效证据引用");
        Path part = Path.of(relative);
        if (part.isAbsolute()) throw new IOException("证据路径必须相对报告目录");
        Path current = root;
        for (Path component : part) {
            if (Set.of("..", ".").contains(component.toString())) throw new IOException("证据引用不能越界");
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("证据引用不能经过符号链接");
        }
        return current;
    }

    private static byte[] readJson(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("报告或完成清单缺失");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(64 * 1024 * 1024 + 1);
            if (bytes.length > 64 * 1024 * 1024) throw new IOException("报告文件大小超过读取上限");
            return bytes;
        }
    }

    private static Map<String, EvaluationCaseRunner.Result> index(List<EvaluationCaseRunner.Result> values) {
        Map<String, EvaluationCaseRunner.Result> result = new LinkedHashMap<>();
        for (var item : values) result.put(item.sample().caseId(), item);
        return result;
    }

    private static String browserVersion(EvaluationCaseRunner.Result result) {
        return result.browser() == null ? null : result.browser().browserVersion();
    }

    private static boolean observed(EvaluationCaseRunner.Result result) {
        return Set.of(EvaluationBrowser.Status.PASS, EvaluationBrowser.Status.FAIL).contains(result.verdict());
    }

    private static Measurements measurements(EvaluationCaseRunner.Result result) {
        var usage = result.usage();
        return new Measurements(result.durationMs(), usage.knownInputTokens(), usage.knownOutputTokens(),
                usage.inputReportedCalls(), usage.outputReportedCalls(), usage.tracedCalls(), usage.actualCalls());
    }

    private static boolean completeUsage(Measurements value) {
        return value != null && value.tracedCalls() == value.actualCalls()
                && value.inputReportedCalls() == value.tracedCalls() && value.outputReportedCalls() == value.tracedCalls()
                && (value.tracedCalls() == 0 || value.knownInputTokens() != null && value.knownOutputTokens() != null);
    }
}
