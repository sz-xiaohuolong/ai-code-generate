package com.xhl.aicodegenerate.evaluation;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.SimpleFileServer;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.ElementNotInteractableException;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;

/** 对固定产物通过真实 HTTP 和 Chrome 执行维护者断言，不修改生成任务或自动修复产物。 */
public final class EvaluationBrowser {
    public enum Status { PASS, FAIL, ERROR, NOT_APPLICABLE, SKIPPED, CANCELLED }
    public record Step(int number, EvaluationDataset.Check check, Status status, String observed, String diagnostic) { }
    public record Result(Status load, Status business, List<Step> steps, boolean evidenceComplete,
            String browserVersion, String screenshot, List<String> diagnostics) {
        public Result { steps = List.copyOf(steps); diagnostics = List.copyOf(diagnostics); }
    }
    public static final int VIEWPORT_WIDTH = 1280;
    public static final int VIEWPORT_HEIGHT = 800;
    private final Duration timeout;
    private final Supplier<WebDriver> browsers;

    public EvaluationBrowser(Duration timeout) { this(timeout, EvaluationBrowser::chrome); }

    EvaluationBrowser(Duration timeout, Supplier<WebDriver> browsers) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new EvaluationConfigurationException("浏览器断言等待时限必须为正值");
        }
        this.timeout = timeout;
        this.browsers = Objects.requireNonNull(browsers);
    }

    public Result verify(Path artifact, boolean vue, EvaluationDataset.Assertions specification, Path evidence,
            EvaluationBudget budget) {
        Status load = Status.SKIPPED;
        Status business = Status.SKIPPED;
        List<Step> steps = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        String version = null;
        String screenshot = null;
        boolean evidenceComplete = true;
        HttpServer server = null;
        WebDriver browser = null;
        var http = Executors.newVirtualThreadPerTaskExecutor();
        Set<String> missing = ConcurrentHashMap.newKeySet();
        try {
            budget.checkActive();
            Path root = (vue ? artifact.resolve("dist") : artifact).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(root.resolve("index.html"), LinkOption.NOFOLLOW_LINKS)) {
                load = Status.FAIL;
                diagnostics.add("产物缺少有效页面入口");
            } else {
                // ponytail: 使用 JDK 21 静态文件处理器；它拒绝符号链接和越界路径，不另写文件路由。
                var handler = SimpleFileServer.createFileHandler(root);
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(http);
                server.createContext("/", exchange -> {
                    handler.handle(exchange);
                    if (exchange.getResponseCode() >= 400
                            && exchange.getRequestURI().getPath().matches(".*\\.(?:js|mjs|css)$")) {
                        missing.add(exchange.getRequestURI().getPath());
                    }
                });
                server.start();
                browser = browsers.get();
                version = ((HasCapabilities) browser).getCapabilities().getBrowserVersion();
                browser.manage().timeouts().implicitlyWait(Duration.ZERO);
                browser.manage().timeouts().pageLoadTimeout(bounded(Duration.ofSeconds(10), budget));
                browser.manage().timeouts().scriptTimeout(bounded(Duration.ofSeconds(5), budget));
                setViewport(browser);
                try {
                    browser.get("http://127.0.0.1:" + server.getAddress().getPort() + "/");
                    waitFor(browser, budget).until(driver -> {
                        budget.checkActive();
                        return ((JavascriptExecutor) driver).executeScript(vue
                                ? "const a=document.querySelector('#app');return !!a&&a.children.length>0&&a.hasAttribute('data-v-app')&&!!a.__vue_app__&&a.__vue_app__._container===a;"
                                : "return !!document.body&&(document.body.innerText.trim().length>0||document.body.children.length>0);");
                    });
                    load = browserErrors(browser, missing, diagnostics) ? Status.FAIL : Status.PASS;
                } catch (TimeoutException error) {
                    load = Status.FAIL;
                    diagnostics.add("页面未在规定时间内加载并呈现内容");
                }
                if (load == Status.PASS) {
                    business = specification.checks().isEmpty() ? Status.NOT_APPLICABLE : Status.PASS;
                    for (var check : specification.checks()) {
                        Step step = execute(browser, check, steps.size() + 1, budget);
                        steps.add(step);
                        if (step.status() != Status.PASS) { business = step.status(); break; }
                    }
                    if (browserErrors(browser, missing, diagnostics)) {
                        load = Status.FAIL;
                        if (business == Status.PASS || business == Status.NOT_APPLICABLE) business = Status.FAIL;
                    }
                }
            }
        } catch (EvaluationBudget.Exhausted error) {
            if (load == Status.SKIPPED) load = Status.CANCELLED;
            business = Status.CANCELLED;
            diagnostics.add(error.getMessage());
        } catch (IOException error) {
            if (load == Status.SKIPPED) load = Status.ERROR;
            diagnostics.add("评测页面服务不可用");
        } catch (RuntimeException error) {
            if (load == Status.SKIPPED) load = Status.ERROR;
            else if (load == Status.PASS) business = Status.ERROR;
            diagnostics.add(browser == null ? "浏览器环境不可用" : "浏览器执行环境异常");
        } finally {
            for (int i = steps.size(); i < specification.checks().size(); i++) {
                steps.add(new Step(i + 1, specification.checks().get(i), Status.SKIPPED, null, "前置步骤未通过或执行已停止"));
            }
            if (browser != null) {
                try {
                    Files.createDirectories(evidence);
                    Path temporary = evidence.resolve("browser.png.tmp");
                    Files.write(temporary, ((TakesScreenshot) browser).getScreenshotAs(OutputType.BYTES), StandardOpenOption.CREATE_NEW);
                    Files.move(temporary, evidence.resolve("browser.png"), StandardCopyOption.ATOMIC_MOVE);
                    screenshot = "browser.png";
                } catch (IOException | RuntimeException error) {
                    evidenceComplete = false;
                    diagnostics.add("浏览器截图未保存");
                }
                try { browser.quit(); }
                catch (RuntimeException error) { evidenceComplete = false; diagnostics.add("浏览器资源关闭失败"); }
            } else {
                evidenceComplete = false;
            }
            if (server != null) server.stop(0);
            http.shutdownNow();
        }
        return new Result(load, business, steps, evidenceComplete, version, screenshot, diagnostics);
    }

    private Step execute(WebDriver browser, EvaluationDataset.Check check, int number, EvaluationBudget budget) {
        String[] observed = {null};
        try {
            budget.checkActive();
            By locator = By.cssSelector(check.selector());
            if (Set.of("click", "fill", "select").contains(check.action())) {
                WebElement target = waitFor(browser, budget).until(driver -> {
                    budget.checkActive();
                    return driver.findElements(locator).stream().filter(WebElement::isDisplayed)
                            .filter(WebElement::isEnabled).findFirst().orElse(null);
                });
                switch (check.action()) {
                    case "click" -> target.click();
                    case "fill" -> { target.clear(); target.sendKeys(check.value()); }
                    case "select" -> new Select(target).selectByValue(check.value());
                    default -> throw new IllegalStateException("不支持的交互动作");
                }
            } else {
                waitFor(browser, budget).until(driver -> {
                    budget.checkActive();
                    var elements = driver.findElements(locator);
                    return switch (check.action()) {
                        case "count" -> {
                            observed[0] = Long.toString(elements.stream().filter(WebElement::isDisplayed).count());
                            yield observed[0].equals(check.expected());
                        }
                        case "visible" -> {
                            observed[0] = Boolean.toString(elements.stream().anyMatch(WebElement::isDisplayed));
                            yield observed[0].equals(check.expected());
                        }
                        case "text" -> {
                            var target = elements.stream().filter(WebElement::isDisplayed).findFirst();
                            // 空文本元素本身可以没有可见尺寸；必须存在，不能把缺失节点当作空文本通过。
                            if (target.isEmpty() && check.expected().isEmpty() && !elements.isEmpty()) target = elements.stream().findFirst();
                            observed[0] = target.map(WebElement::getText).orElse(null);
                            yield observed[0] != null && observed[0].strip().equals(check.expected());
                        }
                        default -> throw new IllegalArgumentException("不支持的验收动作");
                    };
                });
            }
            return new Step(number, check, Status.PASS, boundedText(observed[0]), null);
        } catch (TimeoutException | NoSuchElementException | ElementNotInteractableException | StaleElementReferenceException error) {
            return new Step(number, check, Status.FAIL, boundedText(observed[0]), "元素或业务状态未满足固定断言");
        } catch (EvaluationBudget.Exhausted error) {
            throw error;
        } catch (RuntimeException error) {
            return new Step(number, check, Status.ERROR, boundedText(observed[0]), "验收规格或浏览器执行异常");
        }
    }

    private WebDriverWait waitFor(WebDriver browser, EvaluationBudget budget) {
        var wait = new WebDriverWait(browser, bounded(timeout, budget));
        wait.pollingEvery(Duration.ofMillis(100)).ignoring(StaleElementReferenceException.class);
        return wait;
    }

    private Duration bounded(Duration maximum, EvaluationBudget budget) {
        budget.checkActive();
        Duration remaining = budget.remaining();
        return remaining.compareTo(maximum) < 0 ? remaining : maximum;
    }

    private boolean browserErrors(WebDriver browser, Set<String> missing, List<String> diagnostics) {
        boolean failed = !missing.isEmpty();
        if (failed && !diagnostics.contains("页面关键资源加载失败")) diagnostics.add("页面关键资源加载失败");
        for (var entry : browser.manage().logs().get(LogType.BROWSER)) {
            String message = entry.getMessage();
            if (message.contains("Uncaught") || message.contains("Failed to load module script") || message.contains("Failed to resolve module")) {
                failed = true;
                if (!diagnostics.contains("页面脚本执行失败")) diagnostics.add("页面脚本执行失败");
            }
        }
        return failed;
    }

    private static String boundedText(String text) {
        return text == null ? null : text.substring(0, Math.min(text.length(), 4096));
    }

    private static WebDriver chrome() {
        var options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--window-size=1280,800");
        Path mac = Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        if (Files.isRegularFile(mac)) options.setBinary(mac.toFile());
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        var logs = new LoggingPreferences();
        logs.enable(LogType.BROWSER, Level.ALL);
        options.setCapability("goog:loggingPrefs", logs);
        return new ChromeDriver(options);
    }

    private static void setViewport(WebDriver browser) {
        long extraWidth = ((Number) ((JavascriptExecutor) browser).executeScript("return window.outerWidth-window.innerWidth")).longValue();
        long extraHeight = ((Number) ((JavascriptExecutor) browser).executeScript("return window.outerHeight-window.innerHeight")).longValue();
        browser.manage().window().setSize(new Dimension(VIEWPORT_WIDTH + (int) extraWidth, VIEWPORT_HEIGHT + (int) extraHeight));
    }
}
