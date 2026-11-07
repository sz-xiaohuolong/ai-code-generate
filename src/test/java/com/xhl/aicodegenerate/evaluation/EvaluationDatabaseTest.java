package com.xhl.aicodegenerate.evaluation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 仅在显式给出的测试服务器创建随机专用库，不读取业务配置。 */
class EvaluationDatabaseTest {
    private JdbcTemplate server;
    private JdbcTemplate jdbc;
    private String schema;
    private EvaluationDatabaseConfig config;

    @BeforeEach void prepare() {
        String url = System.getProperty("evaluation.test.mysql.url");
        assertNotNull(url, "需显式设置 evaluation.test.mysql.url，指向 generation_evaluation 专用库");
        var base = EvaluationDatabaseConfig.from(Map.of("EVAL_JDBC_URL", url, "EVAL_JDBC_USER", "root", "EVAL_JDBC_PASSWORD", ""));
        schema = "generation_evaluation_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        server = new JdbcTemplate(new DriverManagerDataSource(url.replace("/" + base.schema(), "/"), "root", ""));
        server.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
        config = EvaluationDatabaseConfig.from(Map.of("EVAL_JDBC_URL", url.replace("/" + base.schema(), "/" + schema),
                "EVAL_JDBC_USER", "root", "EVAL_JDBC_PASSWORD", ""));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(config.jdbcUrl(), "root", ""));
    }

    @AfterEach void cleanup() {
        if (schema != null && server != null) server.execute("DROP DATABASE " + schema);
    }

    @Test void existingUnmarkedDataIsRejectedWithoutMutation() {
        jdbc.execute("CREATE TABLE app (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO app VALUES (19)");
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationDatabase.open(config, Path.of(".")));
        assertEquals(19L, jdbc.queryForObject("SELECT id FROM app", Long.class));
        assertEquals(1, jdbc.queryForList("SHOW TABLES").size());
    }

    @Test void emptySchemaInitializesActualTablesAndExclusiveLeaseReleases() {
        try (var database = EvaluationDatabase.open(config, Path.of("."))) {
            var owned = new JdbcTemplate(database.dataSource());
            assertEquals(schema, owned.queryForObject("SELECT DATABASE()", String.class));
            assertEquals(6, owned.queryForList("SHOW TABLES").size());
            assertEquals("READY", owned.queryForObject("SELECT state FROM evaluation_schema", String.class));
            assertTrue(owned.queryForList("SHOW COLUMNS FROM app").stream().anyMatch(row -> "generationMode".equals(row.get("Field"))));
            assertThrows(EvaluationConfigurationException.class, () -> EvaluationDatabase.open(config, Path.of(".")));
        }
        try (var reopened = EvaluationDatabase.open(config, Path.of("."))) {
            assertNotNull(reopened.dataSource());
        }
    }

    @Test void changedMarkerOrForeignTablesCannotBeSilentlyMigratedOrErased() {
        try (var ignored = EvaluationDatabase.open(config, Path.of("."))) { }
        jdbc.update("UPDATE evaluation_schema SET ddl_digest=?", "0".repeat(64));
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationDatabase.open(config, Path.of(".")));
        assertEquals("0".repeat(64), jdbc.queryForObject("SELECT ddl_digest FROM evaluation_schema", String.class));
        jdbc.execute("CREATE TABLE unexpected_customer_data (id INT)");
        assertThrows(EvaluationConfigurationException.class, () -> EvaluationDatabase.open(config, Path.of(".")));
        assertEquals(7, jdbc.queryForList("SHOW TABLES").size());
    }
}
