package com.xhl.aicodegenerate.generation;

import com.sun.net.httpserver.HttpServer;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Level;

/** 发布前通过临时 HTTP 服务验证实际页面，避免 file 协议掩盖模块加载问题。 */
@Component
public class PreviewVerifier {
    public BuildResult verify(Path source, boolean vue, GenerationExecution run) {
        Path root = vue ? source.resolve("dist") : source;
        if (!Files.isRegularFile(root.resolve("index.html")) || Files.isSymbolicLink(root))
            return failure("缺少有效的预览入口 index.html", BuildResult.Kind.CODE);
        HttpServer server = null; WebDriver browser = null;
        ExecutorService http = Executors.newVirtualThreadPerTaskExecutor();
        Set<String> missing = ConcurrentHashMap.newKeySet();
        try {
            if (run != null) run.checkActive();
            Path realRoot = root.toRealPath();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(http);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                Path file = realRoot.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
                try {
                    if (!file.startsWith(realRoot) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(realRoot)) {
                        if (path.matches(".*\\.(?:js|mjs|css)$")) missing.add(path);
                        exchange.sendResponseHeaders(404, -1); return;
                    }
                    String type = path.endsWith(".js") || path.endsWith(".mjs") ? "application/javascript" : path.endsWith(".css") ? "text/css" : path.endsWith(".svg") ? "image/svg+xml" : path.endsWith(".json") ? "application/json" : path.endsWith(".png") ? "image/png" : path.endsWith(".jpg") || path.endsWith(".jpeg") ? "image/jpeg" : path.endsWith(".webp") ? "image/webp" : path.endsWith(".woff2") ? "font/woff2" : "text/html";
                    exchange.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
                    byte[] bytes = Files.readAllBytes(file); exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start();
            HttpServer activeServer = server;
            if (run != null) run.onCancel(() -> activeServer.stop(0));
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--window-size=1280,800");
            Path chrome = Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            if (Files.isRegularFile(chrome)) options.setBinary(chrome.toFile());
            options.setPageLoadStrategy(PageLoadStrategy.EAGER);
            LoggingPreferences logs = new LoggingPreferences(); logs.enable(LogType.BROWSER, Level.ALL);
            options.setCapability("goog:loggingPrefs", logs);
            browser = new ChromeDriver(options);
            WebDriver activeBrowser = browser;
            if (run != null) run.onCancel(() -> { try { activeBrowser.quit(); } catch (RuntimeException ignored) { } });
            browser.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(10));
            browser.manage().timeouts().scriptTimeout(Duration.ofSeconds(5));
            browser.get("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            boolean rendered;
            try {
                rendered = Boolean.TRUE.equals(new WebDriverWait(browser, Duration.ofSeconds(4)).until(driver -> {
                    if (run != null) run.checkActive();
                    return ((JavascriptExecutor) driver).executeScript(vue
                            ? "const app=document.querySelector('#app'); return !!app && app.children.length>0 && app.hasAttribute('data-v-app') && !!app.__vue_app__ && app.__vue_app__._container===app;"
                            : "return !!document.body && (document.body.innerText.trim().length>0 || document.body.children.length>0);");
                }));
            } catch (org.openqa.selenium.TimeoutException e) { rendered = false; }
            StringBuilder errors = new StringBuilder();
            for (var entry : browser.manage().logs().get(LogType.BROWSER)) {
                String text = entry.getMessage();
                if (text.contains("Uncaught") || text.contains("Failed to load module script") || text.contains("Failed to resolve module"))
                    errors.append(text).append('\n');
            }
            if (run != null) run.checkActive();
            if (!rendered) errors.append(vue ? "Vue 应用未成功挂载到 #app\n" : "页面没有可显示内容\n");
            if (!missing.isEmpty()) errors.append("关键资源加载失败：").append(missing);
            if (!errors.isEmpty()) return failure(errors.substring(0, Math.min(errors.length(), 12000)), BuildResult.Kind.CODE);
            return new BuildResult("preview", 0, "入口、资源与页面挂载检查通过", "", BuildResult.Kind.NONE, false);
        } catch (java.util.concurrent.CancellationException | GenerationExecution.BudgetExceeded e) { throw e; }
        catch (Exception e) { return failure("预览验证环境不可用：" + e.getMessage(), BuildResult.Kind.ENVIRONMENT); }
        finally {
            if (browser != null) try { browser.quit(); } catch (RuntimeException ignored) { }
            if (server != null) server.stop(0);
            http.shutdownNow();
        }
    }
    private BuildResult failure(String message, BuildResult.Kind kind) { return new BuildResult("preview", -1, "", message, kind, false); }
}
