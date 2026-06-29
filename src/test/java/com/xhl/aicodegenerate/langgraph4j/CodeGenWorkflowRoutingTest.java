package com.xhl.aicodegenerate.langgraph4j;

import com.xhl.aicodegenerate.langgraph4j.model.QualityResult;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class CodeGenWorkflowRoutingTest {

    @Test
    void routeAfterQualityCheckShouldSkipBuildForHtmlAndMultiFile() {
        CodeGenWorkflow workflow = new CodeGenWorkflow();

        Assertions.assertEquals("skip_build", workflow.routeAfterQualityCheck(state(CodeGenTypeEnum.HTML, true)));
        Assertions.assertEquals("skip_build", workflow.routeAfterQualityCheck(state(CodeGenTypeEnum.MULTI_FILE, true)));
    }

    @Test
    void routeAfterQualityCheckShouldBuildOnlyForVueProject() {
        CodeGenWorkflow workflow = new CodeGenWorkflow();

        Assertions.assertEquals("build", workflow.routeAfterQualityCheck(state(CodeGenTypeEnum.VUE_PROJECT, true)));
    }

    @Test
    void routeAfterQualityCheckShouldLoopBackWhenQualityFails() {
        CodeGenWorkflow workflow = new CodeGenWorkflow();

        Assertions.assertEquals("fail", workflow.routeAfterQualityCheck(state(CodeGenTypeEnum.HTML, false)));
    }

    @Test
    void routeAfterQualityCheckShouldStopWhenRetryLimitReached() {
        CodeGenWorkflow workflow = new CodeGenWorkflow();
        WorkflowContext context = WorkflowContext.builder()
                .currentStep("代码质量检查")
                .generationType(CodeGenTypeEnum.HTML)
                .qualityCheckRetryCount(2)
                .qualityResult(QualityResult.builder()
                        .isValid(false)
                        .errors(List.of("index.html 存在语法错误"))
                        .suggestions(List.of("重新生成 HTML"))
                        .build())
                .build();

        Assertions.assertEquals("stop", workflow.routeAfterQualityCheck(
                new MessagesState<>(WorkflowContext.saveContext(context))));
    }

    private MessagesState<String> state(CodeGenTypeEnum generationType, boolean valid) {
        WorkflowContext context = WorkflowContext.builder()
                .currentStep("代码质量检查")
                .generationType(generationType)
                .qualityResult(QualityResult.builder()
                        .isValid(valid)
                        .errors(valid ? List.of() : List.of("index.html 存在语法错误"))
                        .suggestions(valid ? List.of() : List.of("重新生成 HTML"))
                        .build())
                .build();
        return new MessagesState<>(WorkflowContext.saveContext(context));
    }
}
