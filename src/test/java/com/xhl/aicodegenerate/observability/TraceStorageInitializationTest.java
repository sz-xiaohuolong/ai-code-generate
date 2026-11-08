package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.config.JsonConfig;
import com.xhl.aicodegenerate.controller.GenerationTraceController;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.mapper.GenerationTraceMapper;
import com.xhl.aicodegenerate.mapper.GenerationTraceStepMapper;
import com.xhl.aicodegenerate.service.GenerationTraceService;
import com.xhl.aicodegenerate.service.UserService;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 复现已有业务库未执行追踪迁移，验证诊断、幂等初始化与故障后的采集恢复。 */
class TraceStorageInitializationTest {
    @Test void missingTablesCanBeInitializedWithoutInventingLostHistory() throws Exception {
        String url = System.getProperty("trace.test.mysql.url");
        assertNotNull(url, "必须显式指定隔离 MySQL 测试库");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/generation_trace_test[^?]*(\\?.*)?"));
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        String database = "generation_trace_test_init_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        try {
            String isolatedUrl = url.replaceFirst("/generation_trace_test[^?]*", "/" + database);
            var source = new DriverManagerDataSource(isolatedUrl, "root", "");
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE app(id BIGINT PRIMARY KEY,userId BIGINT NOT NULL,isDelete TINYINT NOT NULL DEFAULT 0)");
            jdbc.update("INSERT INTO app(id,userId) VALUES(7,9)");
            var config = new Configuration();
            config.addMapper(GenerationTraceMapper.class);
            config.addMapper(GenerationTraceStepMapper.class);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            var sql = new SqlSessionTemplate(factory.getObject());
            var roots = sql.getMapper(GenerationTraceMapper.class);
            var steps = sql.getMapper(GenerationTraceStepMapper.class);
            var store = new MybatisTraceStore(roots, steps, new DataSourceTransactionManager(source));
            try (var recorder = new GenerationTraceRecorder(store, Clock.systemUTC(), 100)) {
                var service = new GenerationTraceService(roots, steps, recorder);
                var users = mock(UserService.class);
                User user = new User(); user.setId(9L);
                when(users.getLoginUser(any())).thenReturn(user);
                var json = new JsonConfig().jacksonObjectMapper(new Jackson2ObjectMapperBuilder());
                var mvc = MockMvcBuilders.standaloneSetup(new GenerationTraceController(service, users))
                        .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
                String lostTask = UUID.randomUUID().toString();
                var lost = recorder.startGeneration(lostTask, 7L, 9L, "standard", "html");
                lost.finish("SUCCEEDED");
                assertTrue(recorder.awaitIdle(Duration.ofSeconds(3)));
                for (String path : new String[]{"/app/traces?appId=7", "/app/traces/by-task/" + lostTask}) {
                    mvc.perform(get(path)).andExpect(jsonPath("$.code").value(50002));
                }
                String migration = Files.readString(Path.of("sql/migrations/20261107_generation_trace.sql"));
                for (int repeat = 0; repeat < 2; repeat++) {
                    for (String statement : migration.split(";")) {
                        if (!statement.isBlank()) jdbc.execute(statement);
                    }
                }
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app WHERE id=7 AND userId=9", Integer.class));
                assertNull(roots.byTask(lostTask), "初始化不能补造故障期间丢失的历史");
                var creation = recorder.startCreation(UUID.randomUUID().toString(), 9L, "standard");
                creation.startStep(TraceSession.Kind.MODEL, "routing", null).complete(4L, 2L, "STOP");
                creation.bindApplication(7L, "html");
                creation.finish("SUCCEEDED");
                assertTrue(recorder.awaitIdle(Duration.ofSeconds(3)));
                String task = UUID.randomUUID().toString();
                var generation = recorder.startGeneration(task, 7L, 9L, "standard", "html");
                generation.startStep(TraceSession.Kind.MODEL, "model", null).complete(12L, 8L, "STOP");
                generation.finish("SUCCEEDED");
                assertTrue(recorder.awaitIdle(Duration.ofSeconds(3)));
                mvc.perform(get("/app/traces").param("appId", "7"))
                        .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.records.length()").value(2));
                mvc.perform(get("/app/traces/by-task/" + task))
                        .andExpect(jsonPath("$.code").value(0))
                        .andExpect(jsonPath("$.data.recordingStatus").value("COMPLETE"))
                        .andExpect(jsonPath("$.data.trace.creationTraceId").value(creation.traceId()));
                mvc.perform(get("/app/traces/" + generation.traceId() + "/steps"))
                        .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.records[0].stepName").value("model"));
            }
        } finally {
            // 只清理本测试随机创建的隔离库，不接触调用者指定库的数据。
            admin.execute("DROP DATABASE " + database);
        }
    }
}
