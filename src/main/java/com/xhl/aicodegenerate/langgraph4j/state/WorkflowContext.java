package com.xhl.aicodegenerate.langgraph4j.state;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.langgraph4j.model.ImageCategoryEnum;
import com.xhl.aicodegenerate.langgraph4j.model.ImageResource;
import com.xhl.aicodegenerate.langgraph4j.model.QualityResult;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工作流上下文 - 存储所有状态信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowContext implements Serializable {

    /**
     * WorkflowContext 在 MessagesState 中的存储key
     */
    public static final String WORKFLOW_CONTEXT_KEY = "workflowContext";

    /**
     * 代码质量检查失败后的最大重新生成次数
     */
    public static final int MAX_QUALITY_RETRY_COUNT = 2;

    /**
     * 当前执行步骤
     */
    private String currentStep;

    /**
     * 用户原始输入的提示词
     */
    private String originalPrompt;

    /**
     * 应用 ID
     */
    @Builder.Default
    private Long appId = 0L;

    /**
     * 用户 ID
     */
    @Builder.Default
    private Long userId = 0L;

    /**
     * 图片资源字符串
     */
    private String imageListStr;

    /**
     * 图片资源列表
     */
    private List<ImageResource> imageList;

    /**
     * 增强后的提示词
     */
    private String enhancedPrompt;

    /**
     * 代码生成类型
     */
    private CodeGenTypeEnum generationType;

    /**
     * 生成的代码目录
     */
    private String generatedCodeDir;

    /**
     * 构建成功的目录
     */
    private String buildResultDir;

    /**
     * 质量检查结果
     */
    private QualityResult qualityResult;

    /**
     * 质量检查失败后的重新生成次数
     */
    @Builder.Default
    private Integer qualityCheckRetryCount = 0;

    /**
     * 错误信息
     */
    private String errorMessage;

    @Serial
    private static final long serialVersionUID = 1L;

    // ========== 上下文操作方法 ==========

    /**
     * 从 MessagesState 中获取 WorkflowContext
     */
    public static WorkflowContext getContext(MessagesState<String> state) {
        Object contextObj = state.data().get(WORKFLOW_CONTEXT_KEY);
        if (contextObj == null) {
            return null;
        }
        if (contextObj instanceof WorkflowContext workflowContext) {
            return workflowContext;
        }
        JSONObject contextJson = JSONUtil.parseObj(contextObj);
        CodeGenTypeEnum generationType = resolveGenerationType(contextJson.remove("generationType"));
        List<ImageCategoryEnum> imageCategories = removeAndResolveImageCategories(contextJson);
        WorkflowContext context = JSONUtil.toBean(contextJson, WorkflowContext.class);
        context.setGenerationType(generationType);
        restoreImageCategories(context, imageCategories);
        return context;
    }

    /**
     * 将 WorkflowContext 保存到 MessagesState 中
     */
    public static Map<String, Object> saveContext(WorkflowContext context) {
        if (context == null) {
            return Map.of();
        }
        // LangGraph4j 会在异步线程中流转状态；Spring Boot DevTools 热重启时，
        // 直接保存对象可能触发同名类的跨 ClassLoader 强转异常，所以这里保存为 JSON 中性结构。
        return Map.of(WORKFLOW_CONTEXT_KEY, JSONUtil.parseObj(context));
    }

    private static CodeGenTypeEnum resolveGenerationType(Object generationType) {
        if (generationType instanceof JSONObject generationTypeJson) {
            return CodeGenTypeEnum.getEnumByValue(generationTypeJson.getStr("value"));
        }
        if (generationType instanceof String generationTypeValue) {
            CodeGenTypeEnum typeEnum = CodeGenTypeEnum.getEnumByValue(generationTypeValue);
            return typeEnum == null ? parseGenerationTypeName(generationTypeValue) : typeEnum;
        }
        return null;
    }

    private static CodeGenTypeEnum parseGenerationTypeName(String generationTypeName) {
        try {
            return CodeGenTypeEnum.valueOf(generationTypeName);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<ImageCategoryEnum> removeAndResolveImageCategories(JSONObject contextJson) {
        JSONArray imageList = contextJson.getJSONArray("imageList");
        if (imageList == null) {
            return List.of();
        }
        List<ImageCategoryEnum> categories = new ArrayList<>();
        for (Object item : imageList) {
            if (!(item instanceof JSONObject imageJson)) {
                categories.add(null);
                continue;
            }
            categories.add(resolveImageCategory(imageJson.remove("category")));
        }
        return categories;
    }

    private static ImageCategoryEnum resolveImageCategory(Object category) {
        if (category instanceof JSONObject categoryJson) {
            return ImageCategoryEnum.getEnumByValue(categoryJson.getStr("value"));
        }
        if (category instanceof String categoryValue) {
            ImageCategoryEnum categoryEnum = ImageCategoryEnum.getEnumByValue(categoryValue);
            if (categoryEnum != null) {
                return categoryEnum;
            }
            try {
                return ImageCategoryEnum.valueOf(categoryValue);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static void restoreImageCategories(WorkflowContext context, List<ImageCategoryEnum> imageCategories) {
        if (context.getImageList() == null || imageCategories == null) {
            return;
        }
        int size = Math.min(context.getImageList().size(), imageCategories.size());
        for (int i = 0; i < size; i++) {
            context.getImageList().get(i).setCategory(imageCategories.get(i));
        }
    }
}
