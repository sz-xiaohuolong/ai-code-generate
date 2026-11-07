package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.config.JsonConfig;
import com.xhl.aicodegenerate.controller.GenerationTraceController;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.GlobalExceptionHandler;
import com.xhl.aicodegenerate.mapper.GenerationTraceMapper;
import com.xhl.aicodegenerate.mapper.GenerationTraceStepMapper;
import com.xhl.aicodegenerate.service.GenerationTraceService;
import com.xhl.aicodegenerate.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 用真实查询服务核对 HTTP 权限、序列化与不可用响应，避免只验证控制器代理调用。 */
class GenerationTraceControllerTest {
    private static final String TRACE = "1".repeat(32);
    private static final String TASK = "00000000-0000-0000-0000-000000000001";
    private static final long APP = 9007199254740993L;
    private final GenerationTraceMapper roots = mock(GenerationTraceMapper.class);
    private final GenerationTraceStepMapper steps = mock(GenerationTraceStepMapper.class);
    private final UserService users = mock(UserService.class);
    private MockMvc mvc;

    @BeforeEach void prepare() {
        var service = new GenerationTraceService(roots, steps);
        var controller = new GenerationTraceController(service, users);
        var mapper = new JsonConfig().jacksonObjectMapper(new Jackson2ObjectMapperBuilder());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        User user = new User(); user.setId(9L);
        when(users.getLoginUser(any())).thenReturn(user);
        var trace = GenerationTrace.builder().traceId(TRACE).traceKind("GENERATION").taskId(TASK)
                .appId(APP).userId(9L).startedAt(Instant.now().minusSeconds(172800))
                .expiresAt(Instant.now().plusSeconds(60)).recordingStatus("COMPLETE")
                .executionStatus("SUCCEEDED").build();
        when(roots.find(TRACE)).thenReturn(trace);
        when(roots.byTask(TASK)).thenReturn(trace);
        when(roots.byRequest(TASK)).thenReturn(trace);
        when(roots.findApp(APP)).thenReturn(App.builder().id(APP).userId(9L).isDelete(0).build());
        when(roots.list(eq(9L), eq(APP), any(), isNull(), isNull(), eq(21))).thenReturn(List.of(trace));
    }

    @Test void fiveReadRoutesUseSameOwnerCheckAndPreserveLongIds() throws Exception {
        for (String path : List.of("/" + TRACE, "/by-task/" + TASK, "/by-request/" + TASK)) {
            mvc.perform(get("/app/traces" + path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.trace.appId").value(Long.toString(APP)))
                    .andExpect(jsonPath("$.data.recordingStatus").value("COMPLETE"));
        }
        mvc.perform(get("/app/traces").param("appId", Long.toString(APP)))
                .andExpect(jsonPath("$.data.records[0].appId").value(Long.toString(APP)));
        mvc.perform(get("/app/traces/" + TRACE + "/steps"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.records").isEmpty());
        User admin = new User(); admin.setId(10L); admin.setUserRole("admin");
        when(users.getLoginUser(any())).thenReturn(admin);
        for (String path : List.of("/" + TRACE, "/" + TRACE + "/steps", "/by-task/" + TASK, "/by-request/" + TASK)) {
            mvc.perform(get("/app/traces" + path)).andExpect(jsonPath("$.code").value(40101));
        }
        mvc.perform(get("/app/traces").param("appId", Long.toString(APP)))
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test void databaseFailureReturnsUnavailableWithoutLeakingException() throws Exception {
        when(roots.find(TRACE)).thenThrow(new DataAccessResourceFailureException("password=/Users/private"));
        mvc.perform(get("/app/traces/" + TRACE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("追踪暂不可用，请稍后重试"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }
}
