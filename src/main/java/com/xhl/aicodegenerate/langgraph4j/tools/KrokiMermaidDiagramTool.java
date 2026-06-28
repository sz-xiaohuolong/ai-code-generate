package com.xhl.aicodegenerate.langgraph4j.tools;

import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.langgraph4j.model.ImageCategoryEnum;
import com.xhl.aicodegenerate.langgraph4j.model.ImageResource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Mermaid 架构图生成工具。
 * <p>
 * 当前没有 COS，因此使用 Kroki 远程渲染 SVG 后保存到本地静态资源目录。
 * Kroki 不可用时，会把 Mermaid 文本包装成本地 SVG，保证工作流不中断。
 * </p>
 */
@Slf4j
@Component
public class KrokiMermaidDiagramTool {

    private static final String DEFAULT_KROKI_BASE_URL = "https://kroki.io";

    private final LocalWorkflowAssetService assetService;

    private final String krokiBaseUrl;

    private final boolean remoteEnabled;

    private final HttpClient httpClient;

    public KrokiMermaidDiagramTool(LocalWorkflowAssetService assetService) {
        this(assetService, DEFAULT_KROKI_BASE_URL, true);
    }

    @Autowired
    public KrokiMermaidDiagramTool(LocalWorkflowAssetService assetService,
                                   @Value("${kroki.base-url:https://kroki.io}") String krokiBaseUrl,
                                   @Value("${kroki.remote-enabled:true}") boolean remoteEnabled) {
        this.assetService = assetService;
        this.krokiBaseUrl = StrUtil.blankToDefault(krokiBaseUrl, DEFAULT_KROKI_BASE_URL);
        this.remoteEnabled = remoteEnabled;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    @Tool("将 Mermaid 代码转换为架构图图片，用于展示系统结构和技术关系")
    public List<ImageResource> generateMermaidDiagram(@P("Mermaid 图表代码") String mermaidCode,
                                                      @P("架构图描述") String description) {
        return generateMermaidDiagram(mermaidCode, description, 0L);
    }

    public List<ImageResource> generateMermaidDiagram(String mermaidCode, String description, Long appId) {
        if (StrUtil.isBlank(mermaidCode)) {
            return Collections.emptyList();
        }
        String safeDescription = StrUtil.blankToDefault(description, "Mermaid 架构图");
        String svg = renderSvg(mermaidCode);
        String url = assetService.saveSvg(appId, "diagram", svg);
        return Collections.singletonList(ImageResource.builder()
                .category(ImageCategoryEnum.ARCHITECTURE)
                .description(safeDescription)
                .url(url)
                .build());
    }

    private String renderSvg(String mermaidCode) {
        if (remoteEnabled) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(krokiBaseUrl + "/mermaid/svg"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "text/plain; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(mermaidCode))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300
                        && StrUtil.isNotBlank(response.body())) {
                    return response.body();
                }
                log.warn("Kroki 生成 Mermaid SVG 失败，状态码: {}", response.statusCode());
            } catch (Exception e) {
                log.warn("Kroki 生成 Mermaid SVG 异常，使用本地兜底 SVG: {}", e.getMessage());
            }
        }
        return buildFallbackSvg(mermaidCode);
    }

    private String buildFallbackSvg(String mermaidCode) {
        String escapedCode = escapeXml(mermaidCode);
        return """
                <svg xmlns="http://www.w3.org/2000/svg" width="960" height="540" viewBox="0 0 960 540">
                  <rect width="960" height="540" rx="24" fill="#f5f7fb"/>
                  <rect x="48" y="48" width="864" height="444" rx="16" fill="#ffffff" stroke="#d9e1ec"/>
                  <text x="80" y="96" font-size="28" font-family="Arial, sans-serif" fill="#1f2937">Mermaid 架构图</text>
                  <foreignObject x="80" y="128" width="800" height="330">
                    <pre xmlns="http://www.w3.org/1999/xhtml" style="font: 18px monospace; white-space: pre-wrap; color: #374151;">%s</pre>
                  </foreignObject>
                </svg>
                """.formatted(escapedCode);
    }

    private String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
