package com.xhl.aicodegenerate.controller;

import cn.hutool.core.io.FileUtil;
import com.xhl.aicodegenerate.constant.AppConstant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class StaticResourceControllerTest {

    private StaticResourceController controller;
    private File coverFile;

    @BeforeEach
    void setUp() {
        controller = new StaticResourceController();
        File coverDir = new File(AppConstant.COVER_OUTPUT_ROOT_DIR);
        if (!coverDir.exists()) {
            coverDir.mkdirs();
        }
        coverFile = new File(coverDir, "test_static_cover.png");
        FileUtil.writeBytes(new byte[]{1, 2, 3, 4}, coverFile);
    }

    @AfterEach
    void tearDown() {
        if (coverFile != null && coverFile.exists()) {
            coverFile.delete();
        }
    }

    @Test
    void previewRedirectPinsVersionAndRejectsCoverTraversal() throws Exception {
        var workspace = org.mockito.Mockito.mock(com.xhl.aicodegenerate.generation.GenerationWorkspace.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "generationWorkspace", workspace);
        org.mockito.Mockito.when(workspace.published(99L, "html")).thenReturn(java.nio.file.Path.of("tmp/generation_tasks/99/version-one"));
        org.mockito.Mockito.when(workspace.publishedVersion(99L)).thenReturn("version-one");
        var request = new MockHttpServletRequest("GET", "/api/static/html_99/");
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/static/html_99/");
        var response = controller.serveStaticResource("html_99", request);
        assertEquals(HttpStatus.TEMPORARY_REDIRECT, response.getStatusCode());
        assertTrue(response.getHeaders().getLocation().toString().contains("v_version-one_html_99/"));
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/static/cover/../../../../pom.xml");
        assertEquals(HttpStatus.NOT_FOUND, controller.serveStaticResource("cover", request).getStatusCode());
    }

    @Test
    void vuePreviewAcceptsLegacyDistPathAndPinsCanonicalVersion(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var workspace = org.mockito.Mockito.mock(com.xhl.aicodegenerate.generation.GenerationWorkspace.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "generationWorkspace", workspace);
        org.mockito.Mockito.when(workspace.published(99L, "vue_project")).thenReturn(root);
        org.mockito.Mockito.when(workspace.publishedVersion(99L)).thenReturn("version-one");
        org.mockito.Mockito.when(workspace.version(99L, "vue_project", "version-one")).thenReturn(root);
        java.nio.file.Files.createDirectories(root.resolve("dist/assets"));
        java.nio.file.Files.writeString(root.resolve("dist/index.html"), "<div id=app></div>");
        java.nio.file.Files.writeString(root.resolve("dist/assets/app.js"), "console.log('预览')");
        var request = new MockHttpServletRequest("GET", "/api/static/vue_project_99/dist/index.html");
        request.setContextPath("/api");
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/static/vue_project_99/dist/index.html");
        var redirect = controller.serveStaticResource("vue_project_99", request);
        assertEquals("/api/static/v_version-one_vue_project_99/index.html", redirect.getHeaders().getLocation().toString());
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/static/v_version-one_vue_project_99/dist/index.html");
        assertEquals(HttpStatus.OK, controller.serveStaticResource("v_version-one_vue_project_99", request).getStatusCode());
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/static/v_version-one_vue_project_99/assets/app.js");
        assertEquals(HttpStatus.OK, controller.serveStaticResource("v_version-one_vue_project_99", request).getStatusCode());
    }

    @Test
    void testServeStaticResource_CoverFound() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/static/cover/test_static_cover.png");

        ResponseEntity<Resource> response = controller.serveStaticResource("cover", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("image/png", response.getHeaders().getFirst("Content-Type"));
        assertNotNull(response.getBody());
    }

    @Test
    void testServeStaticResource_CoverNotFound() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/static/cover/non_existent.png");

        ResponseEntity<Resource> response = controller.serveStaticResource("cover", request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
