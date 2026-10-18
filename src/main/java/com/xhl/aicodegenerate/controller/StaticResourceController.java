package com.xhl.aicodegenerate.controller;

import com.xhl.aicodegenerate.constant.AppConstant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.File;

@RestController
@RequestMapping("/static")
public class StaticResourceController {
    @jakarta.annotation.Resource private com.xhl.aicodegenerate.generation.GenerationWorkspace generationWorkspace;

    // 应用生成根目录（用于浏览）
    private static final String PREVIEW_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR;

    // 应用部署根目录
    private static final String DEPLOY_ROOT_DIR = AppConstant.CODE_DEPLOY_ROOT_DIR;

    // 应用封面根目录
    private static final String COVER_ROOT_DIR = AppConstant.COVER_OUTPUT_ROOT_DIR;

    /**
     * 提供静态资源访问，支持目录重定向
     * 访问格式：http://localhost:8123/api/static/{deployKey}[/{fileName}]
     * 封面格式：http://localhost:8123/api/static/cover/{fileName}
     */
    @GetMapping("/{deployKey}/**")
    public ResponseEntity<Resource> serveStaticResource(
            @PathVariable String deployKey,
            HttpServletRequest request) {
        try {
            // 获取资源路径
            String resourcePath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
            resourcePath = resourcePath.substring(("/static/" + deployKey).length());

            // 封面图片路由支持：/static/cover/{fileName}
            if ("cover".equals(deployKey)) {
                return fileResponse(java.nio.file.Path.of(COVER_ROOT_DIR), resourcePath);
            }

            // 如果是目录访问（不带斜杠），重定向到带斜杠的URL
            if (resourcePath.isEmpty()) {
                HttpHeaders headers = new HttpHeaders();
                headers.add("Location", request.getRequestURI() + "/");
                return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
            }
            // 默认返回 index.html
            if (resourcePath.equals("/")) {
                resourcePath = "/index.html";
            }
            java.nio.file.Path base;
            var match = java.util.regex.Pattern.compile("(html|multi_file|vue_project)_(\\d+)").matcher(deployKey);
            var fixed = java.util.regex.Pattern.compile("v_([A-Za-z0-9-]{1,80})_(html|multi_file|vue_project)_(\\d+)").matcher(deployKey);
            if (match.matches()) {
                base = generationWorkspace.published(Long.parseLong(match.group(2)), match.group(1));
                if ("vue_project".equals(match.group(1)) && resourcePath.startsWith("/dist/")) {
                    resourcePath = resourcePath.substring("/dist".length());
                }
                String version = generationWorkspace.publishedVersion(Long.parseLong(match.group(2)));
                if (version != null) {
                    // 页面及相对资源固定到同一个成功版本，后续发布不会混用资源。
                    String key = "v_" + version + "_" + deployKey;
                    String location = request.getContextPath() + "/static/" + key + resourcePath;
                    return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).header("Location", location)
                            .header("Cache-Control", "no-store").build();
                }
                if ("vue_project".equals(match.group(1))) base = base.resolve("dist");
            } else if (fixed.matches()) {
                base = generationWorkspace.version(Long.parseLong(fixed.group(3)), fixed.group(2), fixed.group(1));
                if ("vue_project".equals(fixed.group(2))) {
                    base = base.resolve("dist");
                    if (resourcePath.startsWith("/dist/")) resourcePath = resourcePath.substring("/dist".length());
                }
            } else if ("workflow_assets".equals(deployKey)) {
                base = java.nio.file.Path.of(PREVIEW_ROOT_DIR, deployKey);
            } else {
                if (!deployKey.matches("[A-Za-z0-9_-]+")) return ResponseEntity.notFound().build();
                base = java.nio.file.Path.of(DEPLOY_ROOT_DIR, deployKey);
            }
            return fileResponse(base, resourcePath);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private ResponseEntity<Resource> fileResponse(java.nio.file.Path base, String path) throws java.io.IOException {
        if (!path.startsWith("/")) return ResponseEntity.notFound().build();
        java.nio.file.Path root = base.toAbsolutePath().normalize();
        java.nio.file.Path target = root.resolve(path.substring(1)).normalize();
        if (!target.startsWith(root) || !java.nio.file.Files.isRegularFile(target)
                || !target.toRealPath().startsWith(root.toRealPath())) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header("Cache-Control", "no-cache")
                .header("Content-Type", getContentTypeWithCharset(target.toString())).body(new FileSystemResource(target));
    }

    /**
     * 根据文件扩展名返回带字符编码的 Content-Type
     */
    private String getContentTypeWithCharset(String filePath) {
        if (filePath.endsWith(".html")) return "text/html; charset=UTF-8";
        if (filePath.endsWith(".css")) return "text/css; charset=UTF-8";
        if (filePath.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (filePath.endsWith(".svg")) return "image/svg+xml; charset=UTF-8";
        if (filePath.endsWith(".png")) return "image/png";
        if (filePath.endsWith(".jpg")) return "image/jpeg";
        return "application/octet-stream";
    }
}
