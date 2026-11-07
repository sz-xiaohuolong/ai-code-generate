package com.xhl.aicodegenerate.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 版本化的固定用例与维护者断言；加载时验证全部输入，不能在模型运行后修改验收规格。 */
public final class EvaluationDataset {
    public record Case(String caseId, String datasetVersion, String split, String category,
            String generationMode, String codeGenType, String operation, String prompt,
            String fixtureRef, String fixtureDigest, String initialArtifactRef, String initialArtifactDigest,
            String initialPrompt, String assertionRef, String assertionSpecVersion, String expectedOutcome,
            List<String> allowedExecutionModes, String fault) {
        public Case { if (allowedExecutionModes != null) allowedExecutionModes = List.copyOf(allowedExecutionModes); }
    }
    public record Check(String action, String selector, String expected, String value) { }
    public record Assertions(String version, List<Check> checks, String engineering) {
        public Assertions { checks = List.copyOf(checks); }
    }
    public record Exclusion(String caseId, String reason) { }
    public record Selection(List<Case> selected, List<Exclusion> excluded) {
        public Selection { selected = List.copyOf(selected); excluded = List.copyOf(excluded); }
    }
    private record Manifest(String datasetVersion, List<Case> cases) { }
    private static final Set<String> HOLDOUT = Set.of("B02", "B04", "B06", "B07", "B09", "B11");
    private static final List<String> BUSINESS_TYPES = List.of("html", "html", "multi_file", "multi_file", "vue_project", "vue_project");
    private static final List<String> ENGINEERING_TYPES = List.of("html", "vue_project", "multi_file", "multi_file", "vue_project", "vue_project");
    private static final List<String> FAULTS = List.of("NATIVE_REGEN", "BUILD_REPAIR", "MODEL_FAILURE", "CANCEL_STREAM", "BUILD_TIMEOUT", "TRACE_FAILURE");
    private static final List<String> ENGINEERING_OUTCOMES = List.of("SUCCEEDED", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "SUCCEEDED");
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private final Path root;
    private final List<Case> cases;
    private final Map<String, Assertions> specifications;
    private final String datasetDigest;
    private final String assertionDigest;

    private EvaluationDataset(Path root, List<Case> cases, Map<String, Assertions> specifications,
            String datasetDigest, String assertionDigest) {
        this.root = root; this.cases = List.copyOf(cases); this.specifications = Map.copyOf(specifications);
        this.datasetDigest = datasetDigest; this.assertionDigest = assertionDigest;
    }

    public static EvaluationDataset load(Path directory) {
        try {
            require(!Files.isSymbolicLink(directory), "数据集根目录不能是符号链接");
            Path root = directory.toRealPath();
            var mapper = new ObjectMapper();
            byte[] manifestBytes = read(safePath(root, "suite.json"));
            Manifest manifest = mapper.readValue(manifestBytes, Manifest.class);
            require("1".equals(manifest.datasetVersion()) && manifest.cases() != null && manifest.cases().size() == 24,
                    "数据集必须为受支持版本及完整 24 条用例");
            Set<String> ids = new HashSet<>();
            Map<String, Assertions> specifications = new HashMap<>();
            var assertionManifest = new StringBuilder();
            for (Case sample : manifest.cases()) {
                validateCase(sample);
                require(ids.add(sample.caseId()), "数据集用例编号重复");
                require(sample.fixtureDigest().equals(treeDigest(safePath(root, sample.fixtureRef()))), "受控产物摘要与数据集不匹配");
                if ("modify".equals(sample.operation())) {
                    require(sample.initialArtifactDigest().equals(treeDigest(safePath(root, sample.initialArtifactRef()))), "修改用例初始产物摘要不匹配");
                }
                byte[] bytes = read(safePath(root, sample.assertionRef()));
                Assertions spec = mapper.readValue(bytes, Assertions.class);
                require(sample.assertionSpecVersion().equals(spec.version()), "验收规格版本不匹配");
                require(java.util.Objects.equals(sample.fault(), spec.engineering()), "工程断言与故障类型不匹配");
                require(spec.checks().size() <= 200 && (!"business".equals(sample.category()) || !spec.checks().isEmpty()), "业务用例必须有有界且可执行的断言");
                for (Check check : spec.checks()) validateCheck(check);
                specifications.put(sample.caseId(), spec);
                assertionManifest.append(sample.caseId()).append(':').append(digest(bytes)).append('\n');
            }
            return new EvaluationDataset(root, manifest.cases(), specifications, digest(manifestBytes),
                    digest(assertionManifest.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (EvaluationConfigurationException error) { throw error; }
        catch (IOException | RuntimeException error) { throw new EvaluationConfigurationException("无法读取或解析完整数据集，请检查文件、字段和权限", error); }
    }

    private static void validateCase(Case sample) {
        require(sample != null && sample.caseId() != null && sample.caseId().matches("[BE](0[1-9]|1[0-2])"), "数据集包含不受支持的用例编号");
        require("1".equals(sample.datasetVersion()) && "1".equals(sample.assertionSpecVersion()), "数据集或验收器版本不受支持");
        boolean business = sample.caseId().startsWith("B");
        int number = Integer.parseInt(sample.caseId().substring(1));
        int pair = (number - 1) / 2;
        String mode = number % 2 == 1 ? "standard" : "workflow";
        String type = business ? BUSINESS_TYPES.get((number - 1) % 6) : ENGINEERING_TYPES.get(pair);
        String operation = (business && number > 6) || (!business && pair == 3) ? "modify" : "create";
        require(mode.equals(sample.generationMode()) && type.equals(sample.codeGenType()) && operation.equals(sample.operation()), "用例模式、代码类型或新建／修改分配不符合已确认范围");
        require((business ? "business" : "engineering").equals(sample.category()), "业务和工程用例不得混组");
        require((HOLDOUT.contains(sample.caseId()) ? "holdout" : "development").equals(sample.split()), "开发集与留出集分配不符合 18／6 约定");
        require((business ? "SUCCEEDED" : ENGINEERING_OUTCOMES.get(pair)).equals(sample.expectedOutcome()), "用例预期终态不符合规格");
        require(java.util.Objects.equals(business ? null : FAULTS.get(pair), sample.fault()), "用例故障类型不受支持");
        require(sample.allowedExecutionModes() != null && (business ? Set.of("fixture", "live") : Set.of("fixture")).equals(new HashSet<>(sample.allowedExecutionModes())), "用例运行模式不符合隔离约束");
        require(sample.prompt() != null && !sample.prompt().isBlank() && sample.prompt().length() <= 8000, "用例必须提供有界的固定需求");
        require(sample.fixtureDigest() != null && sample.fixtureDigest().matches("[a-f0-9]{64}"), "用例缺少有效产物摘要");
        if ("modify".equals(operation)) {
            require(sample.initialArtifactDigest() != null && sample.initialArtifactDigest().matches("[a-f0-9]{64}")
                    && sample.initialPrompt() != null && !sample.initialPrompt().isBlank(), "修改用例必须固定原始需求与初始产物");
        } else {
            require(sample.initialArtifactRef() == null && sample.initialArtifactDigest() == null && sample.initialPrompt() == null, "新建用例不能隐式继承初始应用");
        }
    }

    private static void validateCheck(Check check) {
        require(check != null && check.action() != null && Set.of("click", "fill", "select", "text", "count", "visible").contains(check.action()), "验收动作不受支持，禁止执行任意脚本");
        require(check.selector() != null && !check.selector().isBlank() && check.selector().length() <= 512, "验收定位器不能为空或过长");
        boolean input = Set.of("fill", "select").contains(check.action());
        boolean expected = Set.of("text", "count", "visible").contains(check.action());
        require(input == (check.value() != null) && expected == (check.expected() != null), "验收动作参数不完整或包含多余值");
        require(check.value() == null || check.value().length() <= 4096, "验收输入过长");
        require(check.expected() == null || check.expected().length() <= 4096, "验收预期过长");
        if ("count".equals(check.action())) require(check.expected().matches("[0-9]{1,6}"), "条目数量必须是非负整数");
        if ("visible".equals(check.action())) require(Set.of("true", "false").contains(check.expected()), "可见性必须明确为 true 或 false");
    }

    public Selection select(EvaluationOptions.Mode mode, EvaluationOptions.Suite suite) {
        List<Case> selected = new ArrayList<>();
        List<Exclusion> excluded = new ArrayList<>();
        for (Case sample : cases) {
            if (mode == EvaluationOptions.Mode.LIVE && "engineering".equals(sample.category())) {
                excluded.add(new Exclusion(sample.caseId(), "工程故障用例仅在受控回归运行"));
            } else if (suite != EvaluationOptions.Suite.ALL && !suite.name().equalsIgnoreCase(sample.split())) {
                excluded.add(new Exclusion(sample.caseId(), "不属于本次选择的开发集或留出集"));
            } else selected.add(sample);
        }
        require(!selected.isEmpty(), "没有可执行用例");
        return new Selection(selected, excluded);
    }
    public List<Case> cases() { return cases; }
    /** 只复制清单实际引用的输入；复制后再次校验原先加载的版本与摘要，后续执行只读本次副本。 */
    public EvaluationDataset freeze(Path destination) {
        try {
            Path target = destination.toAbsolutePath().normalize();
            EvaluationWorkspace.rejectLinks(target);
            require(!target.startsWith(root) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS), "输入快照不能覆盖已有目录或写入原数据集");
            var files = new java.util.LinkedHashSet<Path>();
            files.add(safePath(root, "suite.json"));
            for (Case sample : cases) {
                files.add(safePath(root, sample.assertionRef()));
                for (Path directory : sample.initialArtifactRef() == null
                        ? List.of(fixture(sample)) : List.of(fixture(sample), initialArtifact(sample))) {
                    try (var paths = Files.walk(directory)) {
                        for (Path path : paths.limit(1001).toList()) {
                            require(!Files.isSymbolicLink(path), "输入快照不能包含符号链接");
                            if (!Files.isDirectory(path)) files.add(path);
                        }
                    }
                }
            }
            require(files.size() <= 10_000, "输入快照文件数量超限");
            Files.createDirectory(target);
            long bytes = 0;
            for (Path file : files) {
                byte[] content = read(file);
                bytes += content.length;
                require(bytes <= 256L * 1024 * 1024, "输入快照总大小超限");
                Path copy = target.resolve(root.relativize(file));
                Files.createDirectories(copy.getParent());
                Files.write(copy, content, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            EvaluationDataset frozen = load(target);
            require(datasetDigest.equals(frozen.datasetDigest) && assertionDigest.equals(frozen.assertionDigest), "复制期间数据集或断言发生变化");
            EvaluationArtifacts.seal(target);
            return frozen;
        } catch (EvaluationConfigurationException error) { throw error; }
        catch (IOException error) { throw new EvaluationConfigurationException("无法固定评测输入", error); }
    }
    public String datasetDigest() { return datasetDigest; }
    public String assertionDigest() { return assertionDigest; }
    public Assertions assertions(Case sample) { return specifications.get(sample.caseId()); }
    public Path fixture(Case sample) { return safePath(root, sample.fixtureRef()); }
    public Path initialArtifact(Case sample) { return sample.initialArtifactRef() == null ? null : safePath(root, sample.initialArtifactRef()); }

    private static Path safePath(Path root, String relative) {
        require(relative != null && !relative.isBlank() && relative.length() <= 512 && !relative.contains("\\") && !relative.contains(":"), "数据集引用路径无效");
        Path path = Path.of(relative);
        require(!path.isAbsolute(), "数据集引用必须为相对路径");
        Path current = root;
        for (Path segment : path) {
            require(!Set.of("..", ".").contains(segment.toString()), "数据集引用不能越界");
            current = current.resolve(segment);
            require(!Files.isSymbolicLink(current), "数据集不能通过符号链接引用外部文件");
        }
        require(current.normalize().startsWith(root) && Files.exists(current, LinkOption.NOFOLLOW_LINKS), "数据集引用不存在或越界");
        return current;
    }
    private static String treeDigest(Path directory) throws IOException {
        require(Files.isDirectory(directory), "受控产物必须为目录");
        var manifest = new StringBuilder();
        int total = 0;
        try (var walk = Files.walk(directory)) {
            var paths = walk.sorted().limit(1001).toList();
            require(paths.size() <= 1000, "受控产物文件数量超限");
            for (Path path : paths) {
                require(!Files.isSymbolicLink(path), "受控产物不能包含符号链接");
                if (Files.isDirectory(path)) continue;
                byte[] bytes = read(path); total += bytes.length;
                require(total <= 16 * 1024 * 1024, "受控产物总大小超限");
                manifest.append(directory.relativize(path).toString().replace('\\', '/')).append(':').append(digest(bytes)).append('\n');
            }
        }
        require(!manifest.isEmpty(), "受控产物不能为空");
        return digest(manifest.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] read(Path path) throws IOException {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "数据集引用必须为普通文件");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            require(bytes.length <= MAX_FILE_BYTES, "数据集文件大小超限"); return bytes;
        }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("运行环境缺少 SHA-256", error); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new EvaluationConfigurationException(message);
    }
}
