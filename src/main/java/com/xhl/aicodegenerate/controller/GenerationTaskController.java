package com.xhl.aicodegenerate.controller;

import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.common.*;
import com.xhl.aicodegenerate.generation.*;
import com.xhl.aicodegenerate.service.UserService;
import com.xhl.aicodegenerate.ratelimit.annotation.RateLimit;
import com.xhl.aicodegenerate.ratelimit.enums.RateLimitType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import java.time.Duration;

/** 创建任务与订阅事件分离，重复订阅没有生成副作用。 */
@RestController
@RequestMapping("/app/generation/tasks")
public class GenerationTaskController {
    private final GenerationTaskService tasks;
    private final UserService users;
    public GenerationTaskController(GenerationTaskService tasks, UserService users) { this.tasks = tasks; this.users = users; }
    public record StartRequest(Long appId, String message, String clientRequestId) { }
    public record Current(GenerationTask task, boolean previewAvailable) { }
    @PostMapping
    @RateLimit(key = "ai_chat", limitType = RateLimitType.USER, rate = 5, rateInterval = 60)
    public BaseResponse<GenerationTask> start(@RequestBody StartRequest input, HttpServletRequest request) {
        return ResultUtils.success(tasks.start(input.appId(), input.message(), input.clientRequestId(), users.getLoginUser(request)));
    }
    @GetMapping("/current") public BaseResponse<Current> current(@RequestParam Long appId, HttpServletRequest request) {
        var user = users.getLoginUser(request);
        return ResultUtils.success(new Current(tasks.current(appId, user), tasks.previewAvailable(appId, user)));
    }
    @GetMapping("/{taskId}") public BaseResponse<GenerationTask> get(@PathVariable String taskId, HttpServletRequest request) {
        return ResultUtils.success(tasks.get(taskId, users.getLoginUser(request)));
    }
    @PostMapping("/{taskId}/stop") public BaseResponse<GenerationTask> stop(@PathVariable String taskId, HttpServletRequest request) {
        return ResultUtils.success(tasks.stop(taskId, users.getLoginUser(request)));
    }
    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(@PathVariable String taskId, HttpServletRequest request) {
        return snapshots(tasks.events(taskId, users.getLoginUser(request)));
    }
    static Flux<ServerSentEvent<String>> snapshots(Flux<GenerationTask> source) {
        return source.publish(shared -> Flux.merge(
                shared.map(task -> ServerSentEvent.<String>builder(JSONUtil.toJsonStr(JSONUtil.parseObj(task)
                        .set("appId", String.valueOf(task.getAppId())).set("userId", String.valueOf(task.getUserId()))
                        .set("userMessageId", task.getUserMessageId() == null ? null : String.valueOf(task.getUserMessageId()))
                        .set("aiMessageId", task.getAiMessageId() == null ? null : String.valueOf(task.getAiMessageId()))))
                        .event("task-snapshot").id(String.valueOf(task.getRevision())).build()),
                Flux.interval(Duration.ofSeconds(15)).map(tick -> ServerSentEvent.<String>builder().comment("任务连接保持").build())
                        .takeUntilOther(shared.then())));
    }
}
