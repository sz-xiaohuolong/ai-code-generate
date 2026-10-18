package com.xhl.aicodegenerate.generation;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.model.enums.GenerationModeEnum;
import com.xhl.aicodegenerate.service.AppService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 页面订阅不拥有后台任务；应用租约一直保持到资源清理结束。 */
@lombok.extern.slf4j.Slf4j
@Service
public class GenerationTaskService implements AutoCloseable {
    private final RedisTaskStore store;
    private final GenerationWorkspace workspace;
    private final AppService apps;
    private final GenerationTaskRunner runner;
    private final Duration budget;
    private final ConcurrentMap<String, Running> running = new ConcurrentHashMap<>();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("generation-monitor").factory());
    private volatile boolean shutdown;
    private final ScheduledExecutorService historyMonitor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("generation-history").factory());

    @Autowired public GenerationTaskService(RedisTaskStore store, GenerationWorkspace workspace, AppService apps,
            GenerationTaskRunner runner, @Value("${generation.task-timeout-minutes:20}") int minutes) {
        this(store, workspace, apps, runner, Duration.ofMinutes(minutes));
    }
    GenerationTaskService(RedisTaskStore store, GenerationWorkspace workspace, AppService apps,
            GenerationTaskRunner runner, Duration budget) {
        this.store = store; this.workspace = workspace; this.apps = apps; this.runner = runner; this.budget = budget;
        monitor.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
        historyMonitor.scheduleWithFixedDelay(this::reconcileHistory, 1, 1, TimeUnit.SECONDS);
    }

    public GenerationTask start(Long appId, String message, String clientRequestId, User user) {
        App app = ownedApp(appId, user);
        if (message == null || message.isBlank() || message.length() > 20_000)
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请输入不超过 20000 字符的生成需求");
        if (clientRequestId == null || !clientRequestId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请提供稳定的 clientRequestId，以便安全重试");
        if (shutdown) throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成服务正在停止");
        GenerationTask task = new GenerationTask();
        task.setId(UUID.randomUUID().toString()); task.setAppId(appId); task.setUserId(user.getId());
        task.setPrompt(message.trim()); task.setClientRequestId(clientRequestId.toLowerCase(Locale.ROOT));
        task.setCodeGenType(app.getCodeGenType()); task.setGenerationMode(GenerationModeEnum.resolve(app.getGenerationMode()).getValue());
        task.setInputHash(DigestUtil.sha256Hex(appId + "\n" + task.getGenerationMode() + "\n" + task.getCodeGenType() + "\n" + task.getPrompt()));
        task.setCreatedAt(System.currentTimeMillis()); task.setUpdatedAt(task.getCreatedAt()); task.setDeadlineAt(task.getCreatedAt() + budget.toMillis());
        task.setPreviewAvailable(workspace.hasPreview(appId, app.getCodeGenType()));
        String claimed = store.claim(task);
        if (!claimed.equals(task.getId())) {
            GenerationTask existing = get(claimed, user);
            if (!task.getInputHash().equals(existing.getInputHash()))
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "相同 clientRequestId 不能用于不同的应用或输入");
            return existing;
        }
        GenerationExecution execution = new GenerationExecution(task.getId(), workspace.taskRoot(appId, task.getId()), budget,
                () -> store.owns(appId, task.getId()) && !store.stopRequested(task.getId()));
        Running active = new Running(task, execution);
        execution.observe(value -> append(active, value), event -> progress(active, event));
        Thread worker = Thread.ofVirtual().name("generation-task-" + task.getId()).unstarted(() -> execute(active));
        active.worker = worker; running.put(task.getId(), active); worker.start();
        return copy(task);
    }

    public GenerationTask get(String id, User user) {
        GenerationTask task = store.load(id);
        if (task == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "任务不存在或已超过保留期");
        ownedApp(task.getAppId(), user);
        if (!running.containsKey(id)) task = store.recoverInterrupted(task);
        if (task == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "任务记录不存在");
        if (!task.getStatus().terminal() && store.stopRequested(id)) {
            task.setStatus(TaskStatus.STOPPING); task.setStage("正在停止"); task.setMessage("正在停止模型、文件操作和构建进程");
        }
        return task;
    }
    public GenerationTask current(Long appId, User user) {
        ownedApp(appId, user); String id = store.latest(appId); return id == null ? null : get(id, user);
    }
    public boolean previewAvailable(Long appId, User user) {
        App app = ownedApp(appId, user); return workspace.hasPreview(appId, app.getCodeGenType());
    }
    public GenerationTask stop(String id, User user) {
        GenerationTask task = get(id, user);
        if (!task.getStatus().terminal()) {
            store.requestStop(id);
            Running active = running.get(id);
            if (active != null) Thread.ofVirtual().start(() -> cancel(active, "用户主动停止"));
        }
        return get(id, user);
    }
    public Flux<GenerationTask> events(String id, User user) {
        get(id, user);
        return Flux.interval(Duration.ZERO, Duration.ofMillis(500))
                .map(tick -> get(id, user))
                .distinctUntilChanged(t -> t.getRevision() + ":" + t.getStatus())
                .takeUntil(task -> task.getStatus().terminal());
    }

    private App ownedApp(Long appId, User user) {
        if (user == null || user.getId() == null) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        if (appId == null || appId <= 0) throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        App app = apps.getById(appId);
        if (app == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!user.getId().equals(app.getUserId())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "仅应用所有者可以操作生成任务");
        return app;
    }

    private void execute(Running active) {
        GenerationTask task = active.task;
        TaskStatus finalStatus = TaskStatus.SUCCEEDED;
        String resultMessage = "生成与交付检查已完成";
        try {
            active.execution.checkActive(); workspace.prepare(task); active.execution.checkActive();
            for (String id : store.pendingHistory()) {
                GenerationTask previous = store.historyTask(id);
                if (previous != null && Objects.equals(previous.getAppId(), task.getAppId())) {
                    recordHistory(id);
                    if (store.historyTask(id) != null) throw new BusinessException(ErrorCode.OPERATION_ERROR, "上一轮正式历史尚未恢复，请稍后重试");
                }
            }
            active.execution.checkActive();
            synchronized (task) { task.setStatus(TaskStatus.RUNNING); task.setStage("代码生成"); task.setMessage("正在生成代码"); changed(active); }
            runner.run(task, active.execution, value -> append(active, value), progress -> progress(active, progress));
            active.execution.checkActive();
        } catch (Throwable error) {
            // 模型包装异常可能保留中断标记；先清除，才能读取已持久化的停止意图。
            Thread.interrupted();
            boolean timedOut = System.currentTimeMillis() >= task.getDeadlineAt() || error instanceof GenerationExecution.BudgetExceeded;
            boolean stopped = false;
            try { stopped = store.stopRequested(task.getId()); } catch (RuntimeException ignored) { }
            finalStatus = timedOut ? TaskStatus.TIMED_OUT : stopped ? TaskStatus.CANCELLED : shutdown ? TaskStatus.INTERRUPTED : TaskStatus.FAILED;
            resultMessage = timedOut ? "已达到生成任务总时限" : stopped ? "生成已停止，上次成功版本已保留" : safeMessage(error, active.execution);
        } finally {
            Thread.interrupted();
            boolean cleaned = false;
            while (!cleaned) {
                try { active.execution.close(); cleaned = true; }
                catch (RuntimeException error) {
                    synchronized (task) { task.setStatus(TaskStatus.STOPPING); task.setStage("正在停止");
                        task.setMessage("正在等待后台资源清理完成"); changed(active); }
                    try { store.save(copy(task)); } catch (RuntimeException ignored) { }
                    java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
                    Thread.interrupted();
                }
            }
            active.committing = true;
            synchronized (task) {
                active.execution.copyUsage(task);
                task.setStatus(finalStatus); task.setStage(finalStatus == TaskStatus.SUCCEEDED ? "已完成" : "已结束");
                task.setMessage(resultMessage); changed(active);
                if (finalStatus == TaskStatus.SUCCEEDED) task.setPreviewAvailable(true);
            }
            try {
                boolean committed = store.finish(task, false);
                if (!committed && finalStatus == TaskStatus.SUCCEEDED && store.stopRequested(task.getId())) {
                    synchronized (task) { task.setStatus(TaskStatus.CANCELLED); task.setMessage("生成已停止，上次成功版本已保留");
                        task.setPreviewAvailable(workspace.hasPreview(task.getAppId(), task.getCodeGenType())); changed(active); }
                    committed = store.finish(task, false);
                }
                if (committed) {
                    store.release(task);
                    if (task.getStatus() == TaskStatus.SUCCEEDED) {
                        try { runner.afterPublish(copy(task)); }
                        catch (RuntimeException error) { log.warn("任务 {} 发布后的封面更新失败：{}", task.getId(), error.getMessage()); }
                    }
                    historyMonitor.execute(() -> recordHistory(task.getId()));
                }
            } finally { running.remove(task.getId(), active); }
        }
    }

    private void append(Running active, String value) {
        active.execution.checkActive();
        if (value == null || value.isEmpty()) return;
        if (JSONUtil.isTypeJSONObject(value)) {
            var json = JSONUtil.parseObj(value);
            if ("workflow_progress".equals(json.getStr("type"))) { progress(active, json); return; }
            if ("ai_response".equals(json.getStr("type"))) value = json.getStr("data", "");
        }
        synchronized (active.task) {
            if (active.task.getOutput().length() + value.length() > 1_000_000)
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成输出超过任务安全上限");
            active.task.setOutput(active.task.getOutput() + value); changed(active);
        }
    }
    private void progress(Running active, Map<String, Object> event) {
        synchronized (active.task) {
            String id = String.valueOf(event.get("id"));
            var steps = new ArrayList<>(active.task.getSteps());
            steps.removeIf(step -> Objects.equals(step.get("id"), id)); steps.add(new LinkedHashMap<>(event));
            active.task.setSteps(steps); active.task.setStage(String.valueOf(event.getOrDefault("name", "生成中")));
            active.task.setMessage(String.valueOf(event.getOrDefault("message", "正在执行")));
            if ("code_generator".equals(id) && "running".equals(event.get("status"))) active.task.setOutput("");
            if ("vue_repair".equals(id) && event.get("retryCount") instanceof Number count) active.task.setRepairCount(count.intValue());
            changed(active);
        }
    }
    private void changed(Running active) {
        active.task.setUpdatedAt(System.currentTimeMillis()); active.task.setRevision(active.task.getRevision() + 1); active.dirty.set(true);
    }
    private GenerationTask copy(GenerationTask task) { synchronized (task) { return JSONUtil.toBean(JSONUtil.toJsonStr(task), GenerationTask.class); } }
    private void tick() {
        for (Running active : running.values()) {
            try {
                if (System.nanoTime() - active.lastRenew > Duration.ofSeconds(5).toNanos()) {
                    if (!store.renew(active.task.getAppId(), active.task.getId())) { cancel(active, "任务执行权已失效"); continue; }
                    active.lastRenew = System.nanoTime();
                }
                if (System.currentTimeMillis() >= active.task.getDeadlineAt()) { cancel(active, "已达到任务总时限"); continue; }
                if (store.stopRequested(active.task.getId())) { cancel(active, "用户主动停止"); continue; }
                if (!active.committing && active.dirty.compareAndSet(true, false) && !store.save(copy(active.task))) cancel(active, "任务执行权已失效");
            } catch (RuntimeException error) { cancel(active, "任务状态存储不可用"); }
        }
    }
    private void reconcileHistory() {
        try { store.pendingHistory().stream().limit(20).forEach(this::recordHistory); }
        catch (RuntimeException error) { log.warn("待记历史扫描暂不可用：{}", error.getMessage()); }
    }
    private void recordHistory(String id) {
        String owner = UUID.randomUUID().toString();
        if (!store.claimHistory(id, owner)) return;
        boolean saved = false;
        try {
            GenerationTask pending = store.historyTask(id);
            if (pending != null) runner.recordResult(pending);
            saved = true;
        } catch (RuntimeException error) { log.warn("任务 {} 的正式历史待补记：{}", id, error.getMessage()); }
        finally { store.finishHistory(id, owner, saved); }
    }
    private void cancel(Running active, String reason) {
        if (active.cancelDispatched.compareAndSet(false, true)) {
            active.worker.interrupt();
            Thread.ofVirtual().start(() -> { try { active.execution.cancel(reason); } catch (RuntimeException ignored) { } });
        }
    }
    private String safeMessage(Throwable error, GenerationExecution execution) {
        String message = error.getMessage();
        return (message == null ? "生成任务失败，请查看诊断后重试" : message).replace(execution.root().toString(), "[项目]");
    }
    @PreDestroy @Override public void close() {
        shutdown = true; running.values().forEach(active -> cancel(active, "服务已停止")); monitor.shutdownNow(); historyMonitor.shutdown();
    }
    private static final class Running {
        final GenerationTask task; final GenerationExecution execution; final AtomicBoolean dirty = new AtomicBoolean(true);
        final AtomicBoolean cancelDispatched = new AtomicBoolean();
        volatile boolean committing;
        volatile Thread worker; volatile long lastRenew = System.nanoTime();
        Running(GenerationTask task, GenerationExecution execution) { this.task = task; this.execution = execution; }
    }
}
