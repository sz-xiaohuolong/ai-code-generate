package com.xhl.aicodegenerate.controller;

import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.exception.GlobalExceptionHandler;
import com.xhl.aicodegenerate.langgraph4j.CodeGenWorkflow;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.service.AppService;
import com.xhl.aicodegenerate.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class WorkflowSseControllerSecurityTest {
    @Mock AppService appService;
    @Mock UserService userService;
    @Mock CodeGenWorkflow codeGenWorkflow;
    @Mock com.xhl.aicodegenerate.generation.GenerationTaskService generationTasks;
    @Mock com.xhl.aicodegenerate.generation.GenerationWorkspace generationWorkspace;
    @InjectMocks WorkflowSseController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        lenient().when(codeGenWorkflow.executeWorkflow(anyString(), anyLong(), anyLong()))
                .thenReturn(WorkflowContext.builder().currentStep("完成").build());
    }

    @Test
    void shouldRejectUnauthenticatedExecution() throws Exception {
        lenient().when(userService.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        mvc.perform(post("/workflow/execute").param("prompt", "作品集").param("appId", "1"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));
    }

    @Test
    void shouldRejectOtherUsersAppEvenWhenClientSuppliesOwnersUserId() throws Exception {
        User visitor = new User();
        visitor.setId(2L);
        App app = App.builder().id(1L).userId(1L).generationMode("workflow").codeGenType("html").build();
        lenient().when(userService.getLoginUser(any())).thenReturn(visitor);
        lenient().when(appService.getById(1L)).thenReturn(app);
        mvc.perform(post("/workflow/execute").param("prompt", "作品集").param("appId", "1").param("userId", "1"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()));
    }

    @Test
    void shouldPreserveSkippedQualityCheckInSynchronousResult() throws Exception {
        User owner = new User();
        owner.setId(1L);
        when(userService.getLoginUser(any())).thenReturn(owner);
        when(appService.getById(1L)).thenReturn(App.builder().id(1L).userId(1L)
                .generationMode("workflow").codeGenType("html").build());
        var task = new com.xhl.aicodegenerate.generation.GenerationTask(); task.setId("task");
        task.setStatus(com.xhl.aicodegenerate.generation.TaskStatus.SUCCEEDED);
        task.setSteps(java.util.List.of(java.util.Map.of("id", "code_quality_check", "status", "skipped", "message", "质检不可用，已跳过检查")));
        when(generationTasks.start(1L, "作品集", null, owner)).thenReturn(task);
        when(generationTasks.events("task", owner)).thenReturn(Flux.just(task));
        when(generationWorkspace.published(1L, "html")).thenReturn(java.nio.file.Path.of("tmp/published"));
        mvc.perform(post("/workflow/execute").param("prompt", "作品集").param("appId", "1"))
                .andExpect(jsonPath("$.qualityCheckSkippedReason").value("质检不可用，已跳过检查"));
    }

    @Test
    void shouldRejectUnauthenticatedStreamingExecution() throws Exception {
        when(userService.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        mvc.perform(get("/workflow/execute-flux").param("prompt", "作品集").param("appId", "1"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));
    }

    @Test
    void shouldRejectOtherUsersStreamingExecution() throws Exception {
        User visitor = new User();
        visitor.setId(2L);
        when(userService.getLoginUser(any())).thenReturn(visitor);
        when(appService.getById(1L)).thenReturn(App.builder().id(1L).userId(1L).generationMode("workflow").build());
        mvc.perform(get("/workflow/execute-flux").param("prompt", "作品集").param("appId", "1"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()));
    }
}
