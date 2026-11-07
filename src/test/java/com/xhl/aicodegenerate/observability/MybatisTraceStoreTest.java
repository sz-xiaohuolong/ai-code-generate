package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.mapper.GenerationTraceMapper;
import com.xhl.aicodegenerate.mapper.GenerationTraceStepMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 使用显式指定的隔离 MySQL 验证真实 SQL、事务与删除竞态；不加载业务配置。 */
class MybatisTraceStoreTest {
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private JdbcTemplate jdbc;
    private MybatisTraceStore store;
    private GenerationTraceMapper roots;
    private GenerationTraceStepMapper steps;
    private DriverManagerDataSource dataSource;

    @BeforeEach void prepare() throws Exception {
        String url = System.getProperty("trace.test.mysql.url");
        assertNotNull(url, "必须显式传入专用隔离测试库地址");
        assertTrue(url.contains("/generation_trace_test"), "仅允许专用追踪测试库");
        dataSource = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS app (id BIGINT PRIMARY KEY, userId BIGINT NOT NULL, isDelete TINYINT NOT NULL DEFAULT 0)");
        String migration = Files.readString(Path.of("sql/migrations/20261107_generation_trace.sql"));
        for (int repetition = 0; repetition < 2; repetition++) {
            for (String statement : migration.split(";")) {
                if (!statement.isBlank()) jdbc.execute(statement);
            }
        }
        jdbc.execute("DELETE FROM generation_trace_step");
        jdbc.execute("DELETE FROM generation_trace");
        jdbc.execute("DELETE FROM app");
        jdbc.update("INSERT INTO app(id, userId) VALUES(7,9)");
        var configuration = new Configuration();
        configuration.addMapper(GenerationTraceMapper.class);
        configuration.addMapper(GenerationTraceStepMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var template = new SqlSessionTemplate(factory.getObject());
        roots = template.getMapper(GenerationTraceMapper.class);
        steps = template.getMapper(GenerationTraceStepMapper.class);
        store = new MybatisTraceStore(roots, steps, new DataSourceTransactionManager(dataSource), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void generationLinksAvailableCreationAndKeepsLinkAcrossUpdates() {
        try (var recorder = recorder()) {
            var creation = recorder.startCreation("10000000-0000-0000-0000-000000000001", 9L, "standard");
            creation.bindApplication(7L, "html");
            creation.finish("SUCCEEDED");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            var generation = recorder.startGeneration("10000000-0000-0000-0000-000000000002", 7L, 9L, "standard", "html");
            generation.startStep(TraceSession.Kind.MODEL, "model", null).succeed();
            generation.finish("SUCCEEDED");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertEquals(creation.traceId(), roots.find(generation.traceId()).getCreationTraceId());
            jdbc.update("UPDATE generation_trace SET expires_at='2026-11-07 12:00:00' WHERE trace_id=?", creation.traceId());
            var later = recorder.startGeneration("10000000-0000-0000-0000-000000000003", 7L, 9L, "standard", "html");
            later.finish("SUCCEEDED");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertNull(roots.find(later.traceId()).getCreationTraceId());
        }
    }

    @Test void writesUtcAndIdempotentRevisionsWithoutLosingModelUsage() {
        try (var recorder = recorder()) {
            var session = recorder.startGeneration("00000000-0000-0000-0000-000000000001", 7L, 9L, "standard", "html");
            var step = session.startStep(TraceSession.Kind.MODEL, "model", null);
            step.complete(12L, null, "STOP");
            step.complete(12L, 8L, "STOP");
            session.finish("SUCCEEDED");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(3)));
            GenerationTrace result = roots.find(session.traceId());
            assertNotNull(result);
            assertEquals("COMPLETE", result.getRecordingStatus());
            assertEquals(NOW, result.getStartedAt());
            assertEquals("2026-11-07 12:00:00", jdbc.queryForObject("SELECT DATE_FORMAT(started_at, '%Y-%m-%d %H:%i:%s') FROM generation_trace", String.class));
            assertEquals(1, result.getModelCalls());
            assertEquals(8L, result.getKnownOutputTokens());
            assertEquals(1, steps.list(session.traceId(), 0, 100).size());
            var stale = result.toBuilder().revision(1).recordingStatus("COLLECTING").knownOutputTokens(null).build();
            store.persist(stale, null);
            assertEquals(8L, roots.find(session.traceId()).getKnownOutputTokens());
        }
    }

    @Test void rejectsLateWritesAndLateCreationBindingAfterDeletion() {
        try (var recorder = recorder()) {
            var creation = recorder.startCreation("00000000-0000-0000-0000-000000000002", 9L, "standard");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            jdbc.update("UPDATE app SET isDelete=1 WHERE id=7");
            creation.bindApplication(7L, "html");
            creation.finish("SUCCEEDED");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertNull(roots.find(creation.traceId()));
            store.persist(creation.snapshot(), null);
            assertNull(roots.find(creation.traceId()));
        }
    }

    @Test void detectsSequenceGapAndCannotUpgradePersistedPartial() {
        try (var recorder = recorder()) {
            var trace = recorder.startGeneration("00000000-0000-0000-0000-000000000003", 7L, 9L, "standard", "html");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            var value = trace.snapshot();
            value.setRevision(5);
            value.setLastPersistedSequence(5);
            value.setRecordingStatus("COMPLETE");
            value.setExecutionStatus("SUCCEEDED");
            store.persist(value, null);
            assertEquals("PARTIAL", roots.find(trace.traceId()).getRecordingStatus());
            value.setRevision(6);
            value.setLastPersistedSequence(6);
            store.persist(value, null);
            assertEquals("PARTIAL", roots.find(trace.traceId()).getRecordingStatus());
        }
    }

    @Test void cleanupHonorsSevenDaysAndRemovesStepsFirst() {
        try (var recorder = recorder()) {
            var trace = recorder.startGeneration("00000000-0000-0000-0000-000000000004", 7L, 9L, "standard", "html");
            trace.startStep(TraceSession.Kind.MODEL, "model", null).succeed();
            trace.finish("SUCCEEDED");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            store.cleanup();
            assertNotNull(roots.find(trace.traceId()));
            jdbc.update("UPDATE generation_trace SET expires_at='2026-11-07 12:00:00'");
            store.cleanup();
            assertNull(roots.find(trace.traceId()));
            assertTrue(steps.list(trace.traceId(), 0, 100).isEmpty());
            store.persist(trace.snapshot(), null);
            // 实际事件本身若未到期，清理后重放必须有旧根才能更新，不能重建终态记录。
            assertNull(roots.find(trace.traceId()));
        }
    }

    @Test void writerWaitsForDeletionCommitAndNeverResurrectsAppTrace() throws Exception {
        var transaction = dataSource.getConnection();
        transaction.setAutoCommit(false);
        transaction.createStatement().executeUpdate("UPDATE app SET isDelete=1 WHERE id=7");
        try (var recorder = recorder()) {
            var session = recorder.startGeneration("00000000-0000-0000-0000-000000000005", 7L, 9L, "standard", "html");
            session.finish("SUCCEEDED");
            assertFalse(recorder.awaitIdle(Duration.ofMillis(100)));
            transaction.commit();
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(3)));
            assertNull(roots.find(session.traceId()));
        } finally { transaction.close(); }
    }

    @Test void cleanupCallbackCannotRemoveTraceWhenAppDeletionRollsBack() {
        try (var recorder = recorder(); var maintenance = new GenerationTraceMaintenance(store, recorder, true, 60)) {
            var trace = recorder.startGeneration("00000000-0000-0000-0000-000000000006", 7L, 9L, "standard", "html");
            trace.finish("SUCCEEDED");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            var transaction = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource));
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE app SET isDelete=1 WHERE id=7");
                maintenance.applicationDeleted(7L);
                status.setRollbackOnly();
            });
            maintenance.tick();
            assertNotNull(roots.find(trace.traceId()));
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE app SET isDelete=1 WHERE id=7");
                maintenance.applicationDeleted(7L);
            });
            maintenance.tick();
            assertNull(roots.find(trace.traceId()));
        }
    }

    @Test void missingFinalWriteIsMarkedWithoutRecreatingCleanedRoot() {
        try (var recorder = recorder()) {
            var trace = recorder.startGeneration("00000000-0000-0000-0000-000000000007", 7L, 9L, "standard", "html");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            store.markIncomplete(trace.traceId(), recorder.instanceId());
            trace.finish("SUCCEEDED");
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertEquals("PARTIAL", roots.find(trace.traceId()).getRecordingStatus());
            jdbc.update("UPDATE app SET isDelete=1 WHERE id=7");
            store.cleanupApp(7L);
            store.markIncomplete(trace.traceId(), recorder.instanceId());
            assertNull(roots.find(trace.traceId()));
        }
    }

    @Test void recoveryPreservesUnknownTimingAndDoesNotOverwriteNewEvents() {
        try (var recorder = recorder()) {
            var trace = recorder.startGeneration("00000000-0000-0000-0000-000000000008", 7L, 9L, "standard", "html");
            trace.startStep(TraceSession.Kind.MODEL, "model", null);
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            var before = roots.find(trace.traceId());
            assertEquals(1, roots.unfinished(null, NOW, 100).size());
            store.recover(before, "UNKNOWN");
            var result = roots.find(trace.traceId());
            assertEquals("PARTIAL", result.getRecordingStatus());
            assertEquals("UNKNOWN", result.getExecutionStatus());
            assertNull(result.getDurationMs());
            assertNull(result.getFinishedAt());
            var step = steps.list(trace.traceId(), 0, 100).getFirst();
            assertEquals("UNKNOWN", step.getStatus());
            assertNull(step.getFinishedAt());
            assertNull(step.getDurationMs());
            assertNull(step.getInputTokens());
            assertTrue(roots.unfinished(null, NOW, 100).isEmpty());
            store.recover(before, "SUCCEEDED");
            assertEquals("UNKNOWN", roots.find(trace.traceId()).getExecutionStatus());
        }
    }

    private GenerationTraceRecorder recorder() {
        return new GenerationTraceRecorder(store, Clock.fixed(NOW, ZoneOffset.UTC), 32);
    }
}
