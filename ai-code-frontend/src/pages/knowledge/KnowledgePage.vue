<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import knowledgeIcons from '@/assets/knowledge-icons.svg?no-inline'
import { message } from 'ant-design-vue'
import { TaskApiError } from '@/api/generationTask'
import {
  deleteKnowledge,
  listKnowledge,
  retryKnowledge,
  uploadKnowledge,
  type KnowledgeDocument,
  type KnowledgePage,
} from '@/api/knowledge'

const data = ref<KnowledgePage>()
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const picker = ref<HTMLInputElement>()
const replacing = ref<KnowledgeDocument>()
const pending = ref<{ file: File; id: string; replacement?: KnowledgeDocument }>()
const selectedId = ref<string>()
const detailsPanel = ref<HTMLElement>()
const selectDocument = async (id: string) => {
  selectedId.value = id
  if (window.matchMedia('(max-width: 800px)').matches) {
    await nextTick()
    detailsPanel.value?.focus({ preventScroll: true })
    detailsPanel.value?.scrollIntoView({ block: 'start' })
  }
}
const selectedDocument = computed(
  () =>
    data.value?.records.find((document) => document.id === selectedId.value) ??
    data.value?.records[0],
)
const storagePercent = computed(() => {
  const quota = data.value?.quota
  if (!quota || quota.maxTotalBytes <= 0) return 0
  return Math.min(100, Math.max(0, (quota.usedBytes / quota.maxTotalBytes) * 100))
})
const storageRemaining = computed(() =>
  Math.max(0, (data.value?.quota.maxTotalBytes ?? 0) - (data.value?.quota.usedBytes ?? 0)),
)
const statusLabel = (status: string) =>
  ({ READY: '已就绪', PROCESSING: '处理中', FAILED: '处理失败' })[status] ?? '状态待确认'
const fileType = (name: string) => (name.toLowerCase().endsWith('.md') ? 'Markdown' : 'TXT')
const createdDate = (value: number) =>
  new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(value))
const retryIds = new Map<string, string>()
let disposed = false
let timer: ReturnType<typeof setTimeout> | undefined
const bytes = (value: number) =>
  value < 1024
    ? `${value} B`
    : value < 1024 * 1024
      ? `${(value / 1024).toFixed(1)} KiB`
      : `${(value / 1024 / 1024).toFixed(2)} MiB`
const failure = (code: string | null) =>
  ({
    EMBEDDING_UNAVAILABLE: '本地向量模型暂不可用，请检查后重试。',
    EMBEDDING_MODEL_MISSING: '未找到已配置的本地向量模型。',
    EMBEDDING_PROFILE_CHANGED: '模型或分块配置已变化，需要重建索引。',
    INDEX_UNAVAILABLE: '知识索引暂不可用，请检查服务后重试。',
    INDEX_INCOMPLETE: '索引未完整保存，可重新处理。',
    PROCESSING_EXPIRED: '处理超时或服务中断，可重新处理。',
    PROCESSING_BUSY: '处理队列已满，请稍后重试。',
  })[code || ''] || '资料处理失败，可重新处理或替换文件。'

const load = async () => {
  if (loading.value || disposed) return
  if (timer) clearTimeout(timer)
  loading.value = true
  try {
    const result = await listKnowledge()
    if (disposed) return
    data.value = result
    error.value = ''
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '加载失败，请重试'
  } finally {
    loading.value = false
    if (!disposed && data.value?.records.some((d) => d.status === 'PROCESSING'))
      timer = setTimeout(load, 3000)
  }
}
const choose = (document?: KnowledgeDocument) => {
  replacing.value = document
  picker.value?.click()
}
const notifyChanged = () => {
  window.dispatchEvent(new Event('knowledge-documents-changed'))
  try {
    localStorage.setItem('knowledge:changed', String(Date.now()))
  } catch {
    /* 通知失败不影响删除结果。 */
  }
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
  } finally {
    busy.value = false
  }
}
const picked = (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (
    !/\.(md|txt)$/i.test(file.name) ||
    !file.size ||
    file.size > (data.value?.quota.maxFileBytes || 2097152)
  ) {
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
  try {
    await retryKnowledge(document, id)
    retryIds.delete(key)
    await load()
  } catch (cause) {
    if (cause instanceof TaskApiError) retryIds.delete(key)
    message.error(cause instanceof Error ? cause.message : '重试结果未确认，请再次点击重试')
  } finally {
    busy.value = false
  }
}
const remove = async (document: KnowledgeDocument) => {
  if (busy.value) return
  busy.value = true
  try {
    await deleteKnowledge(document.id)
    notifyChanged()
    await load()
    message.success('已删除，新任务将不再检索此文档')
  } catch (cause) {
    message.error(cause instanceof Error ? cause.message : '删除失败，请重试')
  } finally {
    busy.value = false
  }
}
onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <section class="knowledge-page" aria-label="个人知识库管理">
    <div class="page-heading">
      <div>
        <p class="eyebrow">工作空间 <span aria-hidden="true">/</span> 你的资料</p>
        <h1>个人知识库</h1>
        <p class="page-description">让生成结果，更懂你的业务。</p>
      </div>
      <a-button
        class="upload-button"
        type="primary"
        :disabled="busy || !!pending || !data?.enabled"
        @click="choose()"
      >
        <svg class="icon" aria-hidden="true"><use :href="`${knowledgeIcons}#upload`" /></svg
        >上传文档
      </a-button>
      <input
        ref="picker"
        class="file-picker"
        type="file"
        accept=".md,.txt"
        aria-label="选择知识文档"
        @change="picked"
      />
    </div>
    <a-alert
      v-if="data && !data.enabled"
      class="page-notice"
      type="warning"
      message="知识库尚未启用"
      description="请先完成后端配置。已有参考记录仍可查看。"
      show-icon
    />
    <div v-if="pending" class="pending-operation page-notice" role="status">
      <span>{{ pending.file.name }}{{ busy ? '：正在提交…' : '：提交结果尚未确认' }}</span>
      <a-button v-if="!busy" @click="submit">重试本次提交</a-button>
    </div>
    <div class="knowledge-workspace">
      <div class="documents-column">
        <div class="list-toolbar">
          <div class="list-heading">
            <h2>我的文档</h2>
            <span v-if="data" class="document-count">{{ data.total }} 份</span>
          </div>
          <a-button class="refresh-button" :loading="loading" @click="load">
            <svg v-if="!loading" class="icon" aria-hidden="true">
              <use :href="`${knowledgeIcons}#refresh-cw`" /></svg
            >刷新状态
          </a-button>
        </div>
        <a-alert v-if="error" class="list-error" type="error" :message="error" show-icon />
        <div v-if="loading && !data" class="loading-state" role="status">
          <a-spin /><span>正在加载资料</span>
        </div>
        <div v-else-if="!data && error" class="empty-state">
          <h3>资料暂时没加载出来</h3>
          <p>点击“刷新状态”再试一次。</p>
        </div>
        <div v-else-if="!data?.records.length" class="empty-state">
          <svg class="empty-icon" aria-hidden="true">
            <use :href="`${knowledgeIcons}#file-text`" />
          </svg>
          <h3>从第一份资料开始</h3>
          <p>接口说明，或一份业务规则。</p>
          <p>上传后，让 AI 有据可依。</p>
          <a-button :disabled="busy || !!pending || !data?.enabled" @click="choose()"
            >上传第一份文档</a-button
          >
        </div>
        <template v-else>
          <div class="list-columns" aria-hidden="true">
            <span>文档名称</span><span>处理状态</span><span>操作</span>
          </div>
          <ul class="document-list" aria-label="知识文档">
            <li
              v-for="document in data.records"
              :key="document.id"
              :class="{ selected: selectedDocument?.id === document.id }"
            >
              <div class="document-main">
                <div class="file-symbol" aria-hidden="true">
                  <svg class="icon"><use :href="`${knowledgeIcons}#file-text`" /></svg>
                </div>
                <div class="document-info">
                  <button
                    class="document-name"
                    :aria-pressed="selectedDocument?.id === document.id"
                    aria-controls="knowledge-document-details"
                    @click="selectDocument(document.id)"
                  >
                    {{ document.name }}
                  </button>
                  <p class="document-meta">
                    {{ fileType(document.name) }} <span>·</span> {{ bytes(document.byteSize) }}
                    <span>·</span>
                    {{
                      document.activeVersionNo
                        ? `可用 v${document.activeVersionNo}`
                        : '尚无可用版本'
                    }}
                  </p>
                  <p v-if="document.status === 'PROCESSING'" class="document-note" role="status">
                    正在处理 v{{ document.pendingVersionNo }}。{{
                      document.activeVersionId ? '旧版仍可用于生成。' : '完成后即可检索。'
                    }}
                  </p>
                  <p v-else-if="document.status === 'FAILED'" class="document-note failure">
                    v{{ document.pendingVersionNo }} 处理失败。{{ failure(document.errorCode)
                    }}{{ document.activeVersionId ? ' 旧版仍可用。' : '' }}
                  </p>
                </div>
              </div>
              <span class="status-label" :class="document.status.toLowerCase()"
                ><i aria-hidden="true" />{{ statusLabel(document.status) }}</span
              >
              <div class="document-actions">
                <a-button
                  v-if="document.status === 'FAILED'"
                  class="retry-button"
                  size="small"
                  :disabled="busy || !data?.enabled"
                  @click="retry(document)"
                  >重新处理</a-button
                >
                <button
                  class="detail-button"
                  :aria-label="`查看 ${document.name} 的详情`"
                  :aria-pressed="selectedDocument?.id === document.id"
                  aria-controls="knowledge-document-details"
                  @click="selectDocument(document.id)"
                >
                  <svg class="icon" aria-hidden="true">
                    <use :href="`${knowledgeIcons}#chevron-right`" />
                  </svg>
                </button>
              </div>
            </li>
          </ul>
          <div class="list-footer">
            <span>显示 {{ data?.records.length }} / {{ data?.total }} 份文档</span
            ><span>选择文档，查看详情</span>
          </div>
        </template>
        <div class="privacy-note">
          <svg class="icon" aria-hidden="true">
            <use :href="`${knowledgeIcons}#lock-keyhole`" />
          </svg>
          <div>
            <strong>你的资料，仅自己可见</strong>
            <p>可用于你的所有应用。向量化在本地完成。</p>
            <p>开启知识库生成时，相关片段会发送给生成模型。</p>
          </div>
        </div>
      </div>
      <aside class="knowledge-sidebar" aria-label="文档详情与容量">
        <section
          id="knowledge-document-details"
          ref="detailsPanel"
          tabindex="-1"
          class="document-details"
          aria-labelledby="details-title"
        >
          <div class="section-heading">
            <h2 id="details-title">文档详情</h2>
            <span v-if="selectedDocument" class="detail-type">{{
              fileType(selectedDocument.name)
            }}</span>
          </div>
          <template v-if="selectedDocument">
            <div class="detail-file-symbol" aria-hidden="true">
              <svg><use :href="`${knowledgeIcons}#file-text`" /></svg>
            </div>
            <h3 class="detail-filename">{{ selectedDocument.name }}</h3>
            <span class="status-label" :class="selectedDocument.status.toLowerCase()"
              ><i aria-hidden="true" />{{ statusLabel(selectedDocument.status) }}</span
            >
            <dl class="document-facts">
              <div>
                <dt>文件大小</dt>
                <dd>{{ bytes(selectedDocument.byteSize) }}</dd>
              </div>
              <div>
                <dt>上传日期</dt>
                <dd>{{ createdDate(selectedDocument.createdAt) }}</dd>
              </div>
              <div>
                <dt>可用版本</dt>
                <dd>
                  {{
                    selectedDocument.activeVersionNo
                      ? `v${selectedDocument.activeVersionNo}`
                      : '暂无'
                  }}
                </dd>
              </div>
              <div v-if="selectedDocument.status !== 'READY'">
                <dt>待处理版本</dt>
                <dd>v{{ selectedDocument.pendingVersionNo }}</dd>
              </div>
            </dl>
            <p v-if="selectedDocument.status === 'PROCESSING'" class="detail-note">
              新版正在处理。{{
                selectedDocument.activeVersionId ? '旧版仍可用于生成。' : '完成后即可用于生成。'
              }}
            </p>
            <p v-else-if="selectedDocument.status === 'FAILED'" class="detail-note failure">
              {{ failure(selectedDocument.errorCode)
              }}{{ selectedDocument.activeVersionId ? ' 旧版仍可用。' : '' }}
            </p>
            <p v-else class="detail-note">生成时开启“使用个人知识库”。</p>
            <div class="detail-actions">
              <a-button
                :disabled="
                  busy || !!pending || selectedDocument.status === 'PROCESSING' || !data?.enabled
                "
                @click="choose(selectedDocument)"
                >替换文件</a-button
              >
              <a-popconfirm
                :key="selectedDocument.id"
                title="删除这份文档？"
                ok-text="删除"
                cancel-text="取消"
                @confirm="remove(selectedDocument)"
              >
                <template #description
                  ><div class="delete-description">
                    新任务将不再检索此文档。<br />历史参考片段将隐藏。<br />已有代码和对话内容不会清除。
                  </div></template
                >
                <a-button danger :disabled="busy">删除</a-button>
              </a-popconfirm>
            </div>
          </template>
          <div v-else class="details-placeholder">
            <svg class="empty-icon" aria-hidden="true">
              <use :href="`${knowledgeIcons}#file-text`" />
            </svg>
            <p>{{ loading ? '正在读取文档…' : '文档信息会显示在这里。' }}</p>
          </div>
        </section>
        <section
          class="storage-panel"
          :class="{ 'storage-warning': storagePercent >= 90 }"
          aria-labelledby="storage-title"
        >
          <div class="section-heading">
            <h2 id="storage-title">知识库容量</h2>
            <svg class="icon" aria-hidden="true">
              <use :href="`${knowledgeIcons}#hard-drive`" />
            </svg>
          </div>
          <template v-if="data">
            <p class="storage-amount">
              <strong>{{ bytes(data.quota.usedBytes) }}</strong
              ><span>/ {{ bytes(data.quota.maxTotalBytes) }}</span>
            </p>
            <div
              class="storage-meter"
              role="progressbar"
              aria-label="已用存储容量"
              :aria-valuenow="Number(storagePercent.toFixed(1))"
              :aria-valuetext="`已用 ${bytes(data.quota.usedBytes)}，总容量 ${bytes(data.quota.maxTotalBytes)}`"
              :aria-valuemin="0"
              :aria-valuemax="100"
            >
              <span :style="{ width: `${storagePercent}%` }" />
            </div>
            <div class="storage-caption">
              <span>剩余 {{ bytes(storageRemaining) }}</span
              ><strong>{{ storagePercent.toFixed(1) }}%</strong>
            </div>
            <p v-if="storagePercent >= 90" class="storage-notice" role="status">
              {{
                storagePercent >= 100
                  ? '容量已满。清理旧资料后再上传。'
                  : '容量快满了。可清理不再用的资料。'
              }}
            </p>
            <div class="storage-documents">
              <span>文档数量</span
              ><span
                ><strong>{{ data.quota.documents }}</strong> /
                {{ data.quota.maxDocuments }} 份</span
              >
            </div>
          </template>
          <p v-else class="storage-placeholder">{{ error ? '容量暂不可用' : '正在读取容量…' }}</p>
        </section>
        <p class="upload-hint">
          支持 UTF-8 的 .md / .txt<span v-if="data"
            >单文件上限 {{ bytes(data.quota.maxFileBytes) }}</span
          >
        </p>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.knowledge-page {
  --kb-accent: var(--ui-success);
  --kb-accent-soft: var(--ui-success-soft);
  width: min(100%, 1280px);
  margin: 0 auto;
  padding: 16px 12px 32px;
  color: var(--ui-ink);
  background: radial-gradient(
    ellipse at 92% 0%,
    color-mix(in srgb, var(--kb-accent) 5%, transparent),
    transparent 42%
  );
}
.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 34px;
}
.eyebrow {
  display: flex;
  gap: 12px;
  margin: 0 0 18px;
  color: var(--ui-ink-faint);
  font-size: 12px;
}
h1 {
  margin: 0;
  font-size: 30px;
  line-height: 1.35;
  letter-spacing: -1px;
  font-weight: 650;
}
.page-description {
  margin: 9px 0 0;
  color: var(--ui-ink-soft);
  font-size: 14px;
}
.icon {
  width: 17px;
  height: 17px;
  flex-shrink: 0;
  vertical-align: middle;
}
.upload-button,
.refresh-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
.upload-button {
  height: 42px;
  padding: 0 20px;
  border-radius: 8px;
}
.knowledge-page .upload-button:not(:disabled) {
  background: var(--kb-accent);
  border-color: var(--kb-accent);
  color: var(--ui-surface);
}
.knowledge-page .upload-button:not(:disabled):hover {
  background: color-mix(in srgb, var(--kb-accent) 86%, var(--ui-ink));
  border-color: transparent;
}
.file-picker {
  display: none;
}
.page-notice {
  margin-bottom: 18px;
}
.pending-operation {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  background: var(--ui-warning-soft);
  color: var(--ui-warning);
  border-radius: 8px;
  overflow-wrap: anywhere;
}
.knowledge-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 304px;
  align-items: start;
  gap: 30px;
}
.documents-column {
  min-width: 0;
  padding-bottom: 12px;
}
h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
}
.list-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 2px 0 20px;
}
.list-heading {
  display: flex;
  align-items: center;
  gap: 12px;
}
.document-count {
  color: var(--ui-ink-faint);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.refresh-button {
  height: 32px;
  font-size: 12px;
  background: transparent;
  border-color: var(--ui-line-strong);
}
.list-columns,
.document-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 90px 110px;
  gap: 16px;
  align-items: center;
}
.list-columns {
  padding: 12px 18px;
  font-size: 12px;
  color: var(--ui-ink-soft);
  border-block: 1px solid var(--ui-line);
  background: color-mix(in srgb, var(--ui-surface-muted) 60%, transparent);
}
.list-columns span:last-child {
  white-space: nowrap;
  text-align: right;
  padding-right: 8px;
}
.document-list {
  list-style: none;
  padding: 0;
  margin: 0;
}
.document-list li {
  position: relative;
  min-height: 90px;
  padding: 20px 18px;
  border-bottom: 1px solid var(--ui-line);
  transition: background-color 150ms cubic-bezier(0.2, 0.7, 0.3, 1);
}
.document-list li:hover {
  background: color-mix(in srgb, var(--ui-surface-hover) 55%, transparent);
}
.document-list li.selected {
  background: var(--kb-accent-soft);
}
.document-list li.selected::before {
  position: absolute;
  content: '';
  inset: 16px auto 16px 0;
  width: 3px;
  border-radius: 3px;
  background: var(--kb-accent);
}
.document-main {
  display: flex;
  align-items: flex-start;
  gap: 13px;
  min-width: 0;
}
.file-symbol {
  display: grid;
  place-items: center;
  width: 36px;
  height: 42px;
  flex-shrink: 0;
  border: 1px solid var(--ui-line);
  border-radius: 7px;
  background: var(--ui-surface);
  color: var(--kb-accent);
}
.file-symbol .icon {
  width: 20px;
  height: 20px;
}
.document-info {
  min-width: 0;
}
.document-name {
  display: block;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ui-ink);
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  text-align: left;
  overflow-wrap: anywhere;
  cursor: pointer;
}
.document-name:hover {
  color: var(--kb-accent);
}
.document-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 5px 0 0;
  color: var(--ui-ink-faint);
  font-size: 11px;
  line-height: 1.6;
  font-variant-numeric: tabular-nums;
}
.document-meta span {
  opacity: 0.65;
}
.document-note {
  margin: 8px 0 0;
  color: var(--ui-ink-soft);
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.status-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ui-ink-soft);
  font-size: 12px;
  white-space: nowrap;
}
.status-label i {
  width: 6px;
  height: 6px;
  background: currentColor;
  border-radius: 50%;
}
.status-label.ready {
  color: var(--ui-success);
}
.status-label.processing {
  color: var(--ui-warning);
}
.status-label.failed,
.failure {
  color: var(--ui-danger);
}
.document-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
}
.retry-button {
  font-size: 11px;
  padding: 0 6px;
}
.detail-button {
  display: grid;
  place-items: center;
  width: 28px;
  height: 32px;
  padding: 0;
  border: 0;
  border-radius: 5px;
  color: var(--ui-ink-soft);
  background: transparent;
  cursor: pointer;
}
.detail-button:hover {
  background: var(--ui-surface);
  color: var(--kb-accent);
}
.list-footer {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  padding: 16px 4px;
  font-size: 11px;
  color: var(--ui-ink-faint);
}
.privacy-note {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-top: 32px;
  padding: 20px 0;
  border-top: 1px solid var(--ui-line);
  color: var(--ui-ink-faint);
}
.privacy-note .icon {
  margin-top: 2px;
}
.privacy-note strong {
  color: var(--ui-ink-soft);
  font-size: 12px;
  font-weight: 500;
}
.privacy-note p {
  margin: 7px 0 0;
  font-size: 12px;
  line-height: 1.7;
}
.knowledge-sidebar {
  border-left: 1px solid var(--ui-line);
  padding-left: 26px;
  min-width: 0;
}
.document-details {
  scroll-margin-top: 80px;
  padding: 8px 0 24px;
}
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.detail-type {
  font-size: 11px;
  color: var(--ui-ink-faint);
}
.detail-file-symbol {
  display: grid;
  place-items: center;
  width: 50px;
  height: 58px;
  margin: 26px 0 16px;
  color: var(--kb-accent);
  background: linear-gradient(145deg, var(--kb-accent-soft), var(--ui-surface));
  border: 1px solid var(--ui-line);
  border-radius: 9px;
}
.detail-file-symbol svg {
  width: 27px;
  height: 27px;
}
.detail-filename {
  margin: 0 0 10px;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.document-facts {
  margin: 24px 0 0;
}
.document-facts > div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 0;
  font-size: 12px;
}
.document-facts dt {
  color: var(--ui-ink-faint);
}
.document-facts dd {
  margin: 0;
  color: var(--ui-ink);
  font-variant-numeric: tabular-nums;
}
.detail-note {
  margin: 16px 0;
  color: var(--ui-ink-soft);
  font-size: 12px;
  line-height: 1.8;
}
.detail-note.failure {
  color: var(--ui-danger);
}
.detail-actions {
  display: flex;
  gap: 10px;
  padding-top: 4px;
}
.detail-actions .ant-btn {
  flex: 1;
  font-size: 12px;
}
.detail-actions .ant-btn-dangerous:not(:disabled) {
  color: var(--ui-danger);
  border-color: var(--ui-danger);
}
.delete-description {
  max-width: 260px;
  font-size: 12px;
  line-height: 1.8;
}
.storage-panel {
  margin-top: 16px;
  padding: 20px;
  border: 1px solid #39493d;
  border-radius: 12px;
  color: #f2f5ed;
  background: radial-gradient(ellipse at 0% 0%, #344837, transparent 85%), #1d2922;
}
.storage-panel h2 {
  font-size: 13px;
  font-weight: 500;
}
.storage-panel .icon {
  color: #b6c7b8;
}
.storage-amount {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
  margin: 24px 0 16px;
  font-variant-numeric: tabular-nums;
}
.storage-amount strong {
  font-size: 21px;
  font-weight: 550;
  letter-spacing: -0.5px;
}
.storage-amount > span {
  color: #bac9bd;
  font-size: 11px;
}
.storage-meter {
  height: 22px;
  background: repeating-linear-gradient(90deg, #526256 0 3px, transparent 3px 7px);
  border-radius: 2px;
  overflow: hidden;
}
.storage-meter > span {
  display: block;
  height: 100%;
  background: repeating-linear-gradient(90deg, #b5d792 0 3px, #243329 3px 7px);
}
.storage-caption {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-top: 11px;
  color: #bdcdbf;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.storage-caption strong {
  font-weight: 500;
  color: #dbe9d3;
}
.storage-documents {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 20px;
  padding-top: 15px;
  border-top: 1px solid #415347;
  color: #bdcdbf;
  font-size: 11px;
}
.storage-documents strong {
  color: #f2f5ed;
  font-weight: 500;
}
.storage-notice {
  margin: 16px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: #ffd2b5;
}
.storage-warning .storage-meter > span {
  background: repeating-linear-gradient(90deg, #f4a882 0 3px, #243329 3px 7px);
}
.storage-placeholder {
  margin: 22px 0 0;
  color: #bdcdbf;
  font-size: 12px;
}
.upload-hint {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin: 14px 2px 0;
  color: var(--ui-ink-faint);
  font-size: 11px;
  line-height: 1.7;
}
.loading-state {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 240px;
  padding: 40px 24px;
  font-size: 13px;
  color: var(--ui-ink-soft);
  border-top: 1px solid var(--ui-line);
}
.empty-state {
  padding: 42px 24px 52px;
  border-top: 1px solid var(--ui-line);
  background: radial-gradient(ellipse at 0% 0%, var(--kb-accent-soft), transparent 75%);
}
.empty-icon {
  width: 32px;
  height: 32px;
  color: var(--ui-ink-faint);
}
.empty-state h3 {
  margin: 20px 0 12px;
  font-size: 20px;
  font-weight: 550;
}
.empty-state p {
  margin: 5px 0;
  color: var(--ui-ink-soft);
  font-size: 13px;
}
.empty-state .ant-btn {
  margin-top: 24px;
}
.details-placeholder {
  padding: 32px 0 12px;
  color: var(--ui-ink-faint);
  font-size: 12px;
}
.list-error {
  margin-bottom: 14px;
}
button:focus-visible {
  outline: 2px solid var(--kb-accent);
  outline-offset: 4px;
}
@media (max-width: 1100px) {
  .knowledge-workspace {
    grid-template-columns: minmax(0, 1fr) 270px;
    gap: 22px;
  }
  .knowledge-sidebar {
    padding-left: 20px;
  }
  .list-columns,
  .document-list li {
    grid-template-columns: minmax(0, 1fr) 80px 28px;
    gap: 12px;
  }
  .document-actions {
    flex-direction: column;
  }
  .retry-button {
    position: absolute;
    right: 56px;
    bottom: 13px;
  }
  .document-list li:has(.retry-button) {
    padding-bottom: 42px;
  }
}
@media (max-width: 800px) {
  .knowledge-page {
    padding: 12px 0 24px;
  }
  .knowledge-workspace {
    grid-template-columns: minmax(0, 1fr);
    gap: 24px;
  }
  .knowledge-sidebar {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 260px;
    gap: 0 28px;
    padding: 24px 0 0;
    border-left: 0;
    border-top: 1px solid var(--ui-line);
  }
  .document-details {
    grid-row: span 2;
  }
  .storage-panel {
    align-self: start;
    margin: 0;
  }
  .upload-hint {
    grid-column: 2;
  }
  .privacy-note {
    margin-top: 16px;
  }
}
@media (max-width: 540px) {
  .page-heading {
    align-items: flex-start;
    gap: 14px;
    margin-bottom: 28px;
  }
  .eyebrow {
    margin-bottom: 14px;
    font-size: 11px;
  }
  h1 {
    font-size: 26px;
  }
  .page-description {
    font-size: 12px;
    max-width: 180px;
    line-height: 1.7;
  }
  .upload-button {
    margin-top: 28px;
    padding: 0 12px;
    height: 38px;
    font-size: 12px;
  }
  .upload-button .icon {
    width: 14px;
    height: 14px;
  }
  .list-columns {
    padding: 10px 8px;
  }
  .list-columns,
  .document-list li {
    grid-template-columns: minmax(0, 1fr) 74px 24px;
    gap: 8px;
  }
  .document-list li {
    padding: 18px 8px 18px 12px;
  }
  .document-main {
    gap: 9px;
  }
  .file-symbol {
    display: none;
  }
  .document-name {
    font-size: 13px;
  }
  .status-label {
    font-size: 11px;
    gap: 5px;
  }
  .list-footer {
    font-size: 10px;
  }
  .knowledge-sidebar {
    display: block;
  }
  .document-details {
    padding-bottom: 28px;
  }
  .detail-file-symbol {
    margin-top: 20px;
  }
  .storage-panel {
    margin-top: 8px;
  }
  .pending-operation {
    flex-direction: column;
    align-items: flex-start;
  }
}
@media (prefers-reduced-motion: reduce) {
  .document-list li {
    transition: none;
  }
}
</style>
