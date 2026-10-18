package com.xhl.aicodegenerate.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.exception.ThrowUtils;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.model.enums.GenerationModeEnum;
import com.xhl.aicodegenerate.ratelimit.annotation.RateLimit;
import com.xhl.aicodegenerate.ratelimit.enums.RateLimitType;
import com.xhl.aicodegenerate.service.AppService;
import com.xhl.aicodegenerate.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** 演示入口复用应用服务，身份、固定模式、历史及限流与正式入口保持一致。 */
@RestController
@RequestMapping("/workflow")
public class WorkflowSseController {
    @Resource private AppService appService;
    @Resource private com.xhl.aicodegenerate.generation.GenerationTaskService generationTasks;
    @Resource private com.xhl.aicodegenerate.generation.GenerationWorkspace generationWorkspace;
    @Resource private UserService userService;

    @PostMapping("/execute")
    @RateLimit(key = "ai_chat", limitType = RateLimitType.USER, rate = 5, rateInterval = 60)
    public WorkflowContext executeWorkflow(@RequestParam String prompt, @RequestParam Long appId,
                                           HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        App app = requireWorkflowApp(prompt, appId, user);
        var started = generationTasks.start(appId, prompt, request.getParameter("clientRequestId"), user);
        var task = generationTasks.events(started.getId(), user).blockLast(Duration.ofMinutes(21));
        if (task == null || task.getStatus() != com.xhl.aicodegenerate.generation.TaskStatus.SUCCEEDED)
            throw new com.xhl.aicodegenerate.exception.BusinessException(ErrorCode.OPERATION_ERROR, task == null ? "任务未完成" : task.getMessage());
        String skippedReason = null;
        int retryCount = 0;
        for (var step : task.getSteps()) {
            if ("code_quality_check".equals(step.get("id"))) {
                if ("skipped".equals(step.get("status"))) skippedReason = String.valueOf(step.get("message"));
                if (step.get("retryCount") instanceof Number count) retryCount = count.intValue();
            }
        }
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        String directory = generationWorkspace.published(appId, app.getCodeGenType()).toString();
        return WorkflowContext.builder().appId(appId).userId(user.getId()).originalPrompt(prompt).generationType(type)
                .currentStep("工作流已完成").generatedCodeDir(directory)
                .qualityCheckSkippedReason(skippedReason).qualityCheckRetryCount(retryCount)
                .buildResultDir(type == CodeGenTypeEnum.VUE_PROJECT ? Path.of(directory, "dist").toString() : directory)
                .build();
    }

    @GetMapping(value = "/execute-flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(key = "ai_chat", limitType = RateLimitType.USER, rate = 5, rateInterval = 60)
    public Flux<ServerSentEvent<String>> executeWorkflowWithFlux(@RequestParam String prompt, @RequestParam Long appId,
                                                                HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        requireWorkflowApp(prompt, appId, user);
        var task = generationTasks.start(appId, prompt, request.getParameter("clientRequestId"), user);
        return GenerationTaskController.snapshots(generationTasks.events(task.getId(), user));
    }

    private App requireWorkflowApp(String prompt, Long appId, User user) {
        ThrowUtils.throwIf(appId == null || appId <= 0 || StrUtil.isBlank(prompt), ErrorCode.PARAMS_ERROR);
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(user.getId()), ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(GenerationModeEnum.resolve(app.getGenerationMode()) != GenerationModeEnum.WORKFLOW,
                ErrorCode.PARAMS_ERROR, "该应用使用标准模式，请从应用工作台生成");
        return app;
    }
}
