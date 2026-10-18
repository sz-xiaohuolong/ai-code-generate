package com.xhl.aicodegenerate.service;

import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.ai.AiCodeGenTypeRoutingService;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.langgraph4j.CodeGenWorkflow;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import reactor.core.publisher.Flux;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.model.dto.app.AppAddRequest;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.service.impl.AppServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 模式必须在创建时持久化，不能由后续全局偏好覆盖。 */
class AppGenerationModeTest {
    private AppServiceImpl service;
    private final AtomicReference<App> savedApp = new AtomicReference<>();
    private User owner;

    @BeforeEach
    void setUp() {
        service = spy(new AppServiceImpl());
        AiCodeGenTypeRoutingService routing = mock(AiCodeGenTypeRoutingService.class);
        when(routing.routeCodeGenType(any())).thenReturn(CodeGenTypeEnum.HTML);
        ReflectionTestUtils.setField(service, "aiCodeGenTypeRoutingService", routing);
        doAnswer(invocation -> {
            App app = invocation.getArgument(0);
            app.setId(100L);
            savedApp.set(app);
            return true;
        }).when(service).save(any(App.class));
        owner = new User();
        owner.setId(1L);
    }

    @Test
    void shouldPersistWorkflowModeWhenCreatingApp() {
        AppAddRequest request = JSONUtil.toBean(
                "{\"initPrompt\":\"个人作品集\",\"generationMode\":\"workflow\"}", AppAddRequest.class);
        assertThat(service.addApp(request, owner)).isEqualTo(100L);
        assertThat(JSONUtil.parseObj(savedApp.get()).getStr("generationMode")).isEqualTo("workflow");
        assertThat(savedApp.get().getCodeGenType()).isEqualTo("html");
    }

    @Test
    void shouldDefaultOldCreationRequestsToStandardMode() {
        AppAddRequest request = new AppAddRequest();
        request.setInitPrompt("个人作品集");
        service.addApp(request, owner);
        assertThat(JSONUtil.parseObj(savedApp.get()).getStr("generationMode")).isEqualTo("standard");
    }

    @Test
    void shouldRejectUnknownModeInsteadOfSilentlyFallingBack() {
        AppAddRequest request = JSONUtil.toBean(
                "{\"initPrompt\":\"个人作品集\",\"generationMode\":\"unknown\"}", AppAddRequest.class);
        assertThatThrownBy(() -> service.addApp(request, owner))
                .isInstanceOf(BusinessException.class).hasMessageContaining("生成模式");
        assertThat(savedApp.get()).isNull();
    }

    @Test
    void shouldChooseWorkflowFromSavedAppMode() {
        doReturn(App.builder().id(100L).userId(1L).codeGenType("html").generationMode("workflow").build())
                .when(service).getById(100L);
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        ReflectionTestUtils.setField(service, "chatMemoryProvider", (ChatMemoryProvider) id -> memory);
        CodeGenWorkflow workflow = mock(CodeGenWorkflow.class);
        when(workflow.generateForApp("改成红色", 100L, 1L, CodeGenTypeEnum.HTML, memory))
                .thenReturn(Flux.just("工作流执行结果"));
        ReflectionTestUtils.setField(service, "codeGenWorkflow", workflow);
        assertThat(service.chatToGenCode(100L, "改成红色", owner).collectList().block())
                .containsExactly("工作流执行结果");
    }

    @Test
    void shouldKeepLegacyAppsOnStandardEngine() {
        doReturn(App.builder().id(100L).userId(1L).codeGenType("html").build()).when(service).getById(100L);
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        ReflectionTestUtils.setField(service, "chatMemoryProvider", (ChatMemoryProvider) id -> memory);
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateAndSaveCodeStream(eq("修改标题"), eq(CodeGenTypeEnum.HTML), any()))
                .thenReturn(Flux.just("标准生成结果"));
        ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", facade);
        assertThat(service.chatToGenCode(100L, "修改标题", owner).collectList().block())
                .containsExactly("标准生成结果");
    }

    @Test
    void shouldRejectOtherUsersBeforeInvokingEitherEngine() {
        doReturn(App.builder().id(100L).userId(2L).codeGenType("html").generationMode("workflow").build())
                .when(service).getById(100L);
        assertThatThrownBy(() -> service.chatToGenCode(100L, "修改标题", owner))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权限");
    }
}
