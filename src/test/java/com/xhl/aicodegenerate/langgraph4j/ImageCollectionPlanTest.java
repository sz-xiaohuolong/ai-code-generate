package com.xhl.aicodegenerate.langgraph4j;

import com.xhl.aicodegenerate.langgraph4j.model.ImageCollectionPlan;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ImageCollectionPlanTest {

    @Test
    void defaultPlanShouldContainGenericLogoAndContentTasksWhenPromptIsSparse() {
        ImageCollectionPlan plan = ImageCollectionPlan.defaultPlan("创建企业官网");

        Assertions.assertFalse(plan.getLogoTasks().isEmpty());
        Assertions.assertFalse(plan.getContentImageTasks().isEmpty());
        Assertions.assertTrue(plan.getLogoTasks().getFirst().description().contains("通用"));
        Assertions.assertTrue(plan.getContentImageTasks().stream().anyMatch(task -> task.query().contains("企业")));
    }

    @Test
    void shouldRemoveBlankTasks() {
        ImageCollectionPlan plan = ImageCollectionPlan.builder()
                .logoTasks(List.of(new ImageCollectionPlan.LogoTask("  ")))
                .contentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("团队协作"), new ImageCollectionPlan.ImageSearchTask("")))
                .diagramTasks(List.of(new ImageCollectionPlan.DiagramTask("", "架构图")))
                .build()
                .normalized("创建企业官网");

        Assertions.assertTrue(plan.getLogoTasks().isEmpty());
        Assertions.assertEquals(1, plan.getContentImageTasks().size());
        Assertions.assertTrue(plan.getDiagramTasks().isEmpty());
    }
}
