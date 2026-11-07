<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { TaskApiError } from '@/api/generationTask'
import { deleteKnowledge, listKnowledge, retryKnowledge, uploadKnowledge, type KnowledgeDocument, type KnowledgePage } from '@/api/knowledge'

const data = ref<KnowledgePage>()
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const picker = ref<HTMLInputElement>()
const replacing = ref<KnowledgeDocument>()
const pending = ref<{ file: File; id: string; replacement?: KnowledgeDocument }>()
const retryIds = new Map<string, string>()
let disposed = false
let timer: ReturnType<typeof setTimeout> | undefined
const bytes = (value: number) => value < 1024 ? `${value} B` : value < 1024 * 1024 ? `${(value / 1024).toFixed(1)} KiB` : `${(value / 1024 / 1024).toFixed(2)} MiB`
const failure = (code: string | null) => ({
  EMBEDDING_UNAVAILABLE: '本地向量模型暂不可用，请检查后重试。',
  EMBEDDING_MODEL_MISSING: '未找到已配置的本地向量模型。',
  EMBEDDING_PROFILE_CHANGED: '模型或分块配置已变化，需要重建索引。',
  INDEX_UNAVAILABLE: '知识索引暂不可用，请检查服务后重试。',
  INDEX_INCOMPLETE: '索引未完整保存，可重新处理。',
  PROCESSING_EXPIRED: '处理超时或服务中断，可重新处理。',
  PROCESSING_BUSY: '处理队列已满，请稍后重试。',
}[code || ''] || '资料处理失败，可重新处理或替换文件。')

const load = async () => {
  if (loading.value || disposed) return
  if (timer) clearTimeout(timer)
  loading.value = true
  try {
    const result = await listKnowledge()
    if (disposed) return
    data.value = result
    error.value = ''
  } catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : '加载失败，请重试' }
  finally {
    loading.value = false
    if (!disposed && data.value?.records.some(d => d.status === 'PROCESSING')) timer = setTimeout(load, 3000)
  }
}
const choose = (document?: KnowledgeDocument) => {
  replacing.value = document
  picker.value?.click()
}
const notifyChanged = () => {
  window.dispatchEvent(new Event('knowledge-documents-changed'))
  try { localStorage.setItem('knowledge:changed', String(Date.now())) } catch { /* 通知失败不影响删除结果。 */ }
}
const submit = async () => {
  const operation = pending.value
  if (!operation || busy.value) return
  busy.value = true
  try {
    await uploadKnowledge(operation.file, operation.id, operation.replacement)
    if (disposed) return
    pending.value = undefined
    message.success('文档已接收，正在处理')
    await load()
  } catch (cause) {
    if (disposed) return
    if (cause instanceof TaskApiError) pending.value = undefined
    message.error(cause instanceof TaskApiError ? cause.message : '提交结果未确认，请重试本次提交')
  } finally { busy.value = false }
}
const picked = (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/\.(md|txt)$/i.test(file.name) || !file.size || file.size > (data.value?.quota.maxFileBytes || 2097152)) {
    message.error('请选择容量限制内的 UTF-8 Markdown 或 TXT 文件')
    return
  }
  pending.value = { file, id: crypto.randomUUID(), replacement: replacing.value }
  submit()
}
const retry = async (document: KnowledgeDocument) => {
  if (busy.value) return
  busy.value = true
  const key = `${document.id}:${document.pendingVersionId}`
  const id = retryIds.get(key) || crypto.randomUUID()
  retryIds.set(key, id)
  try { await retryKnowledge(document, id); retryIds.delete(key); await load() }
  catch (cause) {
    if (cause instanceof TaskApiError) retryIds.delete(key)
    message.error(cause instanceof Error ? cause.message : '重试结果未确认，请再次点击重试')
  } finally { busy.value = false }
}
const remove = async (document: KnowledgeDocument) => {
  if (busy.value) return
  busy.value = true
  try { await deleteKnowledge(document.id); notifyChanged(); await load(); message.success('已删除，新任务将不再检索此文档') }
  catch (cause) { message.error(cause instanceof Error ? cause.message : '删除失败，请重试') }
  finally { busy.value = false }
}
onMounted(load)
onBeforeUnmount(() => { disposed = true; if (timer) clearTimeout(timer) })
</script>

<template>
  <section class="knowledge-page" aria-label="个人知识库管理">
    <div class="page-heading">
      <div><h1>个人知识库</h1><p>上传接口文档与业务规则，让生成结果参考你的真实资料。</p></div>
      <a-button type="primary" :disabled="busy || !!pending || !data?.enabled" @click="choose()">上传文档</a-button>
      <input ref="picker" class="file-picker" type="file" accept=".md,.txt" aria-label="选择知识文档" @change="picked" />
    </div>
    <p class="privacy-note">资料仅本人可访问，可用于自己的所有应用。向量化在本地完成；开启知识库生成时，相关片段会发送给当前配置的生成模型。</p>
    <p v-if="data" class="quota">{{ data.quota.documents }} / {{ data.quota.maxDocuments }} 份 · 已用 {{ bytes(data.quota.usedBytes) }} / {{ bytes(data.quota.maxTotalBytes) }} · 单文件上限 {{ bytes(data.quota.maxFileBytes) }}，支持 UTF-8 .md / .txt</p>
    <a-alert v-if="data && !data.enabled" type="warning" message="知识库尚未启用，请先完成后端配置。已有参考记录仍可查看。" show-icon />
    <div v-if="pending" class="pending-operation" role="status">
      <span>{{ pending.file.name }}{{ busy ? '：正在提交…' : '：提交结果尚未确认' }}</span>
      <a-button v-if="!busy" @click="submit">重试本次提交</a-button>
    </div>
    <div class="list-toolbar"><h2>我的文档</h2><a-button :loading="loading" @click="load">刷新状态</a-button></div>
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <a-spin v-else-if="loading && !data" tip="正在加载资料" />
    <div v-else-if="!data?.records.length" class="empty-state"><h3>还没有上传资料</h3><p>从一份接口说明或业务规则开始。处理完成后，在生成输入框开启“使用个人知识库”。</p></div>
    <ul v-else class="document-list">
      <li v-for="document in data.records" :key="document.id">
        <div class="document-info">
          <strong>{{ document.name }}</strong>
          <p>{{ bytes(document.byteSize) }} · {{ document.activeVersionNo ? `当前可用 v${document.activeVersionNo}` : '尚无可用版本' }}</p>
          <p v-if="document.status === 'PROCESSING'" role="status">正在处理 v{{ document.pendingVersionNo }}{{ document.activeVersionId ? '，旧版仍可用于生成' : '，完成后即可检索' }}</p>
          <p v-else-if="document.status === 'FAILED'" class="failure">v{{ document.pendingVersionNo }} 处理失败：{{ failure(document.errorCode) }}{{ document.activeVersionId ? ' 旧版仍可用。' : '' }}</p>
          <p v-else>已就绪</p>
        </div>
        <div class="document-actions">
          <a-button v-if="document.status === 'FAILED'" :disabled="busy || !data.enabled" @click="retry(document)">重新处理</a-button>
          <a-button :disabled="busy || !!pending || document.status === 'PROCESSING' || !data.enabled" @click="choose(document)">替换文件</a-button>
          <a-popconfirm title="删除后新任务不再检索此文档，历史参考片段将隐藏；已有代码和对话中的内容不会清除。" ok-text="删除" cancel-text="取消" @confirm="remove(document)">
            <a-button danger :disabled="busy">删除</a-button>
          </a-popconfirm>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.knowledge-page { width: min(100%, 1000px); margin: 0 auto; padding: 32px 24px 64px; color: var(--ui-ink); }
.page-heading, .list-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
h1 { margin: 0 0 8px; font-size: 28px; } h2 { margin: 0; font-size: 18px; }
p { color: var(--ui-ink-soft); line-height: 1.65; }
.privacy-note { max-width: 75ch; margin: 24px 0 12px; }
.quota { font-size: 13px; font-variant-numeric: tabular-nums; }
.file-picker { display: none; }
.list-toolbar { margin: 32px 0 16px; }
.document-list { padding: 0; margin: 0; list-style: none; border-top: 1px solid var(--ui-line); }
.document-list li { display: flex; justify-content: space-between; gap: 20px; padding: 20px 0; border-bottom: 1px solid var(--ui-line); }
.document-info { min-width: 0; overflow-wrap: anywhere; }
.document-info strong { font-size: 15px; } .document-info p { margin: 6px 0 0; font-size: 13px; }
.document-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; flex-shrink: 0; }
.failure { color: var(--ui-danger, #b42318); }
.empty-state { padding: 40px 0; max-width: 65ch; }
.pending-operation { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-top: 16px; }
@media (max-width: 600px) {
  .knowledge-page { padding: 24px 16px; }
  .page-heading { align-items: flex-start; flex-wrap: wrap; }
  .document-list li { flex-direction: column; gap: 12px; }
  .document-actions { justify-content: flex-start; }
}
</style>
