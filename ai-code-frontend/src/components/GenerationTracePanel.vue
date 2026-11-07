<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { CloseOutlined, ReloadOutlined, RightOutlined } from '@ant-design/icons-vue'
import { getCreationTrace, getGenerationTrace, getTaskTrace, listGenerationTraces, listTraceSteps } from '@/api/generationTrace'
import {
  formatTraceUsage, mergeTraceSteps, traceDate, traceDuration, traceErrorText, traceMetadata,
  traceNumber, traceStatus, traceStepKind, traceStepName, traceStepRows,
  type TraceRoot, type TraceStep, type TraceView,
} from '@/utils/generationTrace'

const props = defineProps<{ appId?: string; taskId?: string; taskActive?: boolean; requestId?: string; embedded?: boolean }>()
defineEmits<{ close: [] }>()
type Locator = { kind: 'trace' | 'task' | 'request'; id: string }
const records = ref<TraceRoot[]>([])
const nextHistory = ref<string | null>(null)
const historyBusy = ref(false)
const historyError = ref('')
const view = ref<TraceView | null>(null)
const steps = ref<TraceStep[]>([])
const nextSteps = ref<string | null>(null)
const detailBusy = ref(false)
const detailError = ref('')
const selected = ref<Locator | null>(null)
const root = computed(() => view.value?.trace)
const rows = computed(() => traceStepRows(steps.value, root.value?.rootSpanId ?? ''))
const currentSelected = computed(() => selected.value?.kind === 'task' && selected.value.id === props.taskId)
let historyController: AbortController | undefined
let detailController: AbortController | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let loadedCursors = ['0']
let disposed = false

function stopTimer() { if (timer) clearTimeout(timer); timer = undefined }
function clearDetail() { view.value = null; steps.value = []; nextSteps.value = null; loadedCursors = ['0']; detailBusy.value = false }
function scheduleRefresh() {
  stopTimer()
  const running = root.value?.executionStatus === 'RUNNING' || view.value?.recordingStatus === 'COLLECTING'
  // 仅打开面板时刷新；任务刚创建但异步记录尚未入库时允许继续等待。
  if (!disposed && (running || (currentSelected.value && props.taskActive))) {
    timer = setTimeout(() => { void refreshDetail() }, 3000)
  }
}
async function loadHistory(more = false) {
  if (!props.appId || historyBusy.value || (more && !nextHistory.value)) return
  historyController?.abort()
  const controller = new AbortController()
  historyController = controller
  historyBusy.value = true
  historyError.value = ''
  try {
    const page = await listGenerationTraces(props.appId, controller.signal, more ? nextHistory.value! : undefined)
    if (controller.signal.aborted) return
    const combined = new Map((more ? records.value : []).map(record => [record.traceId, record]))
    for (const record of page.records) combined.set(record.traceId, record)
    records.value = [...combined.values()]
    nextHistory.value = page.nextCursor
    if (!selected.value && records.value[0]) select({ kind: 'trace', id: records.value[0].traceId })
  } catch (error) {
    if (!controller.signal.aborted) {
      historyError.value = traceErrorText(error)
      if (isAccessLoss(error)) { records.value = []; clearDetail(); selected.value = null; detailController?.abort(); stopTimer() }
    }
  } finally { if (historyController === controller) historyBusy.value = false }
}
function isAccessLoss(error: unknown) {
  return typeof error === 'object' && error !== null && 'code' in error && [40100, 40101, 40400].includes(Number(error.code))
}
function select(locator: Locator) {
  stopTimer()
  detailController?.abort()
  selected.value = locator
  clearDetail()
  detailError.value = ''
  void refreshDetail()
}
async function refreshDetail() {
  const locator = selected.value
  if (!locator) return
  stopTimer()
  detailController?.abort()
  const controller = new AbortController()
  detailController = controller
  detailBusy.value = true
  detailError.value = ''
  try {
    const read = locator.kind === 'task' ? getTaskTrace : locator.kind === 'request' ? getCreationTrace : getGenerationTrace
    const result = await read(locator.id, controller.signal)
    if (controller.signal.aborted) return
    view.value = result
    if (!result.trace) { steps.value = []; nextSteps.value = null; return }
    const found = records.value.findIndex(record => record.traceId === result.trace!.traceId)
    if (found >= 0) records.value[found] = result.trace
    for (const cursor of loadedCursors) {
      const page = await listTraceSteps(result.trace.traceId, controller.signal, cursor)
      if (controller.signal.aborted) return
      steps.value = mergeTraceSteps(steps.value, page.records)
      nextSteps.value = page.nextCursor
    }
  } catch (error) {
    if (!controller.signal.aborted) {
      detailError.value = traceErrorText(error)
      if (isAccessLoss(error)) clearDetail()
    }
  } finally {
    if (detailController === controller && !controller.signal.aborted) { detailBusy.value = false; scheduleRefresh() }
  }
}
async function loadMoreSteps() {
  if (!root.value || !nextSteps.value || detailBusy.value) return
  stopTimer()
  const controller = new AbortController()
  detailController?.abort()
  detailController = controller
  const cursor = nextSteps.value
  detailBusy.value = true
  detailError.value = ''
  try {
    const page = await listTraceSteps(root.value.traceId, controller.signal, cursor)
    if (controller.signal.aborted) return
    steps.value = mergeTraceSteps(steps.value, page.records)
    loadedCursors.push(cursor)
    nextSteps.value = page.nextCursor
  } catch (error) {
    if (!controller.signal.aborted) {
      detailError.value = traceErrorText(error)
      if (isAccessLoss(error)) clearDetail()
    }
  } finally {
    if (detailController === controller && !controller.signal.aborted) { detailBusy.value = false; scheduleRefresh() }
  }
}
watch(() => [props.appId, props.taskId, props.requestId], () => {
  historyController?.abort()
  detailController?.abort()
  stopTimer()
  records.value = []
  nextHistory.value = null
  historyBusy.value = false
  historyError.value = ''
  detailError.value = ''
  clearDetail()
  selected.value = null
  if (props.requestId) select({ kind: 'request', id: props.requestId })
  else if (props.taskId) select({ kind: 'task', id: props.taskId })
  if (props.appId) void loadHistory()
}, { immediate: true })
watch(() => props.taskActive, () => { if (currentSelected.value) void refreshDetail() })
onBeforeUnmount(() => { disposed = true; stopTimer(); historyController?.abort(); detailController?.abort() })
</script>

<template>
  <section class="trace-panel" aria-label="生成追踪记录">
    <header v-if="!embedded" class="trace-header">
      <div><h2>{{ requestId ? '创建失败诊断' : '生成记录' }}</h2><p>保留 7 天 · 仅所有者可见 · 记录只用于查询</p></div>
      <button class="trace-button icon-button" type="button" aria-label="关闭生成记录" @click="$emit('close')"><CloseOutlined /></button>
    </header>
    <p v-if="embedded" class="trace-secondary">记录保留 7 天，仅所有者可查询。</p>
    <div class="trace-layout" :class="{ 'with-history': appId }">
      <nav v-if="appId" class="trace-history" aria-label="应用追踪历史">
        <div class="trace-toolbar"><strong>近 7 天记录</strong><button class="trace-button" type="button" :disabled="historyBusy" @click="loadHistory()">刷新列表</button></div>
        <button v-if="taskId" class="trace-button current-button" type="button" :aria-pressed="currentSelected" @click="select({ kind: 'task', id: taskId })">查看本次过程</button>
        <p v-if="historyBusy" role="status">正在加载记录…</p>
        <p v-if="historyError" role="alert" class="trace-notice">{{ historyError }}</p>
        <p v-else-if="!historyBusy && !records.length" class="trace-empty">尚无可查询记录。启用追踪前的历史不会补录。</p>
        <ol class="trace-records">
          <li v-for="record in records" :key="record.traceId">
            <button class="trace-record" type="button" :aria-pressed="root?.traceId === record.traceId" @click="select({ kind: 'trace', id: record.traceId })">
              <span>{{ record.traceKind === 'CREATE' ? '创建路由' : '生成任务' }} · {{ traceStatus(record.executionStatus) }}</span>
              <time>{{ traceDate(record.startedAt) }}</time>
              <span class="trace-secondary">{{ traceStatus(record.recordingStatus) }}</span>
            </button>
          </li>
        </ol>
        <button v-if="nextHistory" class="trace-button" type="button" :disabled="historyBusy" @click="loadHistory(true)">加载更多记录</button>
      </nav>
      <div class="trace-detail" :aria-busy="detailBusy">
        <div class="trace-toolbar"><strong>{{ root?.traceKind === 'CREATE' ? '创建路由过程' : '执行过程' }}</strong><button v-if="selected" class="trace-button" type="button" :disabled="detailBusy" @click="refreshDetail"><ReloadOutlined /> 重新查询</button></div>
        <p v-if="detailBusy" class="trace-secondary" role="status">正在更新追踪…</p>
        <p v-if="detailError" class="trace-notice" role="alert">{{ detailError }}<span v-if="steps.length"> 已加载步骤保留，当前显示可能不完整。</span></p>
        <p v-if="view && !root" class="trace-notice" role="status">{{ view.message || '追踪暂不可用' }}</p>
        <p v-if="!selected" class="trace-empty">选择一条记录查看实际执行过程。</p>
        <template v-if="root">
          <div class="trace-result" role="status">
            <strong>{{ root.traceKind === 'CREATE' ? '创建' : '生成' }}{{ traceStatus(root.executionStatus) }}</strong>
            <span>{{ traceStatus(view!.recordingStatus) }}</span>
          </div>
          <p v-if="view?.recordingStatus === 'PARTIAL'" class="trace-notice">部分追踪记录缺失，不能据此推断完整调用过程或总用量。生成结果以任务状态为准。</p>
          <p v-if="root.safeMessage" class="trace-notice">{{ root.safeMessage }}</p>
          <dl class="trace-summary">
            <div><dt>模式 / 类型</dt><dd>{{ root.generationMode === 'workflow' ? '工作流' : '标准' }} / {{ root.codeGenType || '尚未确定' }}</dd></div>
            <div><dt>开始时间</dt><dd>{{ traceDate(root.startedAt) }}</dd></div>
            <div><dt>结束时间</dt><dd>{{ root.finishedAt ? traceDate(root.finishedAt) : '尚无已知结束时间' }}</dd></div>
            <div><dt>已记录耗时</dt><dd>{{ traceDuration(root.durationMs) }}</dd></div>
            <div><dt>模型调用</dt><dd>{{ traceNumber(root.modelCalls) }} 次逻辑调用</dd></div>
            <div><dt>输入用量</dt><dd>{{ formatTraceUsage(root.knownInputTokens, root.inputReportedCalls, root.modelCalls) }}</dd></div>
            <div><dt>输出用量</dt><dd>{{ formatTraceUsage(root.knownOutputTokens, root.outputReportedCalls, root.modelCalls) }}</dd></div>
          </dl>
          <p class="trace-secondary">Token 为模型上报的用量单位；缺报保持未知，不估算金额或供应商内部重试次数。</p>
          <details class="trace-identifiers"><summary>关联编号与产物</summary><dl class="trace-summary">
            <div><dt>追踪编号</dt><dd>{{ root.traceId }}</dd></div>
            <div v-if="root.taskId"><dt>任务编号</dt><dd>{{ root.taskId }}</dd></div>
            <div v-if="root.requestId"><dt>请求编号</dt><dd>{{ root.requestId }}</dd></div>
            <div v-if="root.creationTraceId"><dt>创建追踪</dt><dd><button class="trace-button" type="button" @click="select({ kind: 'trace', id: root.creationTraceId! })">查看创建路由</button></dd></div>
            <div><dt>保留至</dt><dd>{{ traceDate(root.expiresAt) }}</dd></div>
            <div v-if="root.artifactRef"><dt>产物引用</dt><dd>{{ root.artifactRef }}</dd></div>
            <div v-if="root.artifactDigest"><dt>产物摘要</dt><dd>{{ root.artifactDigest }}</dd></div>
          </dl></details>
          <h3>执行步骤 <span class="trace-secondary">已加载 {{ rows.length }} 条</span></h3>
          <p v-if="!rows.length && !detailBusy" class="trace-empty">尚无已记录步骤；不会从聊天内容补造过程。</p>
          <ol class="trace-steps">
            <li v-for="row in rows" :key="row.step.spanId" :style="{ '--trace-depth': Math.min(row.depth, 3) }">
              <details>
                <summary>
                  <RightOutlined class="step-expander" aria-hidden="true" />
                  <span class="step-number">{{ row.step.stepNo }}</span>
                  <span class="step-name">{{ traceStepName(row.step) }}<small>{{ traceStepKind(row.step.stepKind) }} · 第 {{ row.step.attemptNo }} 次</small></span>
                  <span class="step-status">{{ traceStatus(row.step.status) }}</span>
                </summary>
                <p v-if="row.parentMissing" class="trace-notice">父步骤未加载或关联不完整。</p>
                <dl class="trace-summary">
                  <div><dt>步骤 / 父步骤</dt><dd>{{ row.step.spanId }} / {{ row.step.parentSpanId || '无' }}</dd></div>
                  <div><dt>开始</dt><dd>{{ traceDate(row.step.startedAt) }}</dd></div>
                  <div><dt>结束 / 耗时</dt><dd>{{ row.step.finishedAt ? traceDate(row.step.finishedAt) : '尚无已知结束时间' }} / {{ traceDuration(row.step.durationMs) }}</dd></div>
                  <template v-if="row.step.stepKind === 'MODEL'">
                    <div><dt>模型 / 配置别名</dt><dd>{{ row.step.modelName || '未知' }} / {{ row.step.providerAlias || '未知' }}</dd></div>
                    <div><dt>输入 / 输出 Token</dt><dd>{{ traceNumber(row.step.inputTokens) }} / {{ traceNumber(row.step.outputTokens) }}</dd></div>
                    <div><dt>结束原因</dt><dd>{{ row.step.finishReason || '未知' }}</dd></div>
                    <div><dt>Prompt 模板版本</dt><dd>{{ row.step.promptVersion || '未知' }}</dd></div>
                    <div><dt>配置摘要</dt><dd>{{ row.step.configDigest || '未知' }}</dd></div>
                  </template>
                  <div v-for="[label, value] in traceMetadata(row.step.safeMetadata)" :key="label"><dt>{{ label }}</dt><dd>{{ value }}</dd></div>
                  <div v-if="row.step.errorCategory"><dt>错误分类</dt><dd>{{ row.step.errorCategory }}</dd></div>
                  <div v-if="row.step.safeMessage"><dt>脱敏诊断</dt><dd>{{ row.step.safeMessage }}</dd></div>
                </dl>
              </details>
            </li>
          </ol>
          <button v-if="nextSteps" class="trace-button" type="button" :disabled="detailBusy" @click="loadMoreSteps">加载更多步骤</button>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.trace-panel { color: var(--ui-ink); background: var(--ui-surface); border-block: 1px solid var(--ui-line); flex: 0 0 auto; text-align: left; font-size: 13px; min-width: 0; }
.trace-header, .trace-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.trace-header { padding: 12px 20px; border-bottom: 1px solid var(--ui-line); }
h2 { font-size: 16px; margin: 0; } h3 { margin: 20px 0 8px; font-size: 14px; }
.trace-header p { margin: 4px 0 0; color: var(--ui-ink-soft); font-size: 12px; }
.trace-layout { display: grid; max-height: 60vh; overflow: auto; scrollbar-color: var(--ui-line-strong) var(--ui-surface); }
.trace-layout.with-history { grid-template-columns: minmax(200px, 250px) minmax(0, 1fr); }
.trace-history, .trace-detail { padding: 16px 20px; min-width: 0; overflow-wrap: anywhere; }
.trace-history { border-right: 1px solid var(--ui-line); }
.trace-button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 36px; padding: 6px 10px; border: 1px solid var(--ui-line-strong); border-radius: var(--ui-radius-sm); color: var(--ui-ink); background: var(--ui-surface); cursor: pointer; font: inherit; }
.trace-button:hover:not(:disabled), .trace-record:hover { background: var(--ui-surface-muted); }
.trace-button:disabled { opacity: .6; cursor: default; }
.icon-button { min-width: 36px; } .current-button { margin: 12px 0; width: 100%; }
.trace-record[aria-pressed='true'], .current-button[aria-pressed='true'] { background: var(--ui-accent-soft); border-color: var(--ui-accent-border); }
.trace-records, .trace-steps { list-style: none; margin: 12px 0; padding: 0; }
.trace-record { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: 10px 8px; width: 100%; text-align: left; color: var(--ui-ink); background: transparent; border: 1px solid transparent; border-bottom-color: var(--ui-line); cursor: pointer; }
.trace-record time, .trace-secondary { font-size: 12px; color: var(--ui-ink-soft); font-weight: 400; }
.trace-result { display: flex; flex-wrap: wrap; gap: 16px; margin: 16px 0; }
.trace-notice { padding: 10px 12px; background: var(--ui-surface-muted); color: var(--ui-ink); border-radius: var(--ui-radius-sm); line-height: 1.65; }
.trace-empty { color: var(--ui-ink-soft); line-height: 1.7; padding-block: 12px; }
.trace-summary { display: grid; gap: 8px; margin: 12px 0; font-variant-numeric: tabular-nums; }
.trace-summary > div { display: grid; grid-template-columns: 136px minmax(0, 1fr); gap: 12px; }
.trace-summary dt { color: var(--ui-ink-soft); } .trace-summary dd { margin: 0; overflow-wrap: anywhere; }
summary { cursor: pointer; min-height: 36px; padding-block: 8px; }
.trace-identifiers { border-block: 1px solid var(--ui-line); margin-top: 16px; }
.trace-steps > li { margin-inline-start: calc(var(--trace-depth) * 12px); border-bottom: 1px solid var(--ui-line); }
.trace-steps summary { display: flex; align-items: baseline; gap: 12px; padding: 10px 4px; }
.step-expander { width: 10px; flex: none; font-size: 10px; }
.trace-steps details[open] > summary .step-expander { transform: rotate(90deg); }
.step-number { color: var(--ui-ink-soft); font-variant-numeric: tabular-nums; }
.step-name { flex: 1; min-width: 0; overflow-wrap: anywhere; } .step-name small { display: block; margin-top: 4px; color: var(--ui-ink-soft); }
.step-status { flex: none; }
@media (max-width: 640px) {
  .trace-layout.with-history { grid-template-columns: minmax(0, 1fr); }
  .trace-history { border-right: 0; border-bottom: 1px solid var(--ui-line); }
  .trace-records { max-height: 150px; overflow: auto; }
  .trace-header, .trace-history, .trace-detail { padding: 12px; }
  .trace-summary > div { grid-template-columns: minmax(0, 1fr); gap: 2px; }
  .trace-steps > li { margin-inline-start: calc(var(--trace-depth) * 6px); }
  .trace-button, .trace-record { min-height: 44px; }
}
</style>
