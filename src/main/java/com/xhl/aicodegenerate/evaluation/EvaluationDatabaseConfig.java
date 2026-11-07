package com.xhl.aicodegenerate.evaluation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 评测数据库必须显式指定；连接串不接受凭据、多库、脚本或任意驱动属性。 */
public record EvaluationDatabaseConfig(String jdbcUrl, String user, String password, String schema) {
    private static final Set<String> BOOLEAN_OPTIONS = Set.of("useSSL", "allowPublicKeyRetrieval");
    private static final Set<String> TIMEOUT_OPTIONS = Set.of("connectTimeout", "socketTimeout");

    public static EvaluationDatabaseConfig from(Map<String, String> environment) {
        String url = environment.get("EVAL_JDBC_URL");
        String user = environment.get("EVAL_JDBC_USER");
        String password = environment.get("EVAL_JDBC_PASSWORD");
        if (url == null || user == null || user.isBlank() || password == null) {
            throw new EvaluationConfigurationException("必须显式配置 EVAL_JDBC_URL、EVAL_JDBC_USER、EVAL_JDBC_PASSWORD（允许空密码）");
        }
        try {
            if (!url.startsWith("jdbc:mysql://")) throw new URISyntaxException("", "协议不支持");
            URI uri = new URI(url.substring("jdbc:".length()));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawPath() == null
                    || !uri.getRawPath().matches("/generation_evaluation(?:_[a-z0-9_]{1,32})?")) {
                throw new URISyntaxException("", "连接目标不受支持");
            }
            validateOptions(uri.getRawQuery());
            return new EvaluationDatabaseConfig(url, user, password, uri.getRawPath().substring(1));
        } catch (URISyntaxException error) {
            // 不附带原 URL 或异常，避免非法连接串中的秘密进入报告。
            throw new EvaluationConfigurationException("评测仅允许单个 MySQL 主机及 generation_evaluation 专用库，不允许 URL 凭据或额外路径");
        }
    }

    private static void validateOptions(String query) {
        if (query == null) return;
        Set<String> seen = new HashSet<>();
        for (String part : query.split("&", -1)) {
            String[] pair = part.split("=", -1);
            if (pair.length != 2 || !seen.add(pair[0])) invalidOptions();
            String key = pair[0];
            String value = pair[1];
            if (BOOLEAN_OPTIONS.contains(key)) {
                if (!Set.of("true", "false").contains(value)) invalidOptions();
            } else if (TIMEOUT_OPTIONS.contains(key)) {
                if (!value.matches("[1-9][0-9]{0,7}")) invalidOptions();
            } else if (!("serverTimezone".equals(key) && "UTC".equals(value))) {
                invalidOptions();
            }
        }
    }

    private static void invalidOptions() {
        throw new EvaluationConfigurationException("评测连接参数仅允许布尔 TLS 选项、正整数连接超时及 UTC 时区，且不能重复");
    }

    @Override public String toString() {
        return "EvaluationDatabaseConfig[schema=" + schema + ", credentials=已隐藏]";
    }
}
