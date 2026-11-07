/** 后端 Long 统一序列化为字符串，编号、计数和版本均保留精度。 */
export type TraceRoot = {
  traceId: string
  rootSpanId: string
  traceKind: string
  requestId?: string | null
  taskId?: string | null
  appId?: string | null
  creationTraceId?: string | null
  generationMode: string
  codeGenType?: string | null
  executionStatus: string
  recordingStatus: string
  startedAt: string
  finishedAt?: string | null
  expiresAt: string
  durationMs?: string | null
  modelCalls: string
  inputReportedCalls: string
  outputReportedCalls: string
  knownInputTokens?: string | null
  knownOutputTokens?: string | null
  safeMessage?: string | null
  artifactDigest?: string | null
  artifactRef?: string | null
  revision: string
}
export type TraceStep = {
  traceId: string
  spanId: string
  parentSpanId?: string | null
  stepNo: string
  attemptNo: number
  stepKind: string
  stepName: string
  status: string
  startedAt: string
  finishedAt?: string | null
  durationMs?: string | null
  providerAlias?: string | null
  modelName?: string | null
  promptVersion?: string | null
  configDigest?: string | null
  finishReason?: string | null
  inputTokens?: string | null
  outputTokens?: string | null
  safeMetadata?: string | null
  errorCategory?: string | null
  safeMessage?: string | null
  revision: string
}
export type TraceView = { trace: TraceRoot | null; recordingStatus: string; message?: string | null }
export type TracePage<T> = { records: T[]; nextCursor: string | null }

const integer = (value: string | null | undefined): bigint | undefined =>
  value != null && /^\d+$/.test(value) ? BigInt(value) : undefined

export const traceNumber = (value?: string | null): string => integer(value)?.toLocaleString('en-US') ?? '未知'
export const traceDuration = (value?: string | null): string => value == null ? '未知' : `${traceNumber(value)} 毫秒`
export const traceDate = (value?: string | null): string => {
  if (!value || !Number.isFinite(Date.parse(value))) return '未知'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
export const formatTraceUsage = (tokens: string | null | undefined, reported: string, calls: string): string => {
  if (integer(calls) === 0n) return '暂无模型调用'
  const value = integer(tokens) == null ? '未知' : `已知 ${traceNumber(tokens)} Token`
  return `${value} · ${traceNumber(reported)}/${traceNumber(calls)} 次上报`
}

const statuses: Record<string, string> = {
  RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消',
  TIMED_OUT: '已超时', INTERRUPTED: '已中断', UNKNOWN: '结果未知', SKIPPED: '已跳过',
  COLLECTING: '记录中', COMPLETE: '追踪完整', PARTIAL: '追踪不完整', UNAVAILABLE: '追踪暂不可用',
}
export const traceStatus = (status: string): string => statuses[status] ?? '未知状态'
const names: Record<string, string> = {
  generation: '生成任务', native_generation: '原生代码生成', routing: '类型路由',
  image_collector: '素材收集', prompt_enhancer: '提示词增强', code_generator: '代码生成',
  code_quality_check: '代码质检', project_builder: '项目构建', router: '类型路由',
  build: '构建', preview: '预览检查', repair: '自动修复', stop: '停止请求',
  streaming_model: '生成模型', routing_model: '路由模型', model: '模型调用',
}
const kinds: Record<string, string> = { STAGE: '阶段', MODEL: '模型调用', TOOL: '工具调用', BUILD: '构建', PREVIEW: '预览检查', STOP: '停止请求' }
export const traceStepName = (step: TraceStep): string => names[step.stepName] ?? step.stepName
export const traceStepKind = (kind: string): string => kinds[kind] ?? kind

export const mergeTraceSteps = (old: TraceStep[], incoming: TraceStep[]): TraceStep[] => {
  const map = new Map(old.map(step => [step.spanId, step]))
  for (const step of incoming) {
    const current = map.get(step.spanId)
    if (!current || (integer(step.revision) ?? 0n) >= (integer(current.revision) ?? 0n)) map.set(step.spanId, step)
  }
  return [...map.values()].sort((a, b) => (integer(a.stepNo) ?? 0n) < (integer(b.stepNo) ?? 0n) ? -1 : 1)
}
export const traceStepRows = (steps: TraceStep[], rootSpanId: string) => {
  const sorted = mergeTraceSteps([], steps)
  const map = new Map(sorted.map(step => [step.spanId, step]))
  return sorted.map(step => {
    const seen = new Set([step.spanId])
    let parent = step.parentSpanId
    let depth = 0
    let parentMissing = false
    while (parent && parent !== rootSpanId) {
      const ancestor = map.get(parent)
      if (!ancestor || seen.has(parent)) { parentMissing = true; break }
      seen.add(parent)
      depth++
      parent = ancestor.parentSpanId
    }
    return { step, depth, parentMissing }
  })
}

export const traceErrorText = (error: unknown): string => {
  const code = typeof error === 'object' && error !== null && 'code' in error ? error.code : undefined
  if (code === 40101) return '仅应用所有者或创建请求的发起用户可以查看追踪。'
  if (code === 40100) return '登录状态已失效，请重新登录后查看。'
  if (code === 40400) return '记录尚未写入、不存在或已超过 7 天保留期；应用删除后也不可查询。'
  return '追踪暂不可用，请重新查询。追踪查询不会改变生成任务的结果。'
}
export const traceMetadata = (value?: string | null): [string, string][] => {
  try {
    const parsed = JSON.parse(value ?? '{}') as Record<string, unknown>
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return []
    const result: [string, string][] = []
    for (const [key, label] of [['toolName', '工具'], ['relativePath', '文件'], ['buildKind', '构建分类']] as const) {
      if (typeof parsed[key] === 'string') result.push([label, parsed[key]])
    }
    if (parsed.truncated === true) result.push(['诊断', '内容已截断'])
    if (parsed.metadataOmitted === true) result.push(['元数据', '部分字段已省略'])
    return result
  } catch { return [] }
}
