package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.service.AppScreenshotService;
import com.xhl.aicodegenerate.service.impl.AppServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationCompatibilityTest {
    @TempDir Path root;

    @Test void deploymentCopiesVerifiedDistWithoutRebuildingSource() throws Exception {
        Files.createDirectories(root.resolve("dist"));
        Files.writeString(root.resolve("dist/index.html"), "已验证的构建产物");
        Files.writeString(root.resolve("index.html"), "尚未构建的源码入口");
        String key = "p0-test-" + UUID.randomUUID();
        Path deployed = Path.of(AppConstant.CODE_DEPLOY_ROOT_DIR, key);
        var app = App.builder().id(91L).userId(99L).codeGenType("vue_project").deployKey(key).build();
        var user = new User(); user.setId(99L);
        var workspace = mock(GenerationWorkspace.class);
        when(workspace.published(91L, "vue_project")).thenReturn(root);
        var screenshots = mock(AppScreenshotService.class);
        var service = spy(new AppServiceImpl());
        doReturn(app).when(service).getById(91L);
        doReturn(true).when(service).updateById(any(App.class));
        ReflectionTestUtils.setField(service, "generationWorkspace", workspace);
        ReflectionTestUtils.setField(service, "appScreenshotService", screenshots);
        try {
            service.deployApp(91L, user);
            assertEquals("已验证的构建产物", Files.readString(deployed.resolve("index.html")));
            assertEquals("尚未构建的源码入口", Files.readString(root.resolve("index.html")));
            verify(screenshots).captureAppCoverAsync(91L, deployed.toString());
        } finally { cn.hutool.core.io.FileUtil.del(deployed); }
    }

    @Test void publishedCoverUsesFixedVerifiedVersionUrl() {
        var screenshots = mock(AppScreenshotService.class);
        var runner = new DefaultGenerationTaskRunner(null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(runner, "screenshots", screenshots);
        var task = new GenerationTask(); task.setId("verified-version"); task.setAppId(91L); task.setCodeGenType("vue_project");
        runner.afterPublish(task);
        verify(screenshots).captureAppCoverAsync(91L, "http://localhost:8123/api/static/v_verified-version_vue_project_91/");
    }
}
