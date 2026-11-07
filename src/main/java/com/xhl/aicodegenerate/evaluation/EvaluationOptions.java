package com.xhl.aicodegenerate.evaluation;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 独立命令参数；不读取业务配置或凭据，不通过参数缺省推断真实模型模式。 */
public record EvaluationOptions(Command command, Mode mode, Suite suite, Path output,
        long maxModelCalls, Duration deadline, Path baseline, Path candidate) {
    public enum Command { RUN, COMPARE, HELP }
    public enum Mode { FIXTURE, LIVE }
    public enum Suite { ALL, DEVELOPMENT, HOLDOUT }
    private static final Set<String> RUN_FLAGS = Set.of("--mode", "--suite", "--output", "--max-model-calls", "--deadline-seconds");
    private static final Set<String> COMPARE_FLAGS = Set.of("--baseline", "--candidate");

    public static EvaluationOptions parse(String[] args, Path workingDirectory) {
        if (args.length == 1 && ("--help".equals(args[0]) || "help".equals(args[0]))) {
            return new EvaluationOptions(Command.HELP, Mode.FIXTURE, Suite.ALL, null, 0, Duration.ZERO, null, null);
        }
        Command command = args.length > 0 && "compare".equals(args[0]) ? Command.COMPARE : Command.RUN;
        Set<String> allowed = command == Command.COMPARE ? COMPARE_FLAGS : RUN_FLAGS;
        Map<String, String> flags = new HashMap<>();
        for (int i = command == Command.COMPARE ? 1 : 0; i < args.length; i += 2) {
            String flag = args[i];
            if (!allowed.contains(flag) || i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new EvaluationConfigurationException("存在未知参数或缺少参数值，请使用 --help 查看调用格式");
            }
            if (flags.putIfAbsent(flag, args[i + 1]) != null) {
                throw new EvaluationConfigurationException("参数不能重复指定");
            }
        }
        Path cwd = workingDirectory.toAbsolutePath().normalize();
        if (command == Command.COMPARE) {
            if (!flags.keySet().containsAll(COMPARE_FLAGS)) {
                throw new EvaluationConfigurationException("比较必须同时指定 --baseline 与 --candidate");
            }
            return new EvaluationOptions(command, Mode.FIXTURE, Suite.ALL, null, 0, Duration.ZERO,
                    path(cwd, flags.get("--baseline")), path(cwd, flags.get("--candidate")));
        }
        Mode mode = value(Mode.class, flags.getOrDefault("--mode", "fixture"));
        Suite suite = value(Suite.class, flags.getOrDefault("--suite", "all"));
        boolean hasCalls = flags.containsKey("--max-model-calls");
        boolean hasDeadline = flags.containsKey("--deadline-seconds");
        if ((mode == Mode.LIVE && (!hasCalls || !hasDeadline)) || hasCalls != hasDeadline) {
            throw new EvaluationConfigurationException("真实评测必须显式指定调用上限和总时限；自定义预算也需成对指定");
        }
        long calls = positive(flags.getOrDefault("--max-model-calls", "1000"));
        long seconds = positive(flags.getOrDefault("--deadline-seconds", "1800"));
        Duration deadline = Duration.ofSeconds(seconds);
        try { deadline.toNanos(); }
        catch (ArithmeticException error) { throw new EvaluationConfigurationException("总时限超出可表示范围"); }
        return new EvaluationOptions(command, mode, suite, path(cwd, flags.getOrDefault("--output", ".evidence/evaluation")),
                calls, deadline, null, null);
    }

    private static <T extends Enum<T>> T value(Class<T> type, String value) {
        try { return Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new EvaluationConfigurationException("评测模式或用例分组不受支持"); }
    }
    private static long positive(String value) {
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            long result = Long.parseLong(value);
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException error) { throw new EvaluationConfigurationException("调用上限和总时限必须为可表示的正整数"); }
    }
    private static Path path(Path cwd, String value) {
        try {
            if (value.isBlank()) throw new InvalidPathException(value, "空路径");
            return cwd.resolve(value).normalize();
        } catch (InvalidPathException error) { throw new EvaluationConfigurationException("评测路径无效"); }
    }
}
