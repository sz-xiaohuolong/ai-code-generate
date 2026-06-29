package com.xhl.aicodegenerate.langgraph4j;

import com.xhl.aicodegenerate.langgraph4j.model.ImageCategoryEnum;
import com.xhl.aicodegenerate.langgraph4j.model.ImageResource;
import com.xhl.aicodegenerate.langgraph4j.model.QualityResult;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class WorkflowContextTest {

    @Test
    void saveContextShouldStoreJsonNeutralStateAndRestoreContext() {
        WorkflowContext context = WorkflowContext.builder()
                .currentStep("代码质量检查")
                .appId(100L)
                .userId(200L)
                .generationType(CodeGenTypeEnum.MULTI_FILE)
                .imageList(List.of(ImageResource.builder()
                        .category(ImageCategoryEnum.LOGO)
                        .description("通用 Logo")
                        .url("/api/static/workflow_assets/app_100/logo.svg")
                        .build()))
                .qualityResult(QualityResult.builder()
                        .isValid(false)
                        .errors(List.of("缺少入口文件"))
                        .suggestions(List.of("重新生成完整项目"))
                        .build())
                .build();

        Map<String, Object> savedState = WorkflowContext.saveContext(context);
        Object rawContext = savedState.get(WorkflowContext.WORKFLOW_CONTEXT_KEY);
        Assertions.assertFalse(rawContext instanceof WorkflowContext);

        WorkflowContext restored = WorkflowContext.getContext(new MessagesState<>(savedState));

        Assertions.assertEquals("代码质量检查", restored.getCurrentStep());
        Assertions.assertEquals(CodeGenTypeEnum.MULTI_FILE, restored.getGenerationType());
        Assertions.assertEquals(ImageCategoryEnum.LOGO, restored.getImageList().getFirst().getCategory());
        Assertions.assertEquals(false, restored.getQualityResult().getIsValid());
        Assertions.assertEquals("缺少入口文件", restored.getQualityResult().getErrors().getFirst());
    }
}
