package com.xhl.aicodegenerate.service;

import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.GenerationTraceStep;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.mapper.GenerationTraceMapper;
import com.xhl.aicodegenerate.mapper.GenerationTraceStepMapper;
import com.xhl.aicodegenerate.observability.GenerationTraceRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/** 只读追踪查询，始终核查当前应用归属与七天保留期，不依赖 Redis 任务快照。 */
@Service
public class GenerationTraceService {
    private final GenerationTraceMapper roots;
    private final GenerationTraceStepMapper steps;
    private final Clock clock;
    private final GenerationTraceRecorder recorder;

    @Autowired
    public GenerationTraceService(GenerationTraceMapper roots, GenerationTraceStepMapper steps, GenerationTraceRecorder recorder) {
        this(roots, steps, Clock.systemUTC(), recorder);
    }

    public GenerationTraceService(GenerationTraceMapper roots, GenerationTraceStepMapper steps) {
        this(roots, steps, Clock.systemUTC(), null);
    }

    public GenerationTraceService(GenerationTraceMapper roots, GenerationTraceStepMapper steps, Clock clock) {
        this(roots, steps, clock, null);
    }

    public GenerationTraceService(GenerationTraceMapper roots, GenerationTraceStepMapper steps, Clock clock, GenerationTraceRecorder recorder) {
        this.roots = roots;
        this.steps = steps;
        this.clock = clock;
        this.recorder = recorder;
    }

    public TraceView detail(String traceId, User user) {
        id(traceId);
        authenticated(user);
        return visible(roots.find(traceId), user);
    }

    public TraceView byTask(String taskId, User user) {
        uuid(taskId);
        authenticated(user);
        return visible(roots.byTask(taskId), user);
    }

    public TraceView byRequest(String requestId, User user) {
        uuid(requestId);
        authenticated(user);
        return visible(roots.byRequest(requestId), user);
    }

    public TracePage<GenerationTrace> list(Long appId, String cursor, int pageSize, User user) {
        size(pageSize, 20);
        authenticated(user);
        Instant before = null;
        String beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            if (cursor.length() > 160) {
                throw invalid();
            }
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\\|", -1);
                if (parts.length != 2) {
                    throw invalid();
                }
                before = Instant.parse(parts[0]);
                beforeId = parts[1];
                id(beforeId);
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException error) {
                throw invalid();
            }
        }
        ownedApp(appId, user);
        List<GenerationTrace> records = roots.list(user.getId(), appId, clock.instant(), before, beforeId, pageSize + 1);
        boolean more = records.size() > pageSize;
        List<GenerationTrace> page = records.subList(0, Math.min(pageSize, records.size())).stream().map(this::withKnownGaps).toList();
        String next = null;
        if (more) {
            GenerationTrace last = page.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.getStartedAt() + "|" + last.getTraceId()).getBytes(StandardCharsets.UTF_8));
        }
        return new TracePage<>(page, next);
    }

    public TracePage<GenerationTraceStep> steps(String traceId, long cursor, int pageSize, User user) {
        id(traceId);
        size(pageSize, 100);
        if (cursor < 0) {
            throw invalid();
        }
        TraceView view = detail(traceId, user);
        if (view.trace() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "追踪关联尚未确认，详情暂不可用");
        }
        List<GenerationTraceStep> records = steps.list(traceId, cursor, pageSize + 1);
        boolean more = records.size() > pageSize;
        List<GenerationTraceStep> page = List.copyOf(records.subList(0, Math.min(pageSize, records.size())));
        return new TracePage<>(page, more ? Long.toString(page.getLast().getStepNo()) : null);
    }

    private TraceView visible(GenerationTrace trace, User user) {
        if (trace == null || trace.getExpiresAt() == null || !trace.getExpiresAt().isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "追踪不存在或已超过保留期");
        }
        if (!user.getId().equals(trace.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "仅发起用户可以查看追踪");
        }
        if (trace.getAppId() != null) {
            ownedApp(trace.getAppId(), user);
        }
        if ("CREATE".equals(trace.getTraceKind())) {
            boolean bound = "BOUND".equals(trace.getCreationLinkState()) && trace.getAppId() != null;
            boolean noApp = "NO_APP".equals(trace.getCreationLinkState()) && trace.getAppId() == null;
            if (!bound && !noApp) {
                return new TraceView(null, "UNAVAILABLE", "创建关联尚未确认，追踪暂不可用");
            }
        } else if (trace.getAppId() == null) {
            return new TraceView(null, "UNAVAILABLE", "追踪关联不完整");
        }
        GenerationTrace visible = withKnownGaps(trace);
        return new TraceView(visible, visible.getRecordingStatus(), null);
    }

    private GenerationTrace withKnownGaps(GenerationTrace trace) {
        return recorder == null ? trace : recorder.visibleSnapshot(trace);
    }

    private void ownedApp(Long appId, User user) {
        if (appId == null || appId <= 0) {
            throw invalid();
        }
        App app = roots.findApp(appId);
        if (app == null || Integer.valueOf(1).equals(app.getIsDelete())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        if (!user.getId().equals(app.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "仅应用所有者可以查看追踪");
        }
    }

    private void authenticated(User user) {
        if (user == null || user.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (recorder != null && !recorder.isEnabled()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "追踪已停用");
        }
    }

    private static void id(String value) {
        if (value == null || !value.matches("[a-f0-9]{32}") || value.equals("0".repeat(32))) {
            throw invalid();
        }
    }

    private static void uuid(String value) {
        if (value == null || !value.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) {
            throw invalid();
        }
    }

    private static void size(int value, int maximum) {
        if (value < 1 || value > maximum) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.PARAMS_ERROR, "追踪编号或分页参数无效");
    }

    public record TraceView(GenerationTrace trace, String recordingStatus, String message) { }
    public record TracePage<T>(List<T> records, String nextCursor) { }
}
