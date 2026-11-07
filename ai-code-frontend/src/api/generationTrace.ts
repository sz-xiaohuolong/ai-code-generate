import request from '@/request'
import type { TracePage, TraceRoot, TraceStep, TraceView } from '@/utils/generationTrace'

export class TraceApiError extends Error {
  readonly code: number
  constructor(code: number) { super('追踪查询失败'); this.code = code }
}
const read = async <T>(path: string, signal: AbortSignal, params?: Record<string, string | number>): Promise<T> => {
  const response = await request.get<{ code: number; data?: T }>(`/app/traces${path}`, { signal, params })
  if (response.data.code !== 0) throw new TraceApiError(response.data.code)
  if (response.data.data == null) throw new TraceApiError(50001)
  return response.data.data
}
export const listGenerationTraces = (appId: string, signal: AbortSignal, cursor?: string) =>
  read<TracePage<TraceRoot>>('', signal, { appId, pageSize: 20, ...(cursor ? { cursor } : {}) })
export const getGenerationTrace = (traceId: string, signal: AbortSignal) =>
  read<TraceView>(`/${encodeURIComponent(traceId)}`, signal)
export const getTaskTrace = (taskId: string, signal: AbortSignal) =>
  read<TraceView>(`/by-task/${encodeURIComponent(taskId)}`, signal)
export const getCreationTrace = (requestId: string, signal: AbortSignal) =>
  read<TraceView>(`/by-request/${encodeURIComponent(requestId)}`, signal)
export const listTraceSteps = (traceId: string, signal: AbortSignal, cursor = '0') =>
  read<TracePage<TraceStep>>(`/${encodeURIComponent(traceId)}/steps`, signal, { cursor, pageSize: 100 })
