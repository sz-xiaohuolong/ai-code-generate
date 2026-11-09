import request from '@/request'
import { TaskApiError } from '@/api/generationTask'

export type KnowledgeDocument = {
  id: string; name: string; activeVersionId: string | null; activeVersionNo: number | null
  pendingVersionId: string; pendingVersionNo: number; status: string; errorCode: string | null
  revision: number; byteSize: number; createdAt: number | string
}
export type KnowledgeQuota = { usedBytes: number; documents: number; maxFileBytes: number; maxTotalBytes: number; maxDocuments: number }
export type KnowledgePage = { records: KnowledgeDocument[]; total: number; quota: KnowledgeQuota; enabled: boolean }
export type KnowledgeReference = {
  taskId: string; appId: string; userMessageId: string | null; aiMessageId: string | null; state: string; createdAt: number | string
  items: { documentName: string; versionNo: number; text: string | null }[]
}
const unwrap = <T>(response: { data: { code: number; message?: string; data: T } }): T => {
  if (response.data.code !== 0) throw new TaskApiError(response.data.code, response.data.message || '知识库请求失败')
  return response.data.data
}
export const listKnowledge = async () => unwrap<KnowledgePage>(await request.get('/knowledge/documents', { params: { pageSize: 50 } }))
export const uploadKnowledge = async (file: File, clientRequestId: string, replacement?: KnowledgeDocument) => {
  const form = new FormData()
  form.append('file', file); form.append('clientRequestId', clientRequestId)
  if (replacement) form.append('expectedRevision', String(replacement.revision))
  return unwrap(await request.post(replacement ? `/knowledge/documents/${replacement.id}/versions` : '/knowledge/documents', form))
}
export const retryKnowledge = async (document: KnowledgeDocument, clientRequestId: string) =>
  unwrap(await request.post(`/knowledge/documents/${document.id}/retry`, { versionId: document.pendingVersionId, clientRequestId }))
export const deleteKnowledge = async (id: string) => unwrap(await request.delete(`/knowledge/documents/${id}`))
export const listKnowledgeReferences = async (appId: string, pageNum = 1) =>
  unwrap<KnowledgeReference[]>(await request.get('/knowledge/references', { params: { appId, pageNum, pageSize: 20 } }))
export const getKnowledgeReference = async (taskId: string) =>
  unwrap<KnowledgeReference>(await request.get(`/knowledge/references/${encodeURIComponent(taskId)}`))
