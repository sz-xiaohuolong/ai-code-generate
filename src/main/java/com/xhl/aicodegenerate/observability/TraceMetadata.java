package com.xhl.aicodegenerate.observability;

import cn.hutool.json.JSONUtil;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** 白名单投影；不接受原始请求、回复、异常消息或工具参数对象。 */
public final class TraceMetadata {
    private TraceMetadata() { }

    public static String relativePath(String value) {
        if (value == null || value.length() > 512 || value.contains(":") || value.contains("\\")
                || value.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        try {
            Path path = Path.of(value);
            if (path.isAbsolute()) {
                return null;
            }
            for (Path part : path) {
                if ("..".equals(part.toString())) {
                    return null;
                }
            }
            return path.normalize().toString();
        } catch (InvalidPathException error) {
            return null;
        }
    }

    public static String json(Map<String, ?> values) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (values != null) {
            Object tool = values.get("toolName");
            if (tool instanceof String name && name.matches("[a-zA-Z][a-zA-Z0-9_]{0,127}")) {
                safe.put("toolName", name);
            }
            Object path = values.get("relativePath");
            if (path instanceof String name) {
                String relative = relativePath(name);
                if (relative != null) {
                    safe.put("relativePath", relative);
                }
            }
            for (String flag : new String[]{"truncated", "metadataOmitted"}) {
                if (Boolean.TRUE.equals(values.get(flag))) {
                    safe.put(flag, true);
                }
            }
            Object buildKind = values.get("buildKind");
            if (buildKind instanceof String kind && java.util.Set.of("NONE", "CODE", "ENVIRONMENT", "TIMEOUT", "CANCELLED").contains(kind)) {
                safe.put("buildKind", kind);
            }
        }
        return JSONUtil.toJsonStr(safe);
    }

    public static String errorCategory(Throwable error) {
        for (int depth = 0; depth < 5 && (error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null; depth++) {
            error = error.getCause();
        }
        if (error instanceof CancellationException || error instanceof InterruptedException) {
            return "CANCELLED";
        }
        if (error instanceof TimeoutException) {
            return "TIMEOUT";
        }
        return "DEPENDENCY_ERROR";
    }

    public static String errorMessage(Throwable error) {
        return switch (errorCategory(error)) {
            case "CANCELLED" -> "操作已取消";
            case "TIMEOUT" -> "操作达到时限";
            default -> "执行失败，请结合步骤类别定位";
        };
    }
}
