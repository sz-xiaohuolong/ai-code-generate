package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.ai.AiCodeGenTypeRoutingService;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.model.dto.app.AppAddRequest;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.service.impl.AppServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 创建失败不能仅凭 appId 为空就推定没有保存应用。 */
class AppCreationTraceTest {
    private final List<GenerationTrace> roots = new ArrayList<>();

    @Test void savedAppIsBoundButAmbiguousSaveFailureStaysUnresolved() {
        try (var recorder = recorder()) {
            var service = service(recorder, false);
            doAnswer(call -> { call.<App>getArgument(0).setId(7L); return true; }).when(service).save(any(App.class));
            assertEquals(7L, service.addApp(request(), owner(), "00000000-0000-0000-0000-000000000001"));
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertEquals("BOUND", roots.getLast().getCreationLinkState());
            assertEquals(7L, roots.getLast().getAppId());
            doThrow(new IllegalStateException("连接在保存时断开")).when(service).save(any(App.class));
            assertThrows(IllegalStateException.class, () -> service.addApp(request(), owner(), "00000000-0000-0000-0000-000000000002"));
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertEquals("UNRESOLVED", roots.getLast().getCreationLinkState());
            assertEquals("FAILED", roots.getLast().getExecutionStatus());
        }
    }

    @Test void confirmedRoutingFailureIsReadableWithoutAppAndStorageFailureDoesNotAbortCreation() {
        try (var recorder = recorder()) {
            var service = service(recorder, true);
            assertThrows(IllegalStateException.class, () -> service.addApp(request(), owner(), "00000000-0000-0000-0000-000000000003"));
            verify(service, never()).save(any(App.class));
            assertTrue(recorder.awaitIdle(Duration.ofSeconds(2)));
            assertEquals("NO_APP", roots.getLast().getCreationLinkState());
            assertNull(roots.getLast().getAppId());
            assertFalse(roots.getLast().toString().contains("private"));
        }
        try (var broken = new GenerationTraceRecorder((root, step) -> { throw new IllegalStateException("数据库不可用"); }, Clock.systemUTC(), 16)) {
            var service = service(broken, false);
            doAnswer(call -> { call.<App>getArgument(0).setId(7L); return true; }).when(service).save(any(App.class));
            assertEquals(7L, service.addApp(request(), owner(), "00000000-0000-0000-0000-000000000004"));
        }
    }

    @Test void serverRequestIdSurvivesFailureAndIgnoresSuppliedHeader() throws Exception {
        var filter = new CreationRequestIdFilter();
        var request = new MockHttpServletRequest("POST", "/api/app/add");
        request.setContextPath("/api");
        request.addHeader("X-Request-Id", "forged");
        var response = new MockHttpServletResponse();
        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, (req, res) -> {
            assertEquals(response.getHeader("X-Request-Id"), req.getAttribute(CreationRequestIdFilter.ATTRIBUTE));
            throw new IllegalStateException("创建失败");
        }));
        assertNotEquals("forged", response.getHeader("X-Request-Id"));
        assertTrue(response.getHeader("X-Request-Id").matches("[a-f0-9-]{36}"));
    }

    private GenerationTraceRecorder recorder() {
        return new GenerationTraceRecorder((root, step) -> roots.add(root), Clock.systemUTC(), 64);
    }

    private AppServiceImpl service(GenerationTraceRecorder recorder, boolean routingFails) {
        var service = spy(new AppServiceImpl());
        var routing = mock(AiCodeGenTypeRoutingService.class);
        if (routingFails) when(routing.routeCodeGenType(anyString(), any(TraceSession.class))).thenThrow(new IllegalStateException("secret=/Users/private"));
        else when(routing.routeCodeGenType(anyString(), any(TraceSession.class))).thenReturn(CodeGenTypeEnum.HTML);
        ReflectionTestUtils.setField(service, "aiCodeGenTypeRoutingService", routing);
        ReflectionTestUtils.setField(service, "generationTraceRecorder", recorder);
        return service;
    }

    private User owner() { User user = new User(); user.setId(9L); return user; }
    private AppAddRequest request() { var request = new AppAddRequest(); request.setInitPrompt("创建一个计数器页面"); return request; }
}
