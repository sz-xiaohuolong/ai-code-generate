package com.xhl.aicodegenerate.controller;

import com.xhl.aicodegenerate.langgraph4j.CodeGenWorkflow;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 工作流 SSE 控制器。
 */
@Slf4j
@RestController
@RequestMapping("/workflow")
public class WorkflowSseController {

    @Resource
    private CodeGenWorkflow codeGenWorkflow;

    @PostMapping("/execute")
    public WorkflowContext executeWorkflow(@RequestParam String prompt,
                                           @RequestParam(required = false) Long appId,
                                           @RequestParam(required = false) Long userId) {
        log.info("收到同步工作流执行请求: {}", prompt);
        if (appId == null) {
            return codeGenWorkflow.executeWorkflow(prompt);
        }
        return codeGenWorkflow.executeWorkflow(prompt, appId, userId == null ? 0L : userId);
    }

    @GetMapping(value = "/execute-flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> executeWorkflowWithFlux(@RequestParam String prompt,
                                                                 @RequestParam(required = false) Long appId,
                                                                 @RequestParam(required = false) Long userId) {
        log.info("收到 Flux 工作流执行请求: {}", prompt);
        if (appId == null) {
            return codeGenWorkflow.executeWorkflowWithServerSentEvent(prompt);
        }
        return codeGenWorkflow.executeWorkflowWithServerSentEvent(prompt, appId, userId == null ? 0L : userId);
    }
}
