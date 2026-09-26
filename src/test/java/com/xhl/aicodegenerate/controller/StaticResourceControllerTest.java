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
