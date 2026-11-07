package com.xhl.aicodegenerate.observability;

import com.sun.net.httpserver.HttpServer;
import com.xhl.aicodegenerate.common.BaseResponse;
import com.xhl.aicodegenerate.common.ResultUtils;
import com.xhl.aicodegenerate.config.CorsConfig;
import com.xhl.aicodegenerate.config.JsonConfig;
import com.xhl.aicodegenerate.controller.GenerationTraceController;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.exception.GlobalExceptionHandler;
import com.xhl.aicodegenerate.mapper.GenerationTraceMapper;
import com.xhl.aicodegenerate.mapper.GenerationTraceStepMapper;
import com.xhl.aicodegenerate.service.GenerationTraceService;
import com.xhl.aicodegenerate.service.UserService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实前端构建、Cookie/CORS、追踪控制器及 MySQL；业务应用和任务入口仅使用隔离夹具。 */
class TraceWorkbenchBrowserTest {
    private static final Path EVIDENCE = Path.of(".evidence/generation-observability-20261107");
    private static final String TASK = "20000000-0000-0000-0000-000000000001";
    private static GenerationTraceMapper roots;
    private static GenerationTraceStepMapper steps;
    private static GenerationTraceRecorder recorder;
    private static volatile boolean unavailable;
    private static volatile java.util.concurrent.CountDownLatch delayedQuery;
    private static volatile java.util.concurrent.CountDownLatch queryEntered;

    @Test void ownerHistoryPaginationDiagnosticsAndCrossOriginCookieAreUsable() throws Exception {
        String url = System.getProperty("trace.test.mysql.url");
        assertNotNull(url, "必须显式指定隔离库");
        assertTrue(url.contains("/generation_trace_test"));
        assertTrue(Files.isRegularFile(Path.of("ai-code-frontend/dist/index.html")), "先执行前端构建");
        var dataSource = new DriverManagerDataSource(url, "root", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS app(id BIGINT PRIMARY KEY,userId BIGINT NOT NULL,isDelete TINYINT NOT NULL DEFAULT 0)");
        for (String sql : Files.readString(Path.of("sql/migrations/20261107_generation_trace.sql")).split(";")) {
            if (!sql.isBlank()) jdbc.execute(sql);
        }
        jdbc.execute("DELETE FROM generation_trace_step"); jdbc.execute("DELETE FROM generation_trace"); jdbc.execute("DELETE FROM app");
        jdbc.update("INSERT INTO app(id,userId) VALUES(7,9),(8,9)");
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(GenerationTraceMapper.class); configuration.addMapper(GenerationTraceStepMapper.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(dataSource); factory.setConfiguration(configuration);
        var sql = new SqlSessionTemplate(factory.getObject());
        roots = sql.getMapper(GenerationTraceMapper.class); steps = sql.getMapper(GenerationTraceStepMapper.class);
        var store = new MybatisTraceStore(roots, steps, new DataSourceTransactionManager(dataSource));
        WebServer server = null;
        HttpServer frontend = null;
        WebDriver browser = null;
        try (var traces = new GenerationTraceRecorder(store, java.time.Clock.systemUTC(), 4096)) {
            recorder = traces;
            unavailable = false;
            for (int i = 0; i < 21; i++) {
                var old = traces.startGeneration(UUID.randomUUID().toString(), 7L, 9L, "standard", "html");
                old.finish("SUCCEEDED");
            }
            var current = traces.startGeneration(TASK, 7L, 9L, "workflow", "vue_project");
            var parent = current.startStep(TraceSession.Kind.STAGE, "generation", null);
            var model = current.startStep(TraceSession.Kind.MODEL, "model", parent);
            model.model("fixture", "controlled", "a".repeat(64), "b".repeat(64));
            model.complete(12L, null, "STOP");
            current.startStep(TraceSession.Kind.MODEL, "model", parent).succeed();
            current.startStep(TraceSession.Kind.BUILD, "build", parent).fail(new IllegalStateException("secret=hidden"));
            current.startStep(TraceSession.Kind.BUILD, "build", parent).succeed();
            for (int i = 0; i < 100; i++) {
                var tool = current.startStep(TraceSession.Kind.TOOL, "writeFile", parent);
                tool.metadata(Map.of("toolName", "writeFile", "relativePath", "src/组件" + i + ".vue")); tool.succeed();
            }
            parent.succeed(); current.finish("SUCCEEDED");
            assertTrue(traces.awaitIdle(Duration.ofSeconds(8)));
            store.markIncomplete(current.traceId(), traces.instanceId());
            server = startBackend();
            frontend = startFrontend();
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1440,1050");
            Path chrome = Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            if (Files.isRegularFile(chrome)) options.setBinary(chrome.toFile());
            browser = new ChromeDriver(options);
            browser.manage().timeouts().scriptTimeout(Duration.ofSeconds(8));
            login(browser, 9);
            browser.get("http://localhost:23317/");
            awaitText(browser, "用自然语言");
            Object header = ((JavascriptExecutor) browser).executeAsyncScript("const done=arguments[arguments.length-1];fetch('http://localhost:8123/api/app/add',{method:'POST',credentials:'include',headers:{'Content-Type':'application/json'},body:'{}'}).then(r=>done(r.headers.get('X-Request-Id'))).catch(e=>done('fetch-failed'));");
            assertNotNull(header, "带 Cookie 的跨域请求必须能读取创建请求编号");
            assertTrue(header.toString().matches("[a-f0-9-]{36}"));
            browser.get("http://localhost:23317/app/chat/7");
            click(browser, "生成记录");
            awaitText(browser, "追踪不完整");
            awaitText(browser, "已知 12 Token · 1/2 次上报");
            awaitText(browser, "未知 · 0/2 次上报");
            assertEquals(100, browser.findElements(By.cssSelector(".trace-steps > li")).size());
            click(browser, "加载更多步骤");
            new WebDriverWait(browser, Duration.ofSeconds(8)).until(d -> d.findElements(By.cssSelector(".trace-steps > li")).size() == 105);
            click(browser, "加载更多记录");
            new WebDriverWait(browser, Duration.ofSeconds(8)).until(d -> d.findElements(By.cssSelector(".trace-record")).size() == 22);
            // 键盘展开必须保留失败与下一次成功，不能把失败步骤覆盖掉。
            var buildRow = browser.findElements(By.cssSelector(".trace-steps summary")).get(3);
            buildRow.sendKeys(Keys.ENTER);
            awaitText(browser, "执行失败，请结合步骤类别定位");
            screenshot(browser, "t4-workbench-desktop.png");
            ((ChromeDriver) browser).executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of(
                    "width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
            assertEquals(390L, ((JavascriptExecutor) browser).executeScript("return window.innerWidth"));
            screenshot(browser, "t4-workbench-mobile.png");
            assertEquals(Boolean.TRUE, ((JavascriptExecutor) browser).executeScript("return document.querySelector('.trace-panel').scrollWidth<=document.querySelector('.trace-panel').clientWidth+1"));
            unavailable = true;
            click(browser, "重新查询"); awaitText(browser, "已加载步骤保留");
            assertEquals(105, browser.findElements(By.cssSelector(".trace-steps > li")).size());
            unavailable = false;
            click(browser, "重新查询");
            new WebDriverWait(browser, Duration.ofSeconds(8)).until(d -> !d.findElement(By.cssSelector(".trace-detail")).getText().contains("已加载步骤保留"));
            // 切换应用时即使旧查询晚到，也不能显示旧应用步骤或遗留加载状态。
            delayedQuery = new java.util.concurrent.CountDownLatch(1);
            queryEntered = new java.util.concurrent.CountDownLatch(1);
            click(browser, "重新查询");
            assertTrue(queryEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            ((JavascriptExecutor) browser).executeScript("history.pushState({},'', '/app/chat/8');window.dispatchEvent(new PopStateEvent('popstate'));");
            delayedQuery.countDown(); delayedQuery = null;
            awaitText(browser, "尚无可查询记录");
            assertTrue(browser.findElements(By.cssSelector(".trace-steps > li")).isEmpty());
            assertEquals("false", browser.findElement(By.cssSelector(".trace-detail")).getDomAttribute("aria-busy"));
            var running = traces.startGeneration(UUID.randomUUID().toString(), 8L, 9L, "standard", "html");
            for (int i = 0; i < 100; i++) running.startStep(TraceSession.Kind.TOOL, "writeFile", null).succeed();
            var lastStep = running.startStep(TraceSession.Kind.PREVIEW, "preview", null);
            assertTrue(traces.awaitIdle(Duration.ofSeconds(5)));
            click(browser, "刷新列表"); awaitText(browser, "生成运行中");
            click(browser, "加载更多步骤");
            new WebDriverWait(browser, Duration.ofSeconds(8)).until(d -> d.findElements(By.cssSelector(".trace-steps > li")).size() == 101);
            lastStep.succeed(); running.finish("SUCCEEDED");
            assertTrue(traces.awaitIdle(Duration.ofSeconds(5)));
            new WebDriverWait(browser, Duration.ofSeconds(8)).until(d -> {
                var states = d.findElements(By.cssSelector(".trace-steps .step-status"));
                return states.size() == 101 && "成功".equals(states.getLast().getText());
            });
            // 直接 HTTP 请求同时检查列表、任务、详情和步骤；管理员也没有他人追踪权限。
            login(browser, 10);
            browser.get("http://localhost:23317/app/chat/7"); awaitText(browser, "追踪验收应用");
            assertTrue(browser.findElements(By.xpath("//button[normalize-space(.)='生成记录']")).isEmpty());
            for (String path : List.of("?appId=7", "/by-task/" + TASK, "/" + current.traceId(), "/" + current.traceId() + "/steps", "/by-request/" + header)) {
                Object code = ((JavascriptExecutor) browser).executeAsyncScript("const done=arguments[arguments.length-1];fetch('http://localhost:8123/api/app/traces'+arguments[0],{credentials:'include'}).then(r=>r.json()).then(r=>done(r.code));", path);
                assertEquals(40101L, code);
            }
            login(browser, 9);
            browser.get("http://localhost:23317/"); awaitText(browser, "用自然语言");
            browser.findElement(By.cssSelector("textarea")).sendKeys("受控创建失败需求");
            browser.findElement(By.cssSelector("button[aria-label='创建应用']")).click();
            awaitText(browser, "受控路由不可用"); awaitText(browser, "请求编号：");
            click(browser, "查看创建诊断"); awaitText(browser, "创建失败");
            assertTrue(browser.findElement(By.cssSelector(".creation-diagnostic")).getText().contains("受控路由不可用"));
            screenshot(browser, "t4-creation-diagnostic-mobile.png");
            assertFalse(browser.findElement(By.cssSelector(".trace-panel")).getText().contains("secret=hidden"));
            // 删除立即影响查询，不等待物理清理；页面清除已经显示的详情。
            browser.get("http://localhost:23317/app/chat/7"); click(browser, "生成记录"); awaitText(browser, "追踪不完整");
            jdbc.update("UPDATE app SET isDelete=1 WHERE id=7");
            click(browser, "重新查询"); awaitText(browser, "应用删除后也不可查询");
            assertTrue(browser.findElements(By.cssSelector(".trace-steps > li")).isEmpty());
            Files.writeString(EVIDENCE.resolve("t4-browser-result.json"), "{\"cookieCors\":true,\"historyPagination\":22,\"stepPagination\":105,\"unknownUsage\":true,\"queryFailureRetainsSteps\":true,\"ownerOnly\":true,\"creationDiagnostic\":true,\"deletedDetailCleared\":true,\"mobileViewport\":390,\"mobileNoPanelOverflow\":true,\"staleResponseIgnored\":true,\"loadedPagesPollToTerminal\":true,\"modelMode\":\"fixture\"}\n");
        } finally {
            if (delayedQuery != null) delayedQuery.countDown();
            delayedQuery = null;
            if (browser != null) browser.quit();
            if (frontend != null) frontend.stop(0);
            if (server != null) server.stop();
        }
    }

    private static void login(WebDriver browser, long user) { browser.get("http://localhost:8123/api/fixture/login?user=" + user); }
    private static void awaitText(WebDriver browser, String text) {
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> d.findElement(By.tagName("body")).getText().contains(text));
    }
    private static void click(WebDriver browser, String text) {
        var button = new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> {
            var found = d.findElements(By.xpath("//button[normalize-space(.)='" + text + "']"));
            return found.stream().filter(e -> e.isDisplayed() && e.isEnabled()).findFirst().orElse(null);
        });
        button.click();
    }
    private static void screenshot(WebDriver browser, String name) throws Exception {
        Files.write(EVIDENCE.resolve(name), ((TakesScreenshot) browser).getScreenshotAs(OutputType.BYTES));
    }
    private static WebServer startBackend() throws Exception {
        var factory = new TomcatServletWebServerFactory(8123);
        factory.setAddress(InetAddress.getByName("127.0.0.1")); factory.setContextPath("/api");
        var server = factory.getWebServer(servlet -> {
            var context = new AnnotationConfigWebApplicationContext(); context.register(WebConfig.class);
            var dispatcher = servlet.addServlet("dispatcher", new DispatcherServlet(context));
            dispatcher.setLoadOnStartup(1); dispatcher.addMapping("/");
            servlet.addFilter("requestId", new CreationRequestIdFilter()).addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
        });
        server.start(); return server;
    }
    private static HttpServer startFrontend() throws Exception {
        Path root = Path.of("ai-code-frontend/dist").toRealPath();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 23317), 0);
        server.createContext("/", exchange -> {
            try {
                Path file = root.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
                if (!file.startsWith(root)) { exchange.sendResponseHeaders(404, -1); return; }
                if (!Files.isRegularFile(file)) file = root.resolve("index.html");
                String name = file.getFileName().toString();
                String type = name.endsWith(".js") ? "application/javascript" : name.endsWith(".css") ? "text/css" : name.endsWith(".woff2") ? "font/woff2" : name.endsWith(".svg") ? "image/svg+xml" : "text/html";
                exchange.getResponseHeaders().set("Content-Type", type + ";charset=UTF-8");
                byte[] bytes = Files.readAllBytes(file); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start(); return server;
    }

    @Configuration @EnableWebMvc @Import({CorsConfig.class, GenerationTraceController.class, GlobalExceptionHandler.class, FixtureController.class})
    static class WebConfig implements WebMvcConfigurer {
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            var mapper = new JsonConfig().jacksonObjectMapper(new Jackson2ObjectMapperBuilder());
            mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            converters.add(new MappingJackson2HttpMessageConverter(mapper));
        }
        @Bean GenerationTraceService traces() {
            var queries = mock(GenerationTraceMapper.class, invocation -> {
                if (unavailable) throw new DataAccessResourceFailureException("secret=hidden");
                var gate = delayedQuery;
                if (gate != null && "byTask".equals(invocation.getMethod().getName())) {
                    queryEntered.countDown();
                    if (!gate.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("受控延迟未释放");
                }
                return invocation.getMethod().invoke(roots, invocation.getArguments());
            });
            return new GenerationTraceService(queries, steps, recorder);
        }
        @Bean UserService users() {
            var users = mock(UserService.class);
            when(users.getLoginUser(any())).thenAnswer(invocation -> {
                HttpServletRequest request = invocation.getArgument(0);
                Object id = request.getSession(false) == null ? null : request.getSession(false).getAttribute("fixture-user");
                if (!(id instanceof Long userId)) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
                var user = new User(); user.setId(userId); user.setUserRole(userId == 10L ? "admin" : "user"); return user;
            });
            return users;
        }
    }
    @RestController static class FixtureController {
        private final UserService users;
        FixtureController(UserService users) { this.users = users; }
        @GetMapping("/fixture/login") String login(@RequestParam long user, HttpServletRequest request) {
            request.getSession().setAttribute("fixture-user", user); return "隔离会话已建立";
        }
        @GetMapping("/user/get/login") BaseResponse<?> user(HttpServletRequest request) { return ResultUtils.success(users.getLoginUser(request)); }
        @GetMapping("/app/get/vo") BaseResponse<?> app(@RequestParam String id) {
            return ResultUtils.success(Map.of("id", id, "userId", "9", "appName", "追踪验收应用", "generationMode", "workflow", "codeGenType", "vue_project"));
        }
        @PostMapping({"/app/my/list/page/vo", "/app/list/page/featured", "/chatHistory/app/list/page/vo"}) BaseResponse<?> empty() {
            return ResultUtils.success(Map.of("records", List.of(), "totalRow", 0));
        }
        @GetMapping("/app/generation/tasks/current") BaseResponse<?> task(@RequestParam String appId) {
            if ("8".equals(appId)) return ResultUtils.success(java.util.Collections.singletonMap("task", null));
            return ResultUtils.success(Map.of("previewAvailable", false, "task", Map.of("id", TASK, "appId", "7", "clientRequestId", TASK,
                    "prompt", "受控追踪需求", "status", "SUCCEEDED", "stage", "完成", "message", "受控完成", "output", "追踪验收完成", "revision", 1, "steps", List.of())));
        }
        @PostMapping("/app/add") BaseResponse<?> fail(HttpServletRequest request) {
            String id = (String) request.getAttribute(CreationRequestIdFilter.ATTRIBUTE);
            var creation = recorder.startCreation(id, users.getLoginUser(request).getId(), "standard");
            creation.startStep(TraceSession.Kind.MODEL, "routing", null).fail(new IllegalStateException("secret=hidden"));
            creation.failedBeforeApplicationSave(); creation.finish("FAILED");
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "受控路由不可用，请保留需求后重试");
        }
    }
}
