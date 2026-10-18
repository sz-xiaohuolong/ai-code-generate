import request from '@/request'
import type { GenerationTask } from '@/utils/generationTask'

export class TaskApiError extends Error {
  constructor(public code: number, text: string) { super(text) }
}
const unwrap = <T>(response: { data: { code: number; message?: string; data?: T } }): T => {
  if (response.data.code !== 0) throw new TaskApiError(response.data.code, response.data.message || '任务请求失败')
  return response.data.data as T
}
export const startGenerationTask = async (appId: string, message: string, clientRequestId: string) =>
  unwrap<GenerationTask>(await request.post('/app/generation/tasks', { appId, message, clientRequestId }))
export const getGenerationTask = async (id: string) =>
  unwrap<GenerationTask>(await request.get(`/app/generation/tasks/${encodeURIComponent(id)}`))
export const getCurrentGenerationTask = async (appId: string) =>
  unwrap<{ task: GenerationTask | null; previewAvailable: boolean }>(await request.get('/app/generation/tasks/current', { params: { appId } }))
export const stopGenerationTask = async (id: string) =>
  unwrap<GenerationTask>(await request.post(`/app/generation/tasks/${encodeURIComponent(id)}/stop`))
export const generationEventsUrl = (id: string): string =>
  new URL(`${request.defaults.baseURL}/app/generation/tasks/${encodeURIComponent(id)}/events`, window.location.origin).toString()
