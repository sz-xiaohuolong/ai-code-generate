<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getKnowledgeReference, listKnowledgeReferences, type KnowledgeReference } from '@/api/knowledge'

const props = defineProps<{ appId: string; refreshKey?: string }>()
const open = ref(false)
const loading = ref(false)
const records = ref<KnowledgeReference[]>([])
const selected = ref<KnowledgeReference>()
const error = ref('')
const page = ref(1)
const hasMore = ref(false)
let epoch = 0
const labels: Record<string, string> = {
  REQUIRED: '检索尚未完成或参考记录缺失', PREPARED: '资料已找到，尚未确认提交给生成模型',
  SUBMITTED: '资料已提供给生成模型', FAILED: '参考准备失败，未进入代码生成',
  SEND_UNCONFIRMED: '模型请求的提交结果未确认', INCOMPLETE: '参考记录不完整',
}
const load = async (more = false) => {
  const id = ++epoch
  loading.value = true
  error.value = ''
  if (!more) { records.value = []; selected.value = undefined; page.value = 1 }
  try {
    const rows = await listKnowledgeReferences(props.appId, more ? page.value + 1 : 1)
    if (id !== epoch) return
    records.value = more ? [...records.value, ...rows] : rows
    if (more) page.value++
    hasMore.value = rows.length === 20
  } catch (cause) { if (id === epoch) error.value = cause instanceof Error ? cause.message : '参考记录暂不可用' }
  finally { if (id === epoch) loading.value = false }
}
const show = async (record: KnowledgeReference) => {
  const id = ++epoch
  selected.value = undefined
  error.value = ''
  loading.value = true
  try { const detail = await getKnowledgeReference(record.taskId); if (id === epoch) selected.value = detail }
  catch (cause) { if (id === epoch) error.value = cause instanceof Error ? cause.message : '读取参考片段失败' }
  finally { if (id === epoch) loading.value = false }
}
const refresh = () => { if (open.value) load() }
const storageChanged = (event: StorageEvent) => { if (event.key === 'knowledge:changed') refresh() }
window.addEventListener('knowledge-documents-changed', refresh)
window.addEventListener('storage', storageChanged)
window.addEventListener('focus', refresh)
watch(() => props.appId, () => { epoch++; records.value = []; selected.value = undefined; open.value = false; loading.value = false; error.value = '' })
watch(() => props.refreshKey, refresh)
onBeforeUnmount(() => { epoch++; window.removeEventListener('knowledge-documents-changed', refresh); window.removeEventListener('storage', storageChanged); window.removeEventListener('focus', refresh) })
</script>

<template>
  <section class="knowledge-references" aria-label="应用参考资料">
    <button type="button" class="references-toggle" :aria-expanded="open" @click="open = !open; if (open) load()">{{ open ? '收起参考资料' : '查看参考资料' }}</button>
    <div v-if="open" class="references-content">
      <p>记录长期保留，随应用删除。这里展示提供给模型的资料，不代表生成结果已完全正确。</p>
      <a-alert v-if="error" :message="error" type="error" show-icon />
      <a-button v-if="error" size="small" @click="load()">重新加载</a-button>
      <p v-if="!loading && !error && !records.length">此应用还没有知识库参考记录。</p>
      <ul class="reference-list">
        <li v-for="record in records" :key="record.taskId">
          <button type="button" :disabled="loading" @click="show(record)">
            {{ new Date(Number(record.createdAt)).toLocaleString('zh-CN') }} · {{ labels[record.state] || '记录状态未知' }}
          </button>
        </li>
      </ul>
      <a-button v-if="hasMore" size="small" :loading="loading" @click="load(true)">加载更早记录</a-button>
      <p v-if="loading" role="status">正在读取参考记录…</p>
      <div v-if="selected" class="reference-detail">
        <p>{{ labels[selected.state] || '参考状态未知' }}</p>
        <p v-if="!selected.items.length">没有可展示的参考片段，请结合上方记录状态判断。</p>
        <article v-for="(item, index) in selected.items" :key="index">
          <strong>{{ index + 1 }}. {{ item.documentName }} · v{{ item.versionNo }}</strong>
          <p v-if="item.text === null">原文档已删除，历史片段已隐藏。</p>
          <pre v-else>{{ item.text }}</pre>
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
.knowledge-references { border-top: 1px solid var(--ui-line); padding: 8px 16px; font-size: 12px; flex: none; }
.references-toggle { padding: 5px 0; color: var(--ui-accent); background: none; border: 0; cursor: pointer; }
.references-content { max-height: 360px; overflow-y: auto; overscroll-behavior: contain; padding-bottom: 12px; }
.references-content p { color: var(--ui-ink-soft); line-height: 1.6; }
.reference-list { list-style: none; margin: 12px 0; padding: 0; }
.reference-list button { width: 100%; padding: 8px 0; border: 0; border-bottom: 1px solid var(--ui-line); text-align: left; background: none; color: var(--ui-ink); cursor: pointer; }
.reference-list button:disabled { cursor: wait; }
.reference-detail article { padding: 12px 0; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font-family: inherit; color: var(--ui-ink-soft); line-height: 1.65; }
</style>
