package com.xhl.aicodegenerate.langgraph4j.tools;

import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.langgraph4j.model.ImageCategoryEnum;
import com.xhl.aicodegenerate.langgraph4j.model.ImageResource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 无文生图服务时的 Logo 替代工具。
 */
@Component
public class PlaceholderLogoTool {

    private final LocalWorkflowAssetService assetService;

    public PlaceholderLogoTool(LocalWorkflowAssetService assetService) {
        this.assetService = assetService;
    }

    @Tool("根据描述生成本地 SVG Logo 占位图，用于网站品牌标识")
    public List<ImageResource> generateLogos(@P("Logo 设计描述") String description) {
        return generateLogos(description, 0L);
    }

    public List<ImageResource> generateLogos(String description, Long appId) {
        String safeDescription = StrUtil.blankToDefault(description, "网站 Logo");
        String label = safeDescription.length() > 18 ? safeDescription.substring(0, 18) : safeDescription;
        String escapedLabel = escapeXml(label);
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
                  <defs>
                    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
                      <stop offset="0" stop-color="#1677ff"/>
                      <stop offset="1" stop-color="#13c2c2"/>
                    </linearGradient>
                  </defs>
                  <rect width="512" height="512" rx="96" fill="url(#g)"/>
                  <circle cx="256" cy="210" r="88" fill="rgba(255,255,255,0.9)"/>
                  <text x="256" y="360" text-anchor="middle" font-size="34" font-family="Arial, sans-serif" fill="#fff">%s</text>
                </svg>
                """.formatted(escapedLabel);
        String url = assetService.saveSvg(appId, "logo", svg);
        return Collections.singletonList(ImageResource.builder()
                .category(ImageCategoryEnum.LOGO)
                .description(safeDescription)
                .url(url)
                .build());
    }

    private String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
