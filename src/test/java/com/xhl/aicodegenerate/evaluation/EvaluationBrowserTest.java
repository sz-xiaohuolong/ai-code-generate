package com.xhl.aicodegenerate.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 Chrome 和本地 HTTP 验证业务行为，不用 WebDriver 模拟成功结果。 */
class EvaluationBrowserTest {
    @TempDir Path temporary;

    @Test void exercisesActionsAndCountsOnlyVisibleRows() throws Exception {
        Path site = page("good", """
                <input id="input"><button id="add" onclick="document.querySelector('#value').textContent=document.querySelector('#input').value">添加</button>
                <p id="value"></p><p id="empty"></p>
                <select id="filter" onchange="document.querySelector('#other').hidden=this.value==='done'"><option value="all">全部</option><option value="done">已完成</option></select>
                <div class="row">已完成</div><div class="row" id="other">未完成</div>
                """);
        var checks = List.of(check("fill", "#input", null, "评测任务"), check("click", "#add", null, null),
                check("text", "#value", "评测任务", null), check("text", "#empty", "", null),
                check("count", ".row", "2", null), check("select", "#filter", null, "done"),
                check("count", ".row", "1", null), check("visible", "#other", "false", null));
        var result = browser().verify(site, false, spec(checks), temporary.resolve("good-evidence"), budget());
        assertEquals(EvaluationBrowser.Status.PASS, result.load());
        assertEquals(EvaluationBrowser.Status.PASS, result.business());
        assertEquals(8, result.steps().size());
        assertTrue(result.steps().stream().allMatch(step -> step.status() == EvaluationBrowser.Status.PASS));
        assertTrue(result.evidenceComplete());
        assertNotNull(result.browserVersion());
        assertTrue(Files.size(temporary.resolve("good-evidence").resolve(result.screenshot())) > 100);
    }

    @Test void inertButtonFailsDespiteSuccessfulPageLoadAndKeepsUnexecutedChecks() throws Exception {
        Path site = page("bad", "<button id='add'>增加</button><p id='value'>0</p>");
        var result = browser().verify(site, false, spec(List.of(check("click", "#add", null, null),
                check("text", "#value", "1", null), check("text", "#value", "2", null))),
                temporary.resolve("bad-evidence"), budget());
        assertEquals(EvaluationBrowser.Status.PASS, result.load());
        assertEquals(EvaluationBrowser.Status.FAIL, result.business());
        assertEquals(EvaluationBrowser.Status.FAIL, result.steps().get(1).status());
        assertEquals("0", result.steps().get(1).observed());
        assertEquals(EvaluationBrowser.Status.SKIPPED, result.steps().get(2).status());
        assertTrue(result.evidenceComplete());
    }

    @Test void emptyExpectedTextStillRequiresAnExistingElement() throws Exception {
        var result = browser().verify(page("missing", "<p>页面正常</p>"), false,
                spec(List.of(check("text", "#missing", "", null))), temporary.resolve("missing-evidence"), budget());
        assertEquals(EvaluationBrowser.Status.FAIL, result.business());
        assertTrue(result.evidenceComplete());
    }

    @Test void numericTextMustMatchExactlyInsteadOfAcceptingSubstring() throws Exception {
        var result = browser().verify(page("wrong-count", "<p id='count'>11</p>"), false,
                spec(List.of(check("text", "#count", "1", null))), temporary.resolve("count-evidence"), budget());
        assertEquals(EvaluationBrowser.Status.FAIL, result.business());
        assertEquals("11", result.steps().getFirst().observed());
    }

    @Test void datasetChecksBusinessFieldsIndependentlyOfRowFormattingAndButtons() throws Exception {
        var data = EvaluationDataset.load(Path.of("evaluation"));
        Path inventory = page("inventory-layout", """
                <input data-testid="inventory-search" oninput="document.querySelectorAll('[data-testid=inventory-row]').forEach(r=>r.hidden=!r.innerText.includes(this.value))">
                <table><tr data-testid="inventory-row"><td data-testid="inventory-name">铅笔</td><td>库存：<span data-testid="inventory-stock">8</span></td></tr>
                <tr data-testid="inventory-row"><td data-testid="inventory-name">笔记本</td><td>库存：<span data-testid="inventory-stock">0</span></td></tr>
                <tr data-testid="inventory-row"><td data-testid="inventory-name">文件夹</td><td>库存：<span data-testid="inventory-stock">3</span></td></tr></table>
                """);
        var inventoryCase = data.cases().stream().filter(c -> c.caseId().equals("B06")).findFirst().orElseThrow();
        var inventoryResult = browser().verify(inventory, false, data.assertions(inventoryCase), temporary.resolve("inventory-evidence"), budget());
        Path tasks = page("task-actions", """
                <input data-testid="task-input"><button data-testid="add-task" onclick="document.querySelector('[data-testid=task-name]').textContent=document.querySelector('[data-testid=task-input]').value;document.querySelector('li').hidden=false">新增</button>
                <ul><li hidden data-testid="task-row" data-status="open"><input type="checkbox" data-testid="task-complete" onchange="this.parentNode.dataset.status='done'"><span data-testid="task-name"></span><button>删除</button></li></ul>
                """);
        var taskCase = data.cases().stream().filter(c -> c.caseId().equals("B05")).findFirst().orElseThrow();
        var taskResult = browser().verify(tasks, false, data.assertions(taskCase), temporary.resolve("tasks-evidence"), budget());
        assertAll(() -> assertEquals(EvaluationBrowser.Status.PASS, inventoryResult.business(), "库存字段正确时不能因整行排版判失败"),
                () -> assertEquals(EvaluationBrowser.Status.PASS, taskResult.business(), "任务名称正确时不能因行内删除按钮判失败"));
    }

    @Test void missingChromeIsEnvironmentErrorAndNeverPasses() throws Exception {
        var unavailable = new EvaluationBrowser(Duration.ofMillis(500), () -> {
            var options = new ChromeOptions();
            options.setBinary(temporary.resolve("missing-chrome").toFile());
            return new ChromeDriver(options);
        });
        var result = unavailable.verify(page("environment", "<p>页面</p>"), false,
                spec(List.of(check("text", "p", "页面", null))), temporary.resolve("env-evidence"), budget());
        assertEquals(EvaluationBrowser.Status.ERROR, result.load());
        assertEquals(EvaluationBrowser.Status.SKIPPED, result.business());
        assertNull(result.screenshot());
        assertTrue(result.diagnostics().contains("浏览器环境不可用"));
    }

    @Test void screenshotWriteFailureDoesNotEraseActualBusinessResult() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("conflict-evidence"));
        Files.createDirectory(evidence.resolve("browser.png"));
        var result = browser().verify(page("evidence", "<p>页面</p>"), false,
                spec(List.of(check("text", "p", "页面", null))), evidence, budget());
        assertEquals(EvaluationBrowser.Status.PASS, result.business());
        assertFalse(result.evidenceComplete());
        assertNull(result.screenshot());
        assertTrue(result.diagnostics().contains("浏览器截图未保存"));
    }

    @Test void scriptErrorsFailLoadInsteadOfClaimingBusinessSuccess() throws Exception {
        var result = browser().verify(page("script-error", "<p>页面</p><script>throw new Error('受控错误')</script>"), false,
                spec(List.of(check("text", "p", "页面", null))), temporary.resolve("script-evidence"), budget());
        assertEquals(EvaluationBrowser.Status.FAIL, result.load());
        assertEquals(EvaluationBrowser.Status.SKIPPED, result.business());
        assertTrue(result.evidenceComplete());
    }

    private EvaluationBrowser browser() { return new EvaluationBrowser(Duration.ofMillis(700)); }
    private EvaluationBudget budget() { return new EvaluationBudget(10, Duration.ofSeconds(40)); }
    private EvaluationDataset.Check check(String action, String selector, String expected, String value) {
        return new EvaluationDataset.Check(action, selector, expected, value);
    }
    private EvaluationDataset.Assertions spec(List<EvaluationDataset.Check> checks) {
        return new EvaluationDataset.Assertions("1", checks, null);
    }
    private Path page(String name, String body) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name));
        Files.writeString(root.resolve("index.html"), "<!doctype html><html><head><meta charset='UTF-8'></head><body>" + body + "</body></html>");
        return root;
    }
}
