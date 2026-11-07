package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.mapper.GenerationTraceMapper;
import com.xhl.aicodegenerate.mapper.GenerationTraceStepMapper;
import com.xhl.aicodegenerate.service.GenerationTraceService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在查询边界核对当前所有者、过期及创建关联状态，不依赖任务快照。 */
class GenerationTraceServiceTest {
    private static final String ID = "1".repeat(32);
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private final GenerationTraceMapper roots = mock(GenerationTraceMapper.class);
    private final GenerationTraceStepMapper steps = mock(GenerationTraceStepMapper.class);
    private final GenerationTraceService service = new GenerationTraceService(roots, steps, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void rejectsForeignOwnerIncludingAdminAndDeletedApp() {
        when(roots.find(ID)).thenReturn(root());
        when(roots.findApp(7L)).thenReturn(App.builder().id(7L).userId(9L).isDelete(0).build());
        assertThrows(BusinessException.class, () -> service.detail(ID, user(10L, "admin")));
        assertEquals(ID, service.detail(ID, user(9L, "user")).trace().getTraceId());
        when(roots.findApp(7L)).thenReturn(App.builder().id(7L).userId(9L).isDelete(1).build());
        assertThrows(BusinessException.class, () -> service.detail(ID, user(9L, "user")));
    }

    @Test void treatsExactSevenDayBoundaryAsExpiredButAllowsSnapshotOlderThanOneDay() {
        GenerationTrace trace = root();
        trace.setStartedAt(NOW.minus(Duration.ofDays(2)));
        when(roots.find(ID)).thenReturn(trace);
        when(roots.findApp(7L)).thenReturn(App.builder().id(7L).userId(9L).isDelete(0).build());
        assertEquals(ID, service.detail(ID, user(9L, "user")).trace().getTraceId());
        trace.setExpiresAt(NOW);
        assertThrows(BusinessException.class, () -> service.detail(ID, user(9L, "user")));
    }

    @Test void unresolvedCreationNeverDisclosesDetailOrSteps() {
        GenerationTrace trace = root();
        trace.setTraceKind("CREATE");
        trace.setCreationLinkState("UNRESOLVED");
        trace.setAppId(null);
        trace.setSafeMessage("不应该展示的局部诊断");
        when(roots.find(ID)).thenReturn(trace);
        var view = service.detail(ID, user(9L, "user"));
        assertEquals("UNAVAILABLE", view.recordingStatus());
        assertNull(view.trace());
        assertThrows(BusinessException.class, () -> service.steps(ID, 0L, 100, user(9L, "user")));
        trace.setCreationLinkState("NO_APP");
        assertEquals(ID, service.detail(ID, user(9L, "user")).trace().getTraceId());
    }

    @Test void invalidPaginationAndIdentifiersFailBeforeDatabaseQuery() {
        assertThrows(BusinessException.class, () -> service.detail("../secret", user(9L, "user")));
        assertThrows(BusinessException.class, () -> service.list(7L, null, 21, user(9L, "user")));
        assertThrows(BusinessException.class, () -> service.steps(ID, -1L, 100, user(9L, "user")));
        assertThrows(BusinessException.class, () -> service.steps(ID, 0L, 101, user(9L, "user")));
        verifyNoInteractions(roots, steps);
    }

    @Test void listRequiresCurrentOwnershipAndReturnsCursorForNextPage() {
        when(roots.findApp(7L)).thenReturn(App.builder().id(7L).userId(9L).isDelete(0).build());
        when(roots.list(eq(9L), eq(7L), eq(NOW), isNull(), isNull(), eq(2))).thenReturn(List.of(root(), root()));
        var page = service.list(7L, null, 1, user(9L, "user"));
        assertEquals(1, page.records().size());
        assertNotNull(page.nextCursor());
        assertThrows(BusinessException.class, () -> service.list(7L, null, 1, user(10L, "admin")));
    }

    private GenerationTrace root() {
        return GenerationTrace.builder().traceId(ID).traceKind("GENERATION").appId(7L).userId(9L)
                .startedAt(NOW.minusSeconds(60)).expiresAt(NOW.plusSeconds(60))
                .recordingStatus("COMPLETE").executionStatus("SUCCEEDED").build();
    }
    private User user(long id, String role) {
        User user = new User(); user.setId(id); user.setUserRole(role); return user;
    }
}
