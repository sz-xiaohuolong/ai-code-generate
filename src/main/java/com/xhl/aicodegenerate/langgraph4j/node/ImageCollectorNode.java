package com.xhl.aicodegenerate.langgraph4j.node;

import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.langgraph4j.ai.ImageCollectionPlanService;
import com.xhl.aicodegenerate.langgraph4j.model.ImageCollectionPlan;
import com.xhl.aicodegenerate.langgraph4j.model.ImageResource;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.langgraph4j.tools.ImageSearchTool;
import com.xhl.aicodegenerate.langgraph4j.tools.KrokiMermaidDiagramTool;
import com.xhl.aicodegenerate.langgraph4j.tools.PlaceholderLogoTool;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 图片素材收集节点。
 * <p>
 * 这一层先让 AI 规划需要哪些素材，再用 CompletableFuture 并发调用具体工具，
 * 最后把汇总后的素材列表写入工作流上下文。
 * </p>
 */
@Slf4j
@Component
public class ImageCollectorNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 图片收集");
            context.setCurrentStep("图片收集");
            if (StrUtil.isBlank(context.getOriginalPrompt())) {
                context.setImageListStr("");
                context.setImageList(new ArrayList<>());
                return WorkflowContext.saveContext(context);
            }
            List<ImageResource> collectedImages = new ArrayList<>();
            try {
                ImageCollectionPlan plan = planImageCollection(context.getOriginalPrompt());
                collectedImages = executeImageTasks(plan, context.getAppId());
            } catch (Exception e) {
                log.warn("图片素材收集失败，继续执行后续代码生成流程: {}", e.getMessage());
            }
            context.setImageList(collectedImages);
            context.setImageListStr(formatImageList(collectedImages));
            return WorkflowContext.saveContext(context);
        });
    }

    static ImageCollectionPlan planImageCollection(String originalPrompt) {
        try {
            ImageCollectionPlanService planService = SpringContextUtil.getBean(ImageCollectionPlanService.class);
            ImageCollectionPlan plan = planService.planImageCollection(originalPrompt);
            if (plan == null) {
                return ImageCollectionPlan.defaultPlan(originalPrompt);
            }
            return plan.normalized(originalPrompt);
        } catch (Exception e) {
            log.warn("图片收集规划失败，使用默认规划: {}", e.getMessage());
            return ImageCollectionPlan.defaultPlan(originalPrompt);
        }
    }

    static List<ImageResource> executeImageTasks(ImageCollectionPlan plan, Long appId) {
        ImageCollectionPlan normalizedPlan = plan == null
                ? ImageCollectionPlan.defaultPlan("")
                : plan.normalized("");
        List<CompletableFuture<List<ImageResource>>> futures = new ArrayList<>();
        ImageSearchTool imageSearchTool = SpringContextUtil.getBean(ImageSearchTool.class);
        PlaceholderLogoTool logoTool = SpringContextUtil.getBean(PlaceholderLogoTool.class);
        KrokiMermaidDiagramTool diagramTool = SpringContextUtil.getBean(KrokiMermaidDiagramTool.class);

        for (ImageCollectionPlan.ImageSearchTask task : normalizedPlan.getContentImageTasks()) {
            futures.add(CompletableFuture.supplyAsync(() -> imageSearchTool.searchContentImages(task.query())));
        }
        for (ImageCollectionPlan.LogoTask task : normalizedPlan.getLogoTasks()) {
            futures.add(CompletableFuture.supplyAsync(() -> logoTool.generateLogos(task.description(), appId)));
        }
        for (ImageCollectionPlan.DiagramTask task : normalizedPlan.getDiagramTasks()) {
            futures.add(CompletableFuture.supplyAsync(() ->
                    diagramTool.generateMermaidDiagram(task.mermaidCode(), task.description(), appId)));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<ImageResource> result = new ArrayList<>();
        for (CompletableFuture<List<ImageResource>> future : futures) {
            try {
                List<ImageResource> images = future.get();
                if (images != null) {
                    result.addAll(images);
                }
            } catch (Exception e) {
                log.warn("图片任务执行失败，忽略该任务: {}", e.getMessage());
            }
        }
        log.info("并发图片收集完成，共收集到 {} 个素材", result.size());
        return result;
    }

    static String formatImageList(List<ImageResource> imageList) {
        if (imageList == null || imageList.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("## 图片素材清单\n");
        for (ImageResource imageResource : imageList) {
            builder.append("- ")
                    .append(imageResource.getCategory() == null ? "图片" : imageResource.getCategory().getText())
                    .append("：")
                    .append(StrUtil.blankToDefault(imageResource.getDescription(), "素材"))
                    .append(" -> ")
                    .append(imageResource.getUrl())
                    .append("\n");
        }
        return builder.toString();
    }
}
