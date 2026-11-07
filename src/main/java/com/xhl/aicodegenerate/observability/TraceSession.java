package com.xhl.aicodegenerate.observability;

import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.GenerationTraceStep;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 由调用方显式携带的单次追踪；所有事件在会话内排序，异步回调只更新其自身步骤。 */
public final class TraceSession {
    public enum Kind { STAGE, MODEL, TOOL, BUILD, PREVIEW, STOP }

    private static final Set<String> RESULTS = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED", "UNKNOWN");
    private static final Set<String> FINISH_REASONS = Set.of("STOP", "LENGTH", "TOOL_EXECUTION", "CONTENT_FILTER", "OTHER");
    private static final int MAX_STEPS = 10_000;
    private static final ContextKey<Step> STEP_CONTEXT = ContextKey.named("generation-trace-step");
    private final GenerationTraceRecorder recorder;
    private final Tracer tracer;
    private final Clock clock;
    private final Span rootSpan;
    private final Context context;
    private final GenerationTrace root;
    private final long startedNanos = System.nanoTime();
    private final AtomicBoolean partial = new AtomicBoolean();
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Map<String, Step> open = new HashMap<>();
    private long stepCount;
    private boolean finished;

    TraceSession(GenerationTraceRecorder recorder, Tracer tracer, Clock clock, String instanceId,
                 String kind, String requestId, String taskId, Long appId, Long userId, String mode, String type) {
        this.recorder = recorder;
        this.tracer = tracer;
        this.clock = clock;
        rootSpan = tracer.spanBuilder("generation." + kind.toLowerCase(java.util.Locale.ROOT)).setNoParent().startSpan();
        context = Context.root().with(rootSpan);
        root = GenerationTrace.builder()
                .traceId(rootSpan.getSpanContext().getTraceId()).rootSpanId(rootSpan.getSpanContext().getSpanId())
                .traceKind(kind).requestId(requestId).taskId(taskId).appId(appId).userId(userId)
                .creationLinkState("CREATE".equals(kind) ? "UNRESOLVED" : null)
                .generationMode(mode).codeGenType(type).executionStatus("RUNNING").recordingStatus("COLLECTING")
                .startedAt(clock.instant())
                .serviceInstanceId(instanceId).build();
        root.setExpiresAt(root.getStartedAt().plus(Duration.ofDays(7)));
        publish(null);
    }

    public String traceId() { return root.getTraceId(); }
    public Context context() { return context; }
    public Step currentStep() {
        Step step = Context.current().get(STEP_CONTEXT);
        return step != null && step.owner() == this ? step : null;
    }
    public boolean isPartial() { return partial.get(); }
    public void markPartial() {
        if (partial.compareAndSet(false, true)) {
            recorder.noteMissing(root.getTraceId(), root.getExpiresAt());
        }
    }

    public synchronized GenerationTrace snapshot() {
        GenerationTrace copy = root.toBuilder().build();
        if (partial.get()) {
            copy.setRecordingStatus("PARTIAL");
        }
        return copy;
    }

    public synchronized Step startStep(Kind kind, String name, Step parent) {
        if (finished || stepCount >= MAX_STEPS) {
            markPartial();
            return new Step(null, null);
        }
        if (parent != null && parent.owner() != this) {
            markPartial();
            parent = null;
        }
        String safeName = identifier(name, 128);
        if (safeName == null) {
            safeName = kind.name().toLowerCase(java.util.Locale.ROOT);
            markPartial();
        }
        Context parentContext = parent == null || parent.span == null ? context : parent.context();
        Span span = tracer.spanBuilder(safeName).setParent(parentContext).startSpan();
        int attempt = attempts.merge(kind.name() + ":" + safeName, 1, Integer::sum);
        GenerationTraceStep data = GenerationTraceStep.builder()
                .traceId(root.getTraceId()).spanId(span.getSpanContext().getSpanId())
                .parentSpanId(Span.fromContext(parentContext).getSpanContext().getSpanId())
                .stepNo(++stepCount).attemptNo(attempt).stepKind(kind.name()).stepName(safeName)
                .status("RUNNING").startedAt(clock.instant()).safeMetadata("{}").build();
        Step step = new Step(span, data);
        open.put(data.getSpanId(), step);
        if (kind == Kind.MODEL) {
            root.setModelCalls(root.getModelCalls() + 1);
        }
        publish(data);
        return step;
    }

    public synchronized void bindApplication(Long appId, String type) {
        if (appId == null || appId <= 0) {
            markPartial();
            return;
        }
        root.setAppId(appId);
        root.setCodeGenType(type);
        root.setCreationLinkState("BOUND");
        publish(null);
    }

    /** 只能在业务确认尚未尝试保存应用时调用。 */
    public synchronized void failedBeforeApplicationSave() {
        if ("CREATE".equals(root.getTraceKind()) && root.getAppId() == null) {
            root.setCreationLinkState("NO_APP");
            publish(null);
        }
    }

    public synchronized void artifact(String relative, String digest) {
        root.setArtifactRef(TraceMetadata.relativePath(relative));
        root.setArtifactDigest(sha256(digest));
        publish(null);
    }

    public synchronized void failure(Throwable error) {
        root.setFailureCategory(TraceMetadata.errorCategory(error));
        root.setSafeMessage(TraceMetadata.errorMessage(error));
        publish(null);
    }

    public synchronized void finish(String result) {
        if (finished) {
            return;
        }
        finished = true;
        root.setExecutionStatus(RESULTS.contains(result) ? result : "UNKNOWN");
        root.setFinishedAt(clock.instant());
        if (!"UNKNOWN".equals(root.getExecutionStatus())) {
            root.setDurationMs(elapsed(startedNanos));
        }
        if (!open.isEmpty()) {
            markPartial();
            // 中断缺少终态证据：结束 SDK 资源，不补造业务步骤的结束时间及耗时。
            open.values().forEach(step -> {
                step.ended = true;
                step.data.setStatus("UNKNOWN");
                step.span.end();
                step.changed();
            });
            open.clear();
        }
        root.setRecordingStatus(partial.get() ? "PARTIAL" : "COMPLETE");
        rootSpan.end();
        publish(null);
    }

    private void publish(GenerationTraceStep step) {
        root.setRevision(root.getRevision() + 1);
        root.setLastPersistedSequence(root.getRevision());
        recorder.record(this, snapshot(), step == null ? null : step.toBuilder().build());
    }

    private static long elapsed(long start) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - start).toMillis());
    }

    private static String identifier(String value, int maximum) {
        return value != null && value.length() <= maximum && value.matches("[a-zA-Z0-9_./-]+") ? value : null;
    }

    private static String sha256(String value) {
        return value != null && value.matches("[a-f0-9]{64}") ? value : null;
    }

    public final class Step {
        private final Span span;
        private final GenerationTraceStep data;
        private final long beganNanos = System.nanoTime();
        private boolean ended;

        private Step(Span span, GenerationTraceStep data) {
            this.span = span;
            this.data = data;
        }

        private TraceSession owner() { return TraceSession.this; }
        public Context context() { return span == null ? TraceSession.this.context : TraceSession.this.context.with(span).with(STEP_CONTEXT, this); }
        public String spanId() { return data == null ? null : data.getSpanId(); }

        public void metadata(Map<String, ?> values) {
            synchronized (TraceSession.this) {
                if (data == null) {
                    return;
                }
                data.setSafeMetadata(TraceMetadata.json(values));
                changed();
            }
        }

        public void model(String alias, String name, String promptDigest, String configDigest) {
            synchronized (TraceSession.this) {
                if (data == null) {
                    return;
                }
                data.setProviderAlias(identifier(alias, 128));
                data.setModelName(identifier(name, 256));
                data.setPromptVersion(sha256(promptDigest));
                data.setConfigDigest(sha256(configDigest));
                changed();
            }
        }

        public void succeed() { complete(null, null, null); }

        public void skip() {
            synchronized (TraceSession.this) {
                if (data != null && !ended) end("SKIPPED", null);
            }
        }

        public void actualModel(String name) {
            synchronized (TraceSession.this) {
                String safeName = identifier(name, 256);
                if (data != null && safeName != null) {
                    data.setModelName(safeName);
                    changed();
                }
            }
        }

        public void complete(Long input, Long output, String finishReason) {
            synchronized (TraceSession.this) {
                if (data == null) {
                    return;
                }
                if (Kind.MODEL.name().equals(data.getStepKind())) {
                    if (data.getInputTokens() == null && input != null && input >= 0) {
                        data.setInputTokens(input);
                        root.setInputReportedCalls(root.getInputReportedCalls() + 1);
                        root.setKnownInputTokens(sum(root.getKnownInputTokens(), input));
                    }
                    if (data.getOutputTokens() == null && output != null && output >= 0) {
                        data.setOutputTokens(output);
                        root.setOutputReportedCalls(root.getOutputReportedCalls() + 1);
                        root.setKnownOutputTokens(sum(root.getKnownOutputTokens(), output));
                    }
                    if (data.getFinishReason() == null && finishReason != null && FINISH_REASONS.contains(finishReason)) {
                        data.setFinishReason(finishReason);
                    }
                }
                end("SUCCEEDED", null);
            }
        }

        public void fail(Throwable error) {
            synchronized (TraceSession.this) {
                if (data != null && !ended) {
                    end("CANCELLED".equals(TraceMetadata.errorCategory(error)) ? "CANCELLED" : "FAILED", error);
                }
            }
        }

        public void cancel() {
            synchronized (TraceSession.this) {
                if (data != null && !ended) {
                    end("CANCELLED", new java.util.concurrent.CancellationException());
                }
            }
        }

        private void end(String status, Throwable error) {
            if (!ended) {
                ended = true;
                data.setStatus(status);
                data.setFinishedAt(clock.instant());
                data.setDurationMs(elapsed(beganNanos));
                open.remove(data.getSpanId());
                if (error != null) {
                    data.setErrorCategory(TraceMetadata.errorCategory(error));
                    data.setSafeMessage(TraceMetadata.errorMessage(error));
                    span.setStatus(StatusCode.ERROR, data.getErrorCategory());
                }
                span.end();
            }
            changed();
        }

        private void changed() {
            data.setRevision(data.getRevision() + 1);
            publish(data);
        }

        private Long sum(Long known, Long value) {
            try {
                return Math.addExact(known == null ? 0 : known, value);
            } catch (ArithmeticException error) {
                markPartial();
                return known;
            }
        }
    }
}
