package com.xhl.aicodegenerate.controller;

import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.service.AppService;
import com.xhl.aicodegenerate.service.UserService;
import dev.langchain4j.guardrail.InputGuardrailException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppControllerSseErrorTest {

    @Test
    void shouldConvertDeferredGuardrailFailureToBusinessErrorEvent() {
        AppService appService = mock(AppService.class);
        UserService userService = mock(UserService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        User user = new User();
        user.setId(1L);
        when(userService.getLoginUser(request)).thenReturn(user);
        when(appService.chatToGenCode(1L, "正常提示词", user))
                .thenReturn(Flux.error(new InputGuardrailException("检测到恶意输入，请求被拒绝")));
        AppController controller = new AppController();
        ReflectionTestUtils.setField(controller, "appService", appService);
        ReflectionTestUtils.setField(controller, "userService", userService);

        List<ServerSentEvent<String>> events = controller
                .chatToGenCode(1L, "正常提示词", request)
                .collectList()
                .block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).event()).isEqualTo("business-error");
        assertThat(events.get(0).data())
                .contains("\"code\":40000")
                .contains("检测到恶意输入，请求被拒绝");
        assertThat(events.get(1).event()).isEqualTo("done");
    }
}
