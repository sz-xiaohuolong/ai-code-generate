package com.xhl.aicodegenerate.langgraph4j.tools;

import com.xhl.aicodegenerate.langgraph4j.model.ImageCategoryEnum;
import com.xhl.aicodegenerate.langgraph4j.model.ImageResource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class WorkflowImageToolsTest {

    @Test
    void placeholderLogoToolCreatesLocalLogoResource() {
        PlaceholderLogoTool logoTool = new PlaceholderLogoTool(new LocalWorkflowAssetService());

        List<ImageResource> logos = logoTool.generateLogos("小火龙", 100L);

        Assertions.assertEquals(1, logos.size());
        ImageResource logo = logos.getFirst();
        Assertions.assertEquals(ImageCategoryEnum.LOGO, logo.getCategory());
        Assertions.assertEquals("小火龙", logo.getDescription());
        Assertions.assertTrue(logo.getUrl().startsWith("/api/static/workflow_assets/app_100/"));
    }


    @Test
    void krokiToolFallsBackToLocalSvgWhenRemoteCallDisabled() {
        KrokiMermaidDiagramTool diagramTool = new KrokiMermaidDiagramTool(
                new LocalWorkflowAssetService(),
                "",
                false
        );
        String mermaid = """
                flowchart TD
                    A[开始] --> B[结束]
                """;

        List<ImageResource> diagrams = diagramTool.generateMermaidDiagram(mermaid, "简单流程图", 100L);

        Assertions.assertEquals(1, diagrams.size());
        ImageResource diagram = diagrams.getFirst();
        Assertions.assertEquals(ImageCategoryEnum.ARCHITECTURE, diagram.getCategory());
        Assertions.assertEquals("简单流程图", diagram.getDescription());
        Assertions.assertTrue(diagram.getUrl().startsWith("/api/static/workflow_assets/app_100/"));
    }
}
