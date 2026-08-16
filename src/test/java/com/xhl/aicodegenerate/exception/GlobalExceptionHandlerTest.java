package com.xhl.aicodegenerate.exception;

import com.xhl.aicodegenerate.common.BaseResponse;
import dev.langchain4j.guardrail.InputGuardrailException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldWriteBusinessErrorAndDoneEventsForSseRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/chat/gen/code");
        request.addHeader("Accept", "text/event-stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        BaseResponse<?> result = handler.businessExceptionHandler(
                new BusinessException(ErrorCode.TOO_MANY_REQUEST, "AI 对话请求过于频繁，请稍后再试"));

        assertThat(result).isNull();
        assertThat(response.getContentType()).startsWith("text/event-stream");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsString())
                .contains("event: business-error")
                .contains("\"error\":true")
                .contains("\"code\":42900")
                .contains("AI 对话请求过于频繁，请稍后再试")
                .contains("event: done");
    }

    @Test
    void shouldKeepJsonResponseForNormalRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/app/deploy");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        BaseResponse<?> result = handler.businessExceptionHandler(
                new BusinessException(ErrorCode.PARAMS_ERROR, "参数错误"));

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
        assertThat(result.getMessage()).isEqualTo("参数错误");
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void shouldExposeInputGuardrailReasonAsSseBusinessError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/chat/gen/code");
        request.addHeader("Accept", "text/event-stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        BaseResponse<?> result = handler.inputGuardrailExceptionHandler(
                new InputGuardrailException("检测到恶意输入，请求被拒绝"));

        assertThat(result).isNull();
        assertThat(response.getContentAsString())
                .contains("\"code\":40000")
                .contains("检测到恶意输入，请求被拒绝");
    }
}
