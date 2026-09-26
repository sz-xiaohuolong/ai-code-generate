package com.xhl.aicodegenerate.service;

import cn.hutool.core.io.FileUtil;
import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.service.impl.AppScreenshotServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppScreenshotServiceTest {

    @Mock
    private AppService appService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    @InjectMocks
    private AppScreenshotServiceImpl appScreenshotService;

    private File tempHtmlFile;

    @BeforeEach
    void setUp() throws IOException {
        tempHtmlFile = File.createTempFile("test_app_", ".html");
        FileUtil.writeUtf8String(
                "<!DOCTYPE html><html><body style='background:#1e1e2e;color:#cdd6f4;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;'><h1>AI Code Studio</h1></body></html>",
                tempHtmlFile
        );
    }

    @AfterEach
    void tearDown() {
        if (tempHtmlFile != null && tempHtmlFile.exists()) {
            tempHtmlFile.delete();
        }
        File testCover = new File(AppConstant.COVER_OUTPUT_ROOT_DIR, "app_999999.png");
        if (testCover.exists()) {
            testCover.delete();
        }
    }

    @Test
    void testCaptureAppCover_Success() {
        when(appService.updateById(any(App.class))).thenReturn(true);
        when(cacheManager.getCache("featuredApp")).thenReturn(cache);

        Long testAppId = 999999L;
        String coverUrl = appScreenshotService.captureAppCover(testAppId, tempHtmlFile.getAbsolutePath());

        assertNotNull(coverUrl);
        assertTrue(coverUrl.startsWith("/api/static/cover/app_999999.png"));

        File generatedFile = new File(AppConstant.COVER_OUTPUT_ROOT_DIR, "app_999999.png");
        assertTrue(generatedFile.exists(), "封面文件应成功生成落盘");
        assertTrue(generatedFile.length() > 0, "封面文件不应为空");

        ArgumentCaptor<App> appCaptor = ArgumentCaptor.forClass(App.class);
        verify(appService).updateById(appCaptor.capture());
        App updated = appCaptor.getValue();
        assertEquals(testAppId, updated.getId());
        assertEquals(coverUrl, updated.getCover());

        verify(cache).clear();
    }

    @Test
    void testCaptureAppCover_SilentFallbackOnError() {
        // 传入非法地址或无效协议，应静默捕获异常并返回 null，不抛出异常
        String result = appScreenshotService.captureAppCover(999998L, "invalid-protocol://non-existent-domain-12345.xyz");
        assertNull(result, "截图异常时应静默降级返回 null");
        verify(appService, never()).updateById(any(App.class));
    }
}
