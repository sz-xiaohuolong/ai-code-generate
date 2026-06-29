package com.xhl.aicodegenerate.langgraph4j.model;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片收集计划。
 * <p>
 * 先由 AI 输出要收集哪些素材，再由节点并发调用具体工具。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageCollectionPlan implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<ImageSearchTask> contentImageTasks;

    private List<DiagramTask> diagramTasks;

    private List<LogoTask> logoTasks;

    public ImageCollectionPlan normalized(String originalPrompt) {
        List<ImageSearchTask> normalizedContentTasks = blankToEmpty(contentImageTasks).stream()
                .filter(task -> task != null && StrUtil.isNotBlank(task.query()))
                .toList();
        List<DiagramTask> normalizedDiagramTasks = blankToEmpty(diagramTasks).stream()
                .filter(task -> task != null && StrUtil.isNotBlank(task.mermaidCode()))
                .toList();
        List<LogoTask> normalizedLogoTasks = blankToEmpty(logoTasks).stream()
                .filter(task -> task != null && StrUtil.isNotBlank(task.description()))
                .toList();
        if (CollUtil.isEmpty(normalizedContentTasks) && CollUtil.isEmpty(normalizedDiagramTasks)
                && CollUtil.isEmpty(normalizedLogoTasks)) {
            return defaultPlan(originalPrompt);
        }
        return ImageCollectionPlan.builder()
                .contentImageTasks(normalizedContentTasks)
                .diagramTasks(normalizedDiagramTasks)
                .logoTasks(normalizedLogoTasks)
                .build();
    }

    public static ImageCollectionPlan defaultPlan(String originalPrompt) {
        String prompt = StrUtil.blankToDefault(originalPrompt, "通用网站");
        return ImageCollectionPlan.builder()
                .logoTasks(List.of(new LogoTask("通用品牌 Logo：" + prompt)))
                .contentImageTasks(List.of(
                        new ImageSearchTask("通用企业官网 现代办公 团队协作"),
                        new ImageSearchTask("通用业务服务 商务合作 科技公司")
                ))
                .diagramTasks(new ArrayList<>())
                .build();
    }

    private static <T> List<T> blankToEmpty(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }

    public record ImageSearchTask(String query) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public record DiagramTask(String mermaidCode, String description) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public record LogoTask(String description) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
