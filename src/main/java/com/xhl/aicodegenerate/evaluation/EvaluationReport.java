package com.xhl.aicodegenerate.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhl.aicodegenerate.observability.TraceArtifacts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import static com.xhl.aicodegenerate.evaluation.EvaluationBrowser.Status;

/** 本地报告是评测事实的导出，不回写生成任务或改变应用成功版本。 */
public final class EvaluationReport {
    public record Source(String gitRevision, String treeDigest, boolean dirty, boolean recoverable, String limitation) { }
    public record Manifest(String schemaVersion, String runId, String mode, String suite, String startedAt,
            String datasetDigest, String assertionDigest, Source source, String promptVersion,
            Map<String, Object> modelConfiguration, Map<String, String> environment, long maxModelCalls,
            long deadlineSeconds, List<String> selected, List<EvaluationDataset.Exclusion> excluded) {
        public Manifest {
            modelConfiguration = Map.copyOf(modelConfiguration); environment = Map.copyOf(environment);
            selected = List.copyOf(selected); excluded = List.copyOf(excluded);
        }
    }
    public record Group(long planned, long passed, long failed, long errors, long cancelled, long skipped,
            long traceMissing, long evidenceMissing, long comparisonEligible) { }
    public record Summary(Group business, Group engineering, long excluded, long modelCalls, String usageSource,
            Map<String, Long> generationOutcomes) { }
    public record Report(Manifest run, List<EvaluationCaseRunner.Result> cases, Summary summary, int exitCode, String finishedAt) {
        public Report { cases = List.copyOf(cases); }
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public Map<String, Statistics> statistics() {
            Map<String, Statistics> groups = new TreeMap<>();
            for (String category : List.of("business", "engineering")) {
                var observed = cases.stream().filter(item -> category.equals(item.sample().category())
                        && item.evidenceComplete() && "COMPLETE".equals(item.traceCompleteness())
                        && (item.verdict() == Status.PASS || item.verdict() == Status.FAIL)).toList();
                var usage = observed.stream().filter(item -> item.comparisonEligible() && item.usage().complete()).toList();
                groups.put(category, new Statistics(
                        distribution(observed.stream().map(EvaluationCaseRunner.Result::durationMs).toList()),
                        distribution(usage.stream().map(EvaluationCaseRunner.Result::modelCalls).toList()),
                        distribution(usage.stream().map(item -> item.usage().knownInputTokens()).toList()),
                        distribution(usage.stream().map(item -> item.usage().knownOutputTokens()).toList())));
            }
            return Map.copyOf(groups);
        }
    }
    public record Distribution(int samples, Long min, java.math.BigDecimal median, Long max) { }
    public record Statistics(Distribution durationMs, Distribution modelCalls, Distribution inputTokens, Distribution outputTokens) { }

    private static Distribution distribution(List<Long> values) {
        var sorted = values.stream().filter(java.util.Objects::nonNull).sorted().toList();
        int size = sorted.size();
        if (size == 0) return new Distribution(0, null, null, null);
        var middle = java.math.BigDecimal.valueOf(sorted.get(size / 2));
        if (size % 2 == 0) middle = middle.add(java.math.BigDecimal.valueOf(sorted.get(size / 2 - 1)))
                .divide(java.math.BigDecimal.valueOf(2));
        return new Distribution(size, sorted.getFirst(), middle, sorted.getLast());
    }
    public record Completion(String schemaVersion, String runId, boolean reportComplete, boolean evidenceComplete,
            String stopReason, Map<String, String> files) { }
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final Path directory;
    private final Manifest manifest;

    public EvaluationReport(Path directory, Manifest manifest) throws IOException {
        if (!"1".equals(manifest.schemaVersion()) || manifest.selected().isEmpty()
                || new HashSet<>(manifest.selected()).size() != manifest.selected().size()
                || manifest.selected().stream().anyMatch(id -> !validId(id))) {
            throw new EvaluationConfigurationException("运行清单缺少唯一且有效的入选用例");
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.manifest = manifest;
        EvaluationWorkspace.rejectLinks(this.directory);
        Files.createDirectories(this.directory);
        writeAtomic(this.directory.resolve("run.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
    }

    public void writeCase(EvaluationCaseRunner.Result result) throws IOException {
        String id = result.sample().caseId();
        if (!manifest.selected().contains(id)) throw new IOException("结果不属于本次运行的入选用例");
        Path path = directory.resolve("cases").resolve(id);
        EvaluationWorkspace.rejectLinks(path);
        Files.createDirectories(path);
        validateEvidence(directory, result);
        writeAtomic(path.resolve("result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
    }

    public Report finish(List<EvaluationCaseRunner.Result> results, long modelCalls, String stopReason) throws IOException {
        Set<String> ids = new HashSet<>();
        for (var result : results) {
            String id = result.sample().caseId();
            if (!validId(id) || !ids.add(id)) throw new IOException("报告存在无效或重复用例");
            Path saved = directory.resolve("cases").resolve(id).resolve("result.json");
            if (!Files.isRegularFile(saved, LinkOption.NOFOLLOW_LINKS)
                    || !JSON.readTree(JSON.writeValueAsBytes(result)).equals(JSON.readTree(saved.toFile()))) throw new IOException("逐用例结果缺失或被修改");
            validateEvidence(directory, result);
        }
        if (!ids.equals(new HashSet<>(manifest.selected()))) throw new IOException("报告必须列出全部入选用例，包括未执行用例");
        var summary = new Summary(group(results, "business"), group(results, "engineering"), manifest.excluded().size(), modelCalls,
                "fixture".equals(manifest.mode()) ? "FIXTURE_PROTOCOL" : "PROVIDER_REPORTED",
                results.stream().collect(java.util.stream.Collectors.groupingBy(EvaluationCaseRunner.Result::generationOutcome,
                        TreeMap::new, java.util.stream.Collectors.counting())));
        int code = exitCode(results, stopReason);
        var report = new Report(manifest, results, summary, code, Instant.now().toString());
        writeAtomic(directory.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        writeAtomic(directory.resolve("report.html"), html(report).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, String> files = evidenceDigests();
        var completion = new Completion("1", manifest.runId(), true,
                results.stream().allMatch(EvaluationCaseRunner.Result::evidenceComplete), stopReason, files);
        writeAtomic(directory.resolve("complete.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(completion));
        return report;
    }

    static void validateEvidence(Path directory, EvaluationCaseRunner.Result result) throws IOException {
        Path caseRoot = directory.resolve("cases").resolve(result.sample().caseId());
        if (result.initialSnapshotDigest() != null
                && !result.initialSnapshotDigest().equals(TraceArtifacts.digest(caseRoot.resolve("initial")))) {
            throw new IOException("初始产物快照摘要不匹配");
        }
        if (result.evidenceComplete() && "SUCCEEDED".equals(result.generationOutcome())
                && (result.artifact() == null || result.browser() == null)) throw new IOException("成功产物或必要浏览器证据未保存");
        if (result.artifact() != null) {
            var artifact = result.artifact();
            if (!Set.of("output", "draft").contains(artifact.relativePath())
                    || !artifact.digest().equals(TraceArtifacts.digest(caseRoot.resolve(artifact.relativePath())))) {
                throw new IOException("报告产物引用或摘要不匹配");
            }
        }
        if (result.browser() != null && result.browser().screenshot() != null) {
            if (!"browser.png".equals(result.browser().screenshot())
                    || !Files.isRegularFile(caseRoot.resolve("browser.png"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("浏览器截图引用无效或文件缺失");
            }
        }
        if (result.browser() != null && result.browser().evidenceComplete() && result.browser().screenshot() == null) {
            throw new IOException("浏览器不能在缺少截图时声称证据完整");
        }
    }

    private Group group(List<EvaluationCaseRunner.Result> results, String category) {
        var values = results.stream().filter(result -> category.equals(result.sample().category())).toList();
        return new Group(values.size(), count(values, Status.PASS), count(values, Status.FAIL), count(values, Status.ERROR),
                count(values, Status.CANCELLED), count(values, Status.SKIPPED),
                values.stream().filter(result -> !"COMPLETE".equals(result.traceCompleteness())).count(),
                values.stream().filter(result -> !result.evidenceComplete()).count(),
                values.stream().filter(EvaluationCaseRunner.Result::comparisonEligible).count());
    }

    private long count(List<EvaluationCaseRunner.Result> values, Status status) {
        return values.stream().filter(result -> result.verdict() == status).count();
    }

    private int exitCode(List<EvaluationCaseRunner.Result> values, String stopReason) {
        if (stopReason != null || values.stream().anyMatch(result -> !result.evidenceComplete()
                || Set.of(Status.ERROR, Status.CANCELLED, Status.SKIPPED).contains(result.verdict())
                || (!"COMPLETE".equals(result.traceCompleteness()) && !"TRACE_FAILURE".equals(result.sample().fault())))) return 2;
        return values.stream().allMatch(result -> result.verdict() == Status.PASS) ? 0 : 1;
    }

    private Map<String, String> evidenceDigests() throws IOException {
        Map<String, String> digests = new TreeMap<>();
        for (String filename : List.of("run.json", "report.json", "report.html")) digests.put(filename, fileDigest(directory.resolve(filename)));
        for (String name : List.of("inputs", "cases")) {
            Path root = directory.resolve(name);
            if (!Files.exists(root)) continue;
            try (var paths = Files.walk(root)) {
                var entries = paths.limit(100_001).toList();
                if (entries.size() > 100_000) throw new IOException("报告证据条目数量超限");
                for (Path path : entries) {
                    if (Files.isSymbolicLink(path)) throw new IOException("评测证据不能包含符号链接");
                    if (Files.isRegularFile(path)) {
                        if (digests.size() >= 100_000) throw new IOException("报告证据文件数量超限");
                        digests.put(directory.relativize(path).toString().replace('\\', '/'), fileDigest(path));
                    }
                }
            }
        }
        return digests;
    }

    static void writeAtomic(Path path, byte[] bytes) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("已有评测结果不能被覆盖");
        Path temporary = path.resolveSibling("." + path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    static String fileDigest(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("证据必须为普通文件");
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("运行时缺少 SHA-256", error); }
    }

    private static boolean validId(String id) { return id != null && id.matches("[BE](0[1-9]|1[0-2])"); }
    static String escape(Object value) {
        return String.valueOf(value == null ? "未知" : value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String html(Report report) {
        var out = new StringBuilder("""
                <!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>生成质量评测报告</title><style>body{font:16px system-ui;margin:32px;color:#172033;line-height:1.6}table{border-collapse:collapse;width:100%;margin:20px 0}th,td{border:1px solid #cbd5e1;padding:8px;text-align:left;vertical-align:top}th{background:#f1f5f9}code{overflow-wrap:anywhere}details{max-width:700px}select{font:inherit;padding:6px}.scroll{overflow:auto}.note{padding:12px;background:#fff7df}</style></head><body><h1>生成质量评测报告</h1>
                """);
        out.append("<p>").append("fixture".equals(manifest.mode()) ? "受控回归：验证工程链路；Token 为人工协议夹具，不代表真实模型质量或费用。" : "真实模型评测：仅代表本次固定样本；Token 采用供应商实际返回值，不估算金额。")
                .append("</p><p>运行编号：<code>").append(escape(manifest.runId())).append("</code>；退出码：").append(report.exitCode())
                .append("；实际模型调用：").append(report.summary().modelCalls()).append("</p>");
        if (!manifest.source().recoverable()) out.append("<p class='note'>可复现限制：").append(escape(manifest.source().limitation())).append("</p>");
        out.append("<table><thead><tr><th>分组</th><th>入选</th><th>通过</th><th>失败</th><th>环境／执行错误</th><th>取消</th><th>未执行</th><th>追踪缺口</th><th>证据缺口</th><th>完整可比</th></tr></thead><tbody>");
        groupRow(out, "业务", report.summary().business()); groupRow(out, "工程故障", report.summary().engineering());
        out.append("</tbody></table><p>未入选：").append(report.summary().excluded()).append("。业务统计分母包含入选的失败、错误、取消及未执行用例。</p>")
                .append("<p>实际生成终态（与工程预期是否通过分开）：").append(escape(report.summary().generationOutcomes())).append("</p>");
        out.append("<h2>范围与中位数</h2><p>仅描述本次固定样本，不代表跨场景性能提升。耗时排除追踪／证据缺口及未完成判定的用例；调用数与 Token 仅统计完整可比样本。有效样本为零时显示未知。</p>")
                .append("<div class='scroll'><table><thead><tr><th>分组</th><th>指标</th><th>有效样本</th><th>最小值</th><th>中位数</th><th>最大值</th></tr></thead><tbody>");
        var statistics = report.statistics();
        for (String category : List.of("business", "engineering")) {
            var stats = statistics.get(category);
            String label = "business".equals(category) ? "业务" : "工程故障";
            distributionRow(out, label, "耗时（ms）", stats.durationMs());
            distributionRow(out, label, "模型调用次数", stats.modelCalls());
            distributionRow(out, label, "输入 Token", stats.inputTokens());
            distributionRow(out, label, "输出 Token", stats.outputTokens());
        }
        out.append("</tbody></table></div><label>筛选结果 <select id='filter'><option value=''>全部</option><option>PASS</option><option>FAIL</option><option>ERROR</option><option>CANCELLED</option><option>SKIPPED</option></select></label>")
                .append("<div class='scroll'><table><thead><tr><th>用例</th><th>生成终态</th><th>构建／页面／业务</th><th>判定</th><th>耗时／调用</th><th>Token 上报</th><th>完整性与证据</th></tr></thead><tbody id='cases'>");
        for (var result : report.cases()) {
            String id = result.sample().caseId();
            out.append("<tr data-verdict='").append(result.verdict()).append("'><td>").append(id).append("<br>").append(escape(result.sample().category())).append(" / ").append(escape(result.sample().split()))
                    .append("</td><td>").append(escape(result.generationOutcome())).append("</td><td>").append(result.build()).append(" / ")
                    .append(result.browser() == null ? "SKIPPED / SKIPPED" : result.browser().load() + " / " + result.browser().business())
                    .append("</td><td>").append(result.verdict()).append("</td><td>").append(result.durationMs()).append(" ms / ").append(result.modelCalls()).append(" 次</td><td>");
            usage(out, result);
            out.append("</td><td>追踪：").append(escape(result.traceCompleteness())).append("；证据：").append(result.evidenceComplete() ? "完整" : "缺失")
                    .append("；完整可比：").append(result.comparisonEligible() ? "是" : "否")
                    .append("<br><a href='cases/").append(id).append("/result.json'>逐用例事实</a>");
            if (result.browser() != null && result.browser().screenshot() != null) out.append(" · <a href='cases/").append(id).append("/browser.png'>浏览器截图</a>");
            out.append("<details><summary>断言与诊断</summary><ul>");
            for (String message : result.diagnostics()) out.append("<li>").append(escape(message)).append("</li>");
            if (result.browser() != null) for (var step : result.browser().steps()) {
                out.append("<li>").append(step.number()).append(". ").append(escape(step.check().action())).append(" ").append(escape(step.check().selector()))
                        .append(" → ").append(step.status()).append("；预期：").append(escape(step.check().expected())).append("；观察：").append(escape(step.observed())).append("</li>");
            }
            if (result.artifact() != null) out.append("<li>").append(result.artifact().draft() ? "失败草稿" : "成功快照").append("摘要：<code>").append(escape(result.artifact().digest())).append("</code></li>");
            out.append("</ul></details></td></tr>");
        }
        out.append("</tbody></table></div><details><summary>未入选用例与原因</summary><ul>");
        for (var excluded : manifest.excluded()) out.append("<li>").append(escape(excluded.caseId())).append("：").append(escape(excluded.reason())).append("</li>");
        out.append("</ul></details><script>document.querySelector('#filter').addEventListener('change',function(){for(const row of document.querySelectorAll('#cases tr'))row.hidden=!!this.value&&row.dataset.verdict!==this.value;});</script></body></html>");
        return out.toString();
    }

    private void usage(StringBuilder out, EvaluationCaseRunner.Result result) {
        var usage = result.usage();
        out.append("输入 ").append(escape(usage.knownInputTokens())).append("（").append(usage.inputReportedCalls()).append('/').append(usage.tracedCalls())
                .append(" 次上报）<br>输出 ").append(escape(usage.knownOutputTokens())).append("（").append(usage.outputReportedCalls()).append('/').append(usage.tracedCalls()).append(" 次上报）");
    }

    private void distributionRow(StringBuilder out, String group, String metric, Distribution values) {
        out.append("<tr><td>").append(group).append("</td><td>").append(metric).append("</td><td>")
                .append(values.samples()).append("</td><td>").append(escape(values.min())).append("</td><td>")
                .append(escape(values.median())).append("</td><td>").append(escape(values.max())).append("</td></tr>");
    }

    private void groupRow(StringBuilder out, String label, Group group) {
        out.append("<tr><td>").append(label).append("</td>");
        for (long value : new long[]{group.planned(), group.passed(), group.failed(), group.errors(), group.cancelled(), group.skipped(),
                group.traceMissing(), group.evidenceMissing(), group.comparisonEligible()}) out.append("<td>").append(value).append("</td>");
        out.append("</tr>");
    }
}
