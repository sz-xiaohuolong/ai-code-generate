package com.xhl.aicodegenerate.langgraph4j.tools;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.constant.AppConstant;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作流本地素材存储。
 * <p>
 * 当前项目没有 COS，这里把工作流生成的 SVG 素材保存到 code_output 下，
 * 并返回现有 StaticResourceController 能访问的 /api/static 路径。
 * </p>
 */
@Component
public class LocalWorkflowAssetService {

    private static final String ASSET_ROOT = "workflow_assets";

    public String saveSvg(Long appId, String fileNamePrefix, String svgContent) {
        Long safeAppId = appId == null || appId <= 0 ? 0L : appId;
        String safePrefix = StrUtil.blankToDefault(fileNamePrefix, "asset")
                .replaceAll("[^a-zA-Z0-9_-]", "_");
        String fileName = safePrefix + "_" + RandomUtil.randomString(8) + ".svg";
        Path relativePath = Path.of(ASSET_ROOT, "app_" + safeAppId, fileName);
        Path targetPath = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).resolve(relativePath);
        try {
            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, svgContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存工作流素材失败: " + targetPath, e);
        }
        return "/api/static/" + relativePath.toString().replace("\\", "/");
    }
}
