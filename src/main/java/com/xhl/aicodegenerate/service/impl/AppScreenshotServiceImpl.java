package com.xhl.aicodegenerate.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadFactoryBuilder;
import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.service.AppScreenshotService;
import com.xhl.aicodegenerate.service.AppService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Selenium 4 Headless Chrome 的应用封面截图服务实现类
 */
@Slf4j
@Service
public class AppScreenshotServiceImpl implements AppScreenshotService {

    @Lazy
    @Resource
    private AppService appService;

    @Autowired(required = false)
    private CacheManager cacheManager;

    @org.springframework.beans.factory.annotation.Value("${server.port:8123}")
    private int serverPort = 8123;

    @org.springframework.beans.factory.annotation.Value("${server.servlet.context-path:/api}")
    private String contextPath = "/api";

    /**
     * 单独的截图线程池，最大并发 2，避免并发启动过多 Chrome 造成系统 OOM
     */
    private final ExecutorService screenshotExecutor = new ThreadPoolExecutor(
            1,
            2,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(20),
            ThreadFactoryBuilder.create().setNamePrefix("screenshot-pool-").build(),
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    @Override
    public void captureAppCoverAsync(Long appId, String targetUrl) {
        if (appId == null || StrUtil.isBlank(targetUrl)) {
            return;
        }
        screenshotExecutor.submit(() -> {
            try {
                captureAppCover(appId, targetUrl);
            } catch (Throwable e) {
                log.warn("异步生成封面截图未捕获异常: appId={}, error={}", appId, e.getMessage());
            }
        });
    }

    @Override
    public String captureAppCover(Long appId, String targetUrl) {
        if (appId == null || StrUtil.isBlank(targetUrl)) {
            return null;
        }
        String finalUrl = normalizeTargetUrl(targetUrl);
        if (StrUtil.isBlank(finalUrl)) {
            log.warn("应用 appId={} 的目标截图路径不存在或无效: targetUrl={}", appId, targetUrl);
            return null;
        }
        WebDriver driver = null;
        try {
            driver = createWebDriver();
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(10));
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(5));
            driver.get(finalUrl);

            // 针对现代前端 SPA (Vue/React)，等待 #app 或 body 内部挂载完成
            try {
                org.openqa.selenium.support.ui.WebDriverWait wait =
                        new org.openqa.selenium.support.ui.WebDriverWait(driver, Duration.ofSeconds(4));
                wait.until(webDriver -> {
                    try {
                        Object result = ((org.openqa.selenium.JavascriptExecutor) webDriver).executeScript(
                                "var app = document.getElementById('app') || document.getElementById('root'); " +
                                "if (app && app.children.length > 0) return true; " +
                                "return document.body && document.body.innerText.trim().length > 0;"
                        );
                        return Boolean.TRUE.equals(result);
                    } catch (Exception e) {
                        return true;
                    }
                });
            } catch (Exception ignored) {
            }

            // 适当缓冲动画与图片动态渲染
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            // 检查网页标题与内容是否属于浏览器错误页（如 ERR_ / 404）
            String title = driver.getTitle();
            String pageSource = driver.getPageSource();
            if (StrUtil.isNotBlank(title) && (title.contains("404") || title.contains("ERR_") || title.contains("Not Found"))) {
                log.warn("截图页面加载异常 (title={}): appId={}, targetUrl={}", title, appId, finalUrl);
                return null;
            }
            if (pageSource != null && (pageSource.contains("dnserror") || pageSource.contains("neterror") || pageSource.contains("ERR_CONNECTION_REFUSED"))) {
                log.warn("截图页面网络错误: appId={}, targetUrl={}", appId, finalUrl);
                return null;
            }

            TakesScreenshot ts = (TakesScreenshot) driver;
            byte[] screenshotBytes = ts.getScreenshotAs(OutputType.BYTES);

            // 若截图字节过小（如小于 5000 字节，通常属于首屏未渲染完毕的空白），二次等待重试截取一次
            if (screenshotBytes != null && screenshotBytes.length < 5000) {
                try {
                    Thread.sleep(1500);
                    screenshotBytes = ts.getScreenshotAs(OutputType.BYTES);
                } catch (Exception ignored) {
                }
            }

            File coverDir = new File(AppConstant.COVER_OUTPUT_ROOT_DIR);
            if (!coverDir.exists()) {
                coverDir.mkdirs();
            }
            File targetFile = new File(coverDir, "app_" + appId + ".png");
            FileUtil.writeBytes(screenshotBytes, targetFile);

            String relativeUrl = String.format("/api/static/cover/app_%d.png?v=%d", appId, System.currentTimeMillis());

            App updateApp = new App();
            updateApp.setId(appId);
            updateApp.setCover(relativeUrl);
            appService.updateById(updateApp);

            // 主动清除精选应用缓存
            if (cacheManager != null) {
                try {
                    Cache cache = cacheManager.getCache("featuredApp");
                    if (cache != null) {
                        cache.clear();
                    }
                } catch (Exception e) {
                    log.warn("清理精选应用缓存失败: {}", e.getMessage());
                }
            }

            log.info("成功为应用 appId={} 生成封面截图: targetUrl={}, path={}, coverUrl={}",
                    appId, finalUrl, targetFile.getAbsolutePath(), relativeUrl);
            return relativeUrl;
        } catch (Throwable e) {
            log.warn("为应用 appId={} 生成封面截图失败 (已静默降级): targetUrl={}, error={}",
                    appId, finalUrl, e.getMessage());
            return null;
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 规范化访问 URL，优先将本地目录映射为本地 HTTP 预览服务（防止 file:// 协议下 ES Module 触发 CORS 导致白屏），
     * 兜底支持 HTTP(S)、file:// 协议。
     */
    private String normalizeTargetUrl(String targetUrl) {
        if (StrUtil.isBlank(targetUrl)) {
            return null;
        }
        if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
            return targetUrl;
        }

        File file = new File(targetUrl);
        String absPath = file.getAbsolutePath();

        String normalizedContextPath = StrUtil.blankToDefault(contextPath, "");
        if (StrUtil.isNotBlank(normalizedContextPath) && !normalizedContextPath.startsWith("/")) {
            normalizedContextPath = "/" + normalizedContextPath;
        }
        if (normalizedContextPath.endsWith("/")) {
            normalizedContextPath = normalizedContextPath.substring(0, normalizedContextPath.length() - 1);
        }

        // 1. 如果路径位于应用部署目录 CODE_DEPLOY_ROOT_DIR 内
        String deployRoot = new File(AppConstant.CODE_DEPLOY_ROOT_DIR).getAbsolutePath();
        if (absPath.startsWith(deployRoot)) {
            String relative = absPath.substring(deployRoot.length()).replace('\\', '/');
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            String deployKey = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : relative;
            if (StrUtil.isNotBlank(deployKey)) {
                return String.format("http://localhost:%d%s/static/%s/", serverPort, normalizedContextPath, deployKey);
            }
        }

        // 2. 如果路径位于应用生成目录 CODE_OUTPUT_ROOT_DIR 内
        String outputRoot = new File(AppConstant.CODE_OUTPUT_ROOT_DIR).getAbsolutePath();
        if (absPath.startsWith(outputRoot)) {
            String relative = absPath.substring(outputRoot.length()).replace('\\', '/');
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            String dirKey = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : relative;
            if (StrUtil.isNotBlank(dirKey)) {
                return String.format("http://localhost:%d%s/static/%s/", serverPort, normalizedContextPath, dirKey);
            }
        }

        // 3. 如果已经是 file:// 协议
        if (targetUrl.startsWith("file://")) {
            return targetUrl;
        }

        // 4. 其他本地文件或临时文件
        if (file.isDirectory()) {
            File vueDist = new File(file, "dist/index.html");
            if (vueDist.exists()) {
                return "file://" + vueDist.getAbsolutePath();
            }
            File indexHtml = new File(file, "index.html");
            if (indexHtml.exists()) {
                return "file://" + indexHtml.getAbsolutePath();
            }
            return null;
        } else if (file.exists()) {
            return "file://" + file.getAbsolutePath();
        }
        return null;
    }

    /**
     * 创建 Headless Chrome 驱动
     */
    private WebDriver createWebDriver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1280,800");
        options.addArguments("--hide-scrollbars");
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--allow-file-access-from-files");
        options.addArguments("--disable-web-security");

        // 优先检查用户自定义路径
        String customChromeBinary = System.getProperty("chrome.binary.path", System.getenv("CHROME_BINARY_PATH"));
        if (StrUtil.isNotBlank(customChromeBinary) && new File(customChromeBinary).exists()) {
            options.setBinary(customChromeBinary);
        } else {
            // macOS 默认路径适配
            File macChrome = new File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            if (macChrome.exists()) {
                options.setBinary(macChrome);
            }
        }
        return new ChromeDriver(options);
    }

    @PreDestroy
    public void destroy() {
        try {
            screenshotExecutor.shutdown();
        } catch (Exception ignored) {
        }
    }
}
