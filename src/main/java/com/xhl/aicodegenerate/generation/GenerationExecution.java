package com.xhl.aicodegenerate.generation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.Map;
import dev.langchain4j.model.output.TokenUsage;

/** 本轮执行的截止时间、文件边界和取消资源，使用任务标识跨异步线程传递。 */
public final class GenerationExecution implements AutoCloseable {
    private static final ConcurrentMap<String, GenerationExecution> ACTIVE = new ConcurrentHashMap<>();
    private final String id;
    private final Path root;
    private final long deadline;
    private final BooleanSupplier owner;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();
    private final Set<Runnable> cancellations = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Process, Set<ProcessHandle>> processes = new ConcurrentHashMap<>();
    private volatile String reason = "任务已停止";
    private Consumer<String> output = text -> { };
    private Consumer<Map<String, Object>> progress = event -> { };
    private int modelCalls;
    private int usageCalls;
    private Long inputTokens;
    private Long outputTokens;

    public void observe(Consumer<String> output, Consumer<Map<String, Object>> progress) { this.output = output; this.progress = progress; }
    public void output(String value) { checkActive(); output.accept(value); }
    public void progress(Map<String, Object> event) { checkActive(); progress.accept(event); }
    public synchronized void modelStarted() { checkActive(); modelCalls++; }
    public synchronized void usage(TokenUsage usage) {
        if (usage == null) return;
        usageCalls++;
        if (usage.inputTokenCount() != null) inputTokens = (inputTokens == null ? 0L : inputTokens) + usage.inputTokenCount();
        if (usage.outputTokenCount() != null) outputTokens = (outputTokens == null ? 0L : outputTokens) + usage.outputTokenCount();
    }
    public synchronized void copyUsage(GenerationTask task) {
        task.setModelCalls(modelCalls); task.setUsageReportedCalls(usageCalls);
        task.setInputTokens(inputTokens); task.setOutputTokens(outputTokens);
    }

    public GenerationExecution(String id, Path root, Duration budget, BooleanSupplier owner) {
        this.id = id;
        this.root = root.toAbsolutePath().normalize();
        this.deadline = System.nanoTime() + budget.toNanos();
        this.owner = owner;
        if (ACTIVE.putIfAbsent(id, this) != null) throw new IllegalStateException("任务已在执行");
    }

    public static GenerationExecution require(String id) {
        GenerationExecution run = ACTIVE.get(id);
        if (run == null) throw new CancellationException("任务执行上下文已失效");
        return run;
    }

    public static GenerationExecution find(String id) { return id == null ? null : ACTIVE.get(id); }
    public Path root() { return root; }
    public String id() { return id; }
    public Duration remaining() {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new BudgetExceeded();
        return Duration.ofNanos(nanos);
    }

    public void checkActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException(reason);
        remaining();
        if (!owner.getAsBoolean()) {
            cancel("任务执行权已失效");
            throw new CancellationException(reason);
        }
    }

    public <T> T files(Callable<T> operation) throws Exception {
        fileLock.readLock().lockInterruptibly();
        try { checkActive(); return operation.call(); }
        finally { fileLock.readLock().unlock(); }
    }

    public Runnable onCancel(Runnable cancellation) {
        cancellations.add(cancellation);
        if (cancelled.get()) cancellation.run();
        return () -> cancellations.remove(cancellation);
    }

    public <T> CompletableFuture<T> fork(java.util.function.Supplier<T> action) {
        checkActive();
        var result = new CompletableFuture<T>();
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try { checkActive(); result.complete(action.get()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        Runnable registration = onCancel(() -> {
            worker.interrupt();
            try { worker.join(5000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            if (worker.isAlive()) throw new IllegalStateException("素材任务尚未结束");
        });
        result.whenComplete((value, error) -> registration.run());
        worker.start();
        return result;
    }

    public void track(Process process) {
        Set<ProcessHandle> children = ConcurrentHashMap.newKeySet();
        processes.put(process, children);
        Thread.ofVirtual().start(() -> {
            while (processes.containsKey(process) && process.isAlive()) {
                process.descendants().forEach(children::add);
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
        });
        if (cancelled.get()) untrack(process);
    }
    /** 即使 npm 父进程先退出，也要回收已观察到的后台子进程。 */
    public void untrack(Process process) {
        Set<ProcessHandle> children = processes.get(process);
        terminate(process, children == null ? Set.of() : children);
        processes.remove(process);
    }

    public void cancel(String message) {
        reason = message;
        cancelled.set(true);
        RuntimeException failure = null;
        for (Runnable action : cancellations) {
            try { action.run(); } catch (RuntimeException error) { failure = error; }
        }
        for (Process process : processes.keySet()) {
            try { untrack(process); } catch (RuntimeException error) { failure = error; }
        }
        if (failure != null) throw new IllegalStateException("后台资源尚未清理完成", failure);
    }

    /** 先结束后代，再结束直接子进程，等待回收后才允许任务释放应用。 */
    public static void terminate(Process process) {
        terminate(process, Set.of());
    }
    private static void terminate(Process process, Set<ProcessHandle> observed) {
        Set<ProcessHandle> descendants = new java.util.HashSet<>(observed);
        process.descendants().forEach(descendants::add);
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
        boolean interrupted = Thread.interrupted();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
            for (ProcessHandle child : descendants) {
                try { child.onExit().get(5, TimeUnit.SECONDS); }
                catch (ExecutionException | TimeoutException ignored) { }
            }
        } catch (InterruptedException e) { interrupted = true; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
            throw new IllegalStateException("构建进程尚未停止");
    }

    @Override public void close() {
        cancel(reason);
        // 等待已经进入的文件操作退出，迟到工具调用会在 checkActive 被拒绝。
        fileLock.writeLock().lock();
        try { ACTIVE.remove(id, this); }
        finally { fileLock.writeLock().unlock(); }
    }

    public static final class BudgetExceeded extends RuntimeException {
        public BudgetExceeded() { super("已达到生成任务总时限"); }
    }
}
