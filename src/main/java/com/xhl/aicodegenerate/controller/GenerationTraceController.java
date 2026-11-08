package com.xhl.aicodegenerate.controller;

import com.xhl.aicodegenerate.common.BaseResponse;
import com.xhl.aicodegenerate.common.ResultUtils;
import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.GenerationTraceStep;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.service.GenerationTraceService;
import com.xhl.aicodegenerate.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;

/** 追踪只读入口；所有路径复用服务层的当前所有者和保留期校验。 */
@RestController
@RequestMapping("/app/traces")
public class GenerationTraceController {
    private final GenerationTraceService traces;
    private final UserService users;

    public GenerationTraceController(GenerationTraceService traces, UserService users) {
        this.traces = traces;
        this.users = users;
    }

    @GetMapping
    public BaseResponse<GenerationTraceService.TracePage<GenerationTrace>> list(
            @RequestParam Long appId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int pageSize, HttpServletRequest request) {
        return ResultUtils.success(traces.list(appId, cursor, pageSize, users.getLoginUser(request)));
    }

    @GetMapping("/{traceId}")
    public BaseResponse<GenerationTraceService.TraceView> detail(@PathVariable String traceId, HttpServletRequest request) {
        return ResultUtils.success(traces.detail(traceId, users.getLoginUser(request)));
    }

    @GetMapping("/{traceId}/steps")
    public BaseResponse<GenerationTraceService.TracePage<GenerationTraceStep>> steps(
            @PathVariable String traceId, @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(defaultValue = "100") int pageSize, HttpServletRequest request) {
        return ResultUtils.success(traces.steps(traceId, cursor, pageSize, users.getLoginUser(request)));
    }

    @GetMapping("/by-task/{taskId}")
    public BaseResponse<GenerationTraceService.TraceView> byTask(@PathVariable String taskId, HttpServletRequest request) {
        return ResultUtils.success(traces.byTask(taskId, users.getLoginUser(request)));
    }

    @GetMapping("/by-request/{requestId}")
    public BaseResponse<GenerationTraceService.TraceView> byRequest(@PathVariable String requestId, HttpServletRequest request) {
        return ResultUtils.success(traces.byRequest(requestId, users.getLoginUser(request)));
    }

    @ExceptionHandler(DataAccessException.class)
    public BaseResponse<?> unavailable(DataAccessException error) {
        // 只检查驱动错误码，不解析或回传可能包含库名、SQL、凭据的异常正文。
        if (error.getMostSpecificCause() instanceof SQLException sql
                && sql.getErrorCode() == 1146 && "42S02".equals(sql.getSQLState())) {
            return ResultUtils.error(ErrorCode.TRACE_STORAGE_NOT_READY);
        }
        // 不把数据库异常正文、SQL 或连接信息放进诊断响应。
        return ResultUtils.error(ErrorCode.OPERATION_ERROR, "追踪暂不可用，请稍后重试");
    }
}
