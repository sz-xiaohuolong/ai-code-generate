package com.xhl.aicodegenerate.exception;

import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.common.BaseResponse;
import com.xhl.aicodegenerate.common.ResultUtils;
import dev.langchain4j.guardrail.InputGuardrailException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Hidden
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        if (handleSseError(e.getCode(), e.getMessage())) {
            return null;
        }
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(InputGuardrailException.class)
    public BaseResponse<?> inputGuardrailExceptionHandler(InputGuardrailException e) {
        log.warn("Prompt 输入护轨拒绝请求: {}", e.getMessage());
        if (handleSseError(ErrorCode.PARAMS_ERROR.getCode(), e.getMessage())) {
            return null;
        }
        return ResultUtils.error(ErrorCode.PARAMS_ERROR.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        if (handleSseError(ErrorCode.SYSTEM_ERROR.getCode(), "系统错误")) {
            return null;
        }
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }

    private boolean handleSseError(int errorCode, String errorMessage) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();
        if (response == null || !isSseRequest(request)) {
            return false;
        }
        try {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put("error", true);
            errorData.put("code", errorCode);
            errorData.put("message", errorMessage);
            response.getWriter().write("event: business-error\ndata: "
                    + JSONUtil.toJsonStr(errorData) + "\n\n");
            response.getWriter().write("event: done\ndata: {}\n\n");
            response.getWriter().flush();
        } catch (IOException ioException) {
            log.error("写入 SSE 错误响应失败", ioException);
        }
        return true;
    }

    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        String uri = request.getRequestURI();
        return (accept != null && accept.contains("text/event-stream"))
                || (uri != null && uri.contains("/chat/gen/code"));
    }
}
