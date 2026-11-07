package com.xhl.aicodegenerate.evaluation;

import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** 显式专用库、用途标记与排他运行租约；只初始化空库，绝不清空已有数据。 */
public final class EvaluationDatabase implements AutoCloseable {
    private static final Set<String> TABLES = Set.of("user", "app", "chat_history", "generation_trace", "generation_trace_step");
    private static final String MARKER = "evaluation_schema";
    private static final String PURPOSE = "zero-code-generation-evaluation-v1";
    private static final Pattern CREATE = Pattern.compile(
            "(?is)\\bcreate\\s+table\\s+if\\s+not\\s+exists\\s+(user|app|chat_history|generation_trace|generation_trace_step)\\s*\\([^;]+;");
    private final DriverManagerDataSource dataSource;
    private final Connection lease;
    private final AtomicBoolean closed = new AtomicBoolean();

    private EvaluationDatabase(DriverManagerDataSource dataSource, Connection lease) {
        this.dataSource = dataSource;
        this.lease = lease;
    }

    public static EvaluationDatabase open(EvaluationDatabaseConfig config, Path repository) {
        List<String> ddl = loadDdl(repository);
        String digest = DigestUtil.sha256Hex(String.join("\n", ddl));
        var source = new DriverManagerDataSource(config.jdbcUrl(), config.user(), config.password());
        Connection lease = null;
        boolean opened = false;
        try {
            lease = source.getConnection();
            if (!config.schema().equals(lease.getCatalog())) {
                throw new EvaluationConfigurationException("数据库实际连接目标与显式评测库不一致");
            }
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(lease, true));
            Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?,0)", Integer.class, config.schema());
            if (!Integer.valueOf(1).equals(acquired)) {
                throw new EvaluationConfigurationException("该专用评测库已有运行占用，请使用其他专用库或等待结束");
            }
            initialize(jdbc, config.schema(), ddl, digest);
            opened = true;
            return new EvaluationDatabase(source, lease);
        } catch (EvaluationConfigurationException error) {
            throw error;
        } catch (SQLException | RuntimeException error) {
            // 驱动异常可能带连接凭据；控制台仅输出可操作的配置诊断。
            throw new EvaluationConfigurationException("专用数据库连接或初始化失败，请检查数据库是否存在、权限及表结构；未清空已有数据");
        } finally {
            if (!opened && lease != null) closeConnection(lease);
        }
    }

    private static void initialize(JdbcTemplate jdbc, String schema, List<String> ddl, String digest) {
        Set<String> present = new HashSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=?", String.class, schema));
        if (!present.isEmpty() && !present.contains(MARKER)) {
            throw new EvaluationConfigurationException("数据库已有未标记数据，拒绝把它作为评测环境");
        }
        Set<String> allowed = new HashSet<>(TABLES);
        allowed.add(MARKER);
        if (!allowed.containsAll(present)) {
            throw new EvaluationConfigurationException("专用库含不属于本评测的表，拒绝继续写入");
        }
        if (present.isEmpty()) {
            jdbc.execute("CREATE TABLE evaluation_schema (id INT PRIMARY KEY, purpose VARCHAR(64) NOT NULL, "
                    + "ddl_digest CHAR(64) NOT NULL, state VARCHAR(16) NOT NULL) ENGINE=InnoDB");
            jdbc.update("INSERT INTO evaluation_schema VALUES(1,?,?,?)", PURPOSE, digest, "INITIALIZING");
        } else {
            var markers = jdbc.queryForList("SELECT id,purpose,ddl_digest,state FROM evaluation_schema");
            if (markers.size() != 1 || !Integer.valueOf(1).equals(markers.getFirst().get("id"))
                    || !PURPOSE.equals(markers.getFirst().get("purpose"))
                    || !digest.equals(markers.getFirst().get("ddl_digest"))) {
                throw new EvaluationConfigurationException("专用库用途或结构版本不匹配，请新建空的专用评测库；不会自动迁移或删除旧库");
            }
            Object state = markers.getFirst().get("state");
            if ("READY".equals(state)) {
                if (!present.equals(allowed)) throw new EvaluationConfigurationException("评测库表结构不完整，拒绝自动修复已用数据库");
                return;
            }
            if (!"INITIALIZING".equals(state)) throw new EvaluationConfigurationException("评测库初始化状态未知");
            for (String table : TABLES) {
                if (present.contains(table) && !jdbc.queryForList("SELECT 1 FROM `" + table + "` LIMIT 1").isEmpty()) {
                    throw new EvaluationConfigurationException("未完成初始化的评测库已有数据，请使用新的空库");
                }
            }
        }
        for (String statement : ddl) jdbc.execute(statement);
        jdbc.update("UPDATE evaluation_schema SET state='READY' WHERE id=1");
    }

    private static List<String> loadDdl(Path repository) {
        try {
            // ponytail: 当前可信脚本的五张表无含分号的字符串；只提取白名单 CREATE TABLE，永不执行 CREATE DATABASE／USE。
            // 若脚本将来引入存储过程或复杂 SQL，需改为专用迁移资源并保持同一结构校验。
            String source = Files.readString(repository.resolve("sql/create_table.sql"));
            String traces = Files.readString(repository.resolve("sql/migrations/20261107_generation_trace.sql"));
            List<String> statements = new ArrayList<>();
            Set<String> found = new HashSet<>();
            for (String script : List.of(source, traces)) {
                var matcher = CREATE.matcher(script);
                while (matcher.find()) {
                    String name = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
                    if ((script == source && name.startsWith("generation_trace")) || (script == traces && !name.startsWith("generation_trace"))) continue;
                    if (!found.add(name)) throw new EvaluationConfigurationException("评测初始化脚本含重复建表定义");
                    statements.add(matcher.group());
                }
            }
            if (!found.equals(TABLES)) throw new EvaluationConfigurationException("评测初始化脚本缺少必要建表定义");
            return List.copyOf(statements);
        } catch (IOException error) {
            throw new EvaluationConfigurationException("无法读取本仓库评测所需的建表定义");
        }
    }

    public DataSource dataSource() { return dataSource; }

    @Override public void close() {
        // 关闭持有 GET_LOCK 的专用连接即可释放租约；各业务连接由调用方管理。
        if (closed.compareAndSet(false, true)) closeConnection(lease);
    }

    private static void closeConnection(Connection connection) {
        try { connection.close(); }
        catch (SQLException error) { throw new IllegalStateException("评测数据库租约连接未能正常关闭"); }
    }
}
