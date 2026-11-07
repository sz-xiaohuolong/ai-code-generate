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
import com.xhl.aicodegenerate.observability.TraceSession;
import io.opentelemetry.context.Context;

/** 本轮执行的截止时间、文件边界和取消资源，使用任务标识跨异步线程传递。 */
public final class GenerationExecution implements AutoCloseable {
    private static final ConcurrentMap<String, GenerationExecution> ACTIVE = new ConcurrentHashMap<>();
    private final String id;
    private final Path root;
    private final long deadline;
    private final BooleanSupplier owner;
    private final String initialPrompt;
    private GenerationRequirements requirements;
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
    private TraceSession trace;
    private boolean knowledgeRequired;
    private com.xhl.aicodegenerate.knowledge.KnowledgeContext knowledge;

    public synchronized void requireKnowledge() {
        if (knowledge != null) throw new IllegalStateException("知识上下文已固定");
        knowledgeRequired = true;
    }

    public synchronized void attachKnowledge(com.xhl.aicodegenerate.knowledge.KnowledgeContext context) {
        checkActive();
        if (!knowledgeRequired || knowledge != null) throw new IllegalStateException("知识上下文不可覆盖或越权启用");
        knowledge = java.util.Objects.requireNonNull(context);
    }

    public synchronized com.xhl.aicodegenerate.knowledge.KnowledgeContext knowledge() { return knowledge; }

    public synchronized void assertGenerationAllowed() {
        checkActive();
        if (knowledgeRequired && knowledge == null) throw new com.xhl.aicodegenerate.knowledge.KnowledgeException(
                "KNOWLEDGE_REQUIRED", "本轮尚未完成有效检索，禁止生成或写入产物");
    }

    public void observe(Consumer<String> output, Consumer<Map<String, Object>> progress) { this.output = output; this.progress = progress; }
    public void output(String value) { checkActive(); output.accept(value); }
    public void progress(Map<String, Object> event) { checkActive(); progress.accept(event); }
    public synchronized void modelStarted() { checkActive(); modelCalls++; }
    public synchronized void attachTrace(TraceSession trace) {
        if (this.trace != null) throw new IllegalStateException("任务追踪已绑定");
        this.trace = java.util.Objects.requireNonNull(trace);
    }
    public TraceSession trace() { return trace; }
    public TraceSession.Step traceStep(TraceSession.Kind kind, String name) {
        return trace == null ? null : trace.startStep(kind, name, trace.currentStep());
    }
    public Context traceContext() {
        if (trace == null) return Context.current();
        TraceSession.Step step = trace.currentStep();
        return step == null ? trace.context() : step.context();
    }

    public void traceAction(String name, Runnable action) {
        traceCall(TraceSession.Kind.STAGE, name, () -> { action.run(); return null; });
    }

    public <T> T traceCall(TraceSession.Kind kind, String name, java.util.function.Supplier<T> action) {
        TraceSession.Step step = traceStep(kind, name);
        if (step != null && kind == TraceSession.Kind.TOOL) step.metadata(Map.of("toolName", name));
        try (var ignored = (step == null ? traceContext() : step.context()).makeCurrent()) {
            T result = action.get();
            if (step != null) step.succeed();
            return result;
        } catch (RuntimeException error) {
            if (step != null) step.fail(error);
            throw error;
        }
    }

    public BuildResult traceBuild(TraceSession.Kind kind, String name, java.util.function.Supplier<BuildResult> action) {
        TraceSession.Step step = traceStep(kind, name);
        try (var ignored = (step == null ? traceContext() : step.context()).makeCurrent()) {
            BuildResult result = action.get();
            if (step != null) {
                step.metadata(Map.of("buildKind", result.kind().name()));
                if (result.success()) step.succeed();
                else if (result.kind() == BuildResult.Kind.CANCELLED) step.cancel();
                else step.fail(new IllegalStateException("交付检查未通过"));
            }
            return result;
        } catch (RuntimeException error) {
            if (step != null) step.fail(error);
            throw error;
        }
    }
    public ModelUsage newModelUsage() { return new ModelUsage(); }

    /** 一次调用的分项用量只累加一次；迟到响应仅补充此前未知的分项。 */
    public final class ModelUsage {
        private boolean reported;
        private boolean inputKnown;
        private boolean outputKnown;
        public void record(TokenUsage usage) {
            if (usage == null) return;
            synchronized (GenerationExecution.this) {
                if (!inputKnown && usage.inputTokenCount() != null && usage.inputTokenCount() >= 0) {
                    inputTokens = (inputTokens == null ? 0L : inputTokens) + usage.inputTokenCount();
                    inputKnown = true;
                }
                if (!outputKnown && usage.outputTokenCount() != null && usage.outputTokenCount() >= 0) {
                    outputTokens = (outputTokens == null ? 0L : outputTokens) + usage.outputTokenCount();
                    outputKnown = true;
                }
                if (!reported && (inputKnown || outputKnown)) {
                    usageCalls++;
                    reported = true;
                }
            }
        }
    }
    public synchronized void copyUsage(GenerationTask task) {
        task.setModelCalls(modelCalls); task.setUsageReportedCalls(usageCalls);
        task.setInputTokens(inputTokens); task.setOutputTokens(outputTokens);
    }

    public GenerationExecution(String id, Path root, Duration budget, BooleanSupplier owner) {
        this(id, root, budget, owner, null);
    }

    public GenerationExecution(String id, Path root, Duration budget, BooleanSupplier owner, String initialPrompt) {
        this.id = id;
        this.root = root.toAbsolutePath().normalize();
        this.deadline = System.nanoTime() + budget.toNanos();
        this.owner = owner;
        this.initialPrompt = initialPrompt;
        if (ACTIVE.putIfAbsent(id, this) != null) throw new IllegalStateException("任务已在执行");
    }

    /** 在模型写入临时记忆前捕获一次，避免窗口淘汰及错误输出改变修复目标。 */
    public synchronized void captureRequirements(java.util.List<dev.langchain4j.data.message.ChatMessage> history, String currentPrompt) {
        checkActive();
        if (requirements != null) throw new IllegalStateException("任务需求已固定");
        requirements = GenerationRequirements.capture(initialPrompt, history, currentPrompt);
    }

    public synchronized GenerationRequirements requirements() {
        if (requirements == null) throw new com.xhl.aicodegenerate.exception.BusinessException(
                com.xhl.aicodegenerate.exception.ErrorCode.OPERATION_ERROR, "任务需求上下文缺失，已停止自动修复");
        return requirements;
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
        try { assertGenerationAllowed(); return operation.call(); }
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
        Context captured = traceContext();
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try (var ignored = captured.makeCurrent()) { checkActive(); result.complete(action.get()); }
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
