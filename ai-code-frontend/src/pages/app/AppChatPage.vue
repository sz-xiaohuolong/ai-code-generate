<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { message, Modal } from 'ant-design-vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import {
  deleteApp,
  deleteAppByAdmin,
  deployApp,
  downloadAppCode,
  getAppVoById,
} from '@/api/appController'
import { listAppChatHistoryVoByPage } from '@/api/chatHistoryController'
import { useLoginUserStore } from '@/stores/loginUser'
import { buildAppPreviewUrl } from '@/config/env'
import { getUserAvatar } from '@/constants/user'
import { CODE_GEN_TYPE_CONFIG, CodeGenTypeEnum } from '@/constants/codeGenType'
import AppChatComposer from '@/components/AppChatComposer.vue'
import {
  createVisualEditorBridge,
  formatVisualElementForPrompt,
  type VisualSelectedElement,
} from '@/utils/visualEditor'

type ChatMessage = {
  role: 'user' | 'ai'
  content: string
  id?: string | number
  createTime?: string
}

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()
const { loginUser } = storeToRefs(loginUserStore)
const appId = computed(() => String(route.params.id))
const appInfo = ref<API.AppVO>({})
const messages = ref<ChatMessage[]>([])
const historyLoading = ref(false)
const hasMoreHistory = ref(false)
const generating = ref(false)
const deploying = ref(false)
const downloading = ref(false)
const deleting = ref(false)
const detailModalVisible = ref(false)
const previewVisible = ref(false)
const previewFrameRef = ref<HTMLIFrameElement | null>(null)
const editMode = ref(false)
const selectedElement = ref<VisualSelectedElement | null>(null)
let eventSource: EventSource | null = null
let streamFinished = false
let visualEditorBridge: ReturnType<typeof createVisualEditorBridge> | null = null

const markdown = new MarkdownIt({
  html: true,
  linkify: true,
  breaks: true,
  typographer: true,
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(code, { language: lang }).value}</code></pre>`
      } catch (error) {
        return ''
      }
    }
    try {
      return `<pre class="hljs"><code>${hljs.highlightAuto(code).value}</code></pre>`
    } catch (error) {
      return ''
    }
  },
})

const renderMarkdown = (content: string) => {
  return markdown.render(content || '')
}

const previewUrl = computed(() => {
  return buildAppPreviewUrl(appInfo.value.codeGenType, appInfo.value.id)
})

const codeGenTypeLabel = computed(() => {
  return CODE_GEN_TYPE_CONFIG[appInfo.value.codeGenType as CodeGenTypeEnum]?.label || '未知模式'
})

const canOperate = computed(() => {
  return (
    loginUser.value.userRole === 'admin' ||
    String(appInfo.value.userId) === String(loginUser.value.id)
  )
})

const creatorName = computed(() => {
  return appInfo.value.user?.userName || appInfo.value.user?.userAccount || '未知用户'
})

const isOwnApp = computed(() => {
  return String(appInfo.value.userId) === String(loginUser.value.id)
})

const formatDateTime = (value?: string) => {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString()
}

const getDownloadFileName = (contentDisposition?: string) => {
  if (!contentDisposition) {
    return `${appId.value}.zip`
  }
  const utf8FileName = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (utf8FileName) {
    return decodeURIComponent(utf8FileName)
  }
  const fileName = contentDisposition.match(/filename="?([^";]+)"?/i)?.[1]
  return fileName || `${appId.value}.zip`
}

const buildPromptWithSelectedElement = (content: string) => {
  if (!selectedElement.value) {
    return content
  }
  return `${content}\n\n${formatVisualElementForPrompt(selectedElement.value)}`
}

const clearSelectedElement = () => {
  selectedElement.value = null
  visualEditorBridge?.clearSelection()
}

const exitEditMode = () => {
  editMode.value = false
  clearSelectedElement()
  visualEditorBridge?.disable()
}

const toChatMessage = (record: API.ChatHistoryVO): ChatMessage => {
  return {
    id: record.id,
    role: record.messageType === 'ai' ? 'ai' : 'user',
    content: record.message || '',
    createTime: record.createTime,
  }
}

const loadAppInfo = async () => {
  if (!loginUserStore.isLogin) {
    await loginUserStore.fetchLoginUser()
  }
  const res = await getAppVoById({ id: appId.value as unknown as number })
  if (res.data.code === 0 && res.data.data) {
    appInfo.value = res.data.data
  } else {
    message.error(res.data.message || '获取应用详情失败')
  }
}

const loadHistory = async (loadMore = false) => {
  if (!appId.value || historyLoading.value) {
    return false
  }
  historyLoading.value = true
  try {
    const oldestMessage = messages.value[0]
    const res = await listAppChatHistoryVoByPage({
      appId: appId.value as unknown as number,
      pageSize: 10,
      lastCreateTime: loadMore ? oldestMessage?.createTime : undefined,
    })
    if (res.data.code === 0 && res.data.data) {
      const records = res.data.data.records || []
      const historyMessages = records.map(toChatMessage)
      messages.value = loadMore ? [...historyMessages, ...messages.value] : historyMessages
      hasMoreHistory.value = records.length === 10 && (res.data.data.totalRow || 0) > records.length
      if (!loadMore) {
        previewVisible.value = (res.data.data.totalRow || records.length) >= 2
      }
      return true
    } else {
      message.error(res.data.message || '获取对话历史失败')
      return false
    }
  } finally {
    historyLoading.value = false
  }
}

const closeStream = () => {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

const hasAiResponse = () => {
  return messages.value.some((item) => item.role === 'ai' && item.content.trim())
}

const showPreviewAfterGenerated = async () => {
  streamFinished = true
  generating.value = false
  previewVisible.value = true
  closeStream()
  await loadAppInfo()
}

const appendAiContent = (content: string) => {
  const lastMessage = messages.value[messages.value.length - 1]
  if (lastMessage?.role === 'ai') {
    lastMessage.content += content
  } else {
    messages.value.push({
      role: 'ai',
      content,
    })
  }
}

const sendMessage = (value?: string) => {
  const content = value?.trim() || ''
  if (!content) {
    message.warning('请输入消息')
    return false
  }
  if (generating.value) {
    message.warning('AI 正在生成中')
    return false
  }
  previewVisible.value = false
  messages.value.push({
    role: 'user',
    content,
  })
  messages.value.push({
    role: 'ai',
    content: '',
  })

  generating.value = true
  streamFinished = false
  closeStream()
  const url = new URL('http://localhost:8123/api/app/chat/gen/code')
  url.searchParams.set('appId', String(appId.value))
  url.searchParams.set('message', content)
  eventSource = new EventSource(url.toString(), {
    withCredentials: true,
  })

  eventSource.onmessage = (event) => {
    if (event.data === '[DONE]') {
      showPreviewAfterGenerated()
      return
    }
    try {
      const data = JSON.parse(event.data)
      if (data.d === '[DONE]') {
        showPreviewAfterGenerated()
        return
      }
      appendAiContent(data.d || '')
    } catch (error) {
      appendAiContent(event.data || '')
    }
  }

  eventSource.addEventListener('done', () => {
    showPreviewAfterGenerated()
  })

  eventSource.onerror = () => {
    if (streamFinished) {
      return
    }
    if (hasAiResponse()) {
      showPreviewAfterGenerated()
      return
    }
    generating.value = false
    closeStream()
    message.error('生成连接异常，请稍后重试')
  }
  return true
}

const handleUserSendMessage = (value: string) => {
  const prompt = buildPromptWithSelectedElement(value)
  if (sendMessage(prompt)) {
    exitEditMode()
  }
}

const toggleEditMode = async () => {
  if (editMode.value) {
    exitEditMode()
    return
  }
  if (!previewVisible.value || !previewUrl.value) {
    message.warning('请先生成并展示网站后再进入编辑模式')
    return
  }
  await nextTick()
  const enabled = visualEditorBridge?.enable()
  if (enabled) {
    editMode.value = true
    message.info('编辑模式已开启，请在右侧网站中选择元素')
  }
}

const handlePreviewLoad = () => {
  if (editMode.value) {
    visualEditorBridge?.refresh()
  }
}

const handleDeploy = async () => {
  deploying.value = true
  try {
    const res = await deployApp({ appId: appId.value as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      Modal.success({
        title: '部署成功',
        content: res.data.data,
        okText: '打开网站',
        onOk: () => {
          window.open(res.data.data, '_blank')
        },
      })
      await loadAppInfo()
    } else {
      message.error(res.data.message || '部署失败')
    }
  } finally {
    deploying.value = false
  }
}

const handleDownloadCode = async () => {
  downloading.value = true
  try {
    const res = await downloadAppCode(
      {
        appId: appId.value as unknown as number,
      },
      {
        responseType: 'blob',
        timeout: 120000,
      },
    )
    const contentType = res.headers['content-type'] || ''
    if (contentType.includes('application/json')) {
      const errorText = await res.data.text()
      const errorData = JSON.parse(errorText)
      message.error(errorData.message || '下载失败')
      return
    }
    const blob = new Blob([res.data], { type: contentType || 'application/zip' })
    const downloadUrl = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = getDownloadFileName(res.headers['content-disposition'])
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(downloadUrl)
    message.success('开始下载代码')
  } catch (error) {
    message.error('下载失败，请稍后重试')
  } finally {
    downloading.value = false
  }
}

const goEdit = () => {
  detailModalVisible.value = false
  router.push({
    path: `/app/edit/${appId.value}`,
    query: loginUser.value.userRole === 'admin' ? { admin: '1' } : undefined,
  })
}

const handleDelete = async () => {
  deleting.value = true
  try {
    const res =
      loginUser.value.userRole === 'admin'
        ? await deleteAppByAdmin({ id: appId.value as unknown as number })
        : await deleteApp({ id: appId.value as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      message.success('删除成功')
      detailModalVisible.value = false
      await router.push(loginUser.value.userRole === 'admin' ? '/admin/appManage' : '/')
    } else {
      message.error(res.data.message || '删除失败')
    }
  } finally {
    deleting.value = false
  }
}

onMounted(() => {
  visualEditorBridge = createVisualEditorBridge({
    getIframe: () => previewFrameRef.value,
    onElementSelected: (element) => {
      selectedElement.value = element
    },
    onError: (errorMessage) => {
      message.warning(errorMessage)
    },
  })
  const initPage = async () => {
    await loadAppInfo()
    const historyLoaded = await loadHistory()
    if (historyLoaded && isOwnApp.value && messages.value.length === 0 && appInfo.value.initPrompt) {
      sendMessage(appInfo.value.initPrompt)
    }
  }
  initPage()
})

onBeforeUnmount(() => {
  closeStream()
  visualEditorBridge?.dispose()
})
</script>

<template>
  <div class="chat-page">
    <div class="chat-topbar">
      <div class="app-title">
        <a-avatar>{{ appInfo.appName?.slice(0, 1) || 'A' }}</a-avatar>
        <span>{{ appInfo.appName || '应用生成' }}</span>
        <a-tag v-if="appInfo.codeGenType" color="blue">{{ codeGenTypeLabel }}</a-tag>
      </div>
      <a-space>
        <a-button @click="detailModalVisible = true">应用详情</a-button>
        <a-button :loading="downloading" @click="handleDownloadCode">下载代码</a-button>
        <a-button type="primary" :loading="deploying" @click="handleDeploy">部署</a-button>
      </a-space>
    </div>

    <div class="workspace">
      <section class="chat-panel">
        <div class="messages">
          <div class="history-actions">
            <a-button
              v-if="hasMoreHistory"
              type="link"
              size="small"
              :loading="historyLoading"
              @click="loadHistory(true)"
            >
              加载更多
            </a-button>
            <span v-else-if="messages.length" class="history-end">已展示最近对话</span>
          </div>
          <div
            v-for="(item, index) in messages"
            :key="item.id || `${item.role}-${index}`"
            class="message-row"
            :class="item.role"
          >
            <a-avatar v-if="item.role === 'ai'" size="small">AI</a-avatar>
            <div class="message-bubble">
              <a-spin v-if="item.role === 'ai' && generating && !item.content" size="small" />
              <div
                v-else-if="item.role === 'ai'"
                class="markdown-body"
                v-html="renderMarkdown(item.content)"
              />
              <span v-else>{{ item.content }}</span>
            </div>
          </div>
        </div>
        <AppChatComposer
          :generating="generating"
          :edit-mode="editMode"
          :selected-element="selectedElement"
          @send="handleUserSendMessage"
          @toggle-edit-mode="toggleEditMode"
          @clear-selected-element="clearSelectedElement"
        />
      </section>

      <section class="preview-panel">
        <iframe
          v-if="previewVisible && previewUrl"
          ref="previewFrameRef"
          :key="previewUrl + messages.length"
          class="preview-frame"
          :src="previewUrl"
          title="生成后网页展示"
          @load="handlePreviewLoad"
        />
        <a-empty v-else description="网站文件生成完成后将在这里展示" />
      </section>
    </div>

    <a-modal v-model:open="detailModalVisible" title="应用详情" :footer="null">
      <div class="detail-section">
        <h3>应用基础信息</h3>
        <div class="creator-row">
          <a-avatar :src="getUserAvatar(appInfo.user?.userAvatar)" :size="40" />
          <div>
            <div class="detail-label">创建者</div>
            <div class="detail-value">{{ creatorName }}</div>
          </div>
        </div>
        <div class="detail-item">
          <span class="detail-label">创建时间</span>
          <span class="detail-value">{{ formatDateTime(appInfo.createTime) }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">生成类型</span>
          <span class="detail-value">{{ codeGenTypeLabel }}</span>
        </div>
      </div>

      <div v-if="canOperate" class="detail-section">
        <h3>操作栏</h3>
        <a-space>
          <a-button type="primary" @click="goEdit">修改</a-button>
          <a-popconfirm
            title="确定删除该应用吗？"
            ok-text="删除"
            cancel-text="取消"
            @confirm="handleDelete"
          >
            <a-button danger :loading="deleting">删除</a-button>
          </a-popconfirm>
        </a-space>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 112px);
  min-height: 0;
  margin: -12px;
  overflow: hidden;
}

.chat-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  margin-bottom: 10px;
  padding: 0 2px;
}

.app-title {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #111827;
  font-size: 18px;
  font-weight: 700;
}

.detail-section + .detail-section {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid #edf0f5;
}

.detail-section h3 {
  margin: 0 0 16px;
  color: #111827;
  font-size: 16px;
  font-weight: 700;
}

.creator-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.detail-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.detail-label {
  color: #6b7280;
}

.detail-value {
  color: #111827;
  font-weight: 600;
}

.workspace {
  display: grid;
  grid-template-columns: minmax(320px, 2fr) minmax(0, 3fr);
  gap: 12px;
  flex: 1;
  min-height: 0;
}

.chat-panel,
.preview-panel {
  min-height: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid #edf0f5;
  border-radius: 8px;
}

.chat-panel {
  display: flex;
  flex-direction: column;
}

.messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
}

.history-actions {
  display: flex;
  justify-content: center;
  min-height: 28px;
  margin-bottom: 8px;
}

.history-end {
  color: #9ca3af;
  font-size: 12px;
}

.messages::-webkit-scrollbar {
  width: 8px;
}

.messages::-webkit-scrollbar-thumb {
  background: #c9ced6;
  border-radius: 8px;
}

.messages::-webkit-scrollbar-track {
  background: transparent;
}

.message-row {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}

.message-row.user {
  justify-content: flex-end;
}

.message-bubble {
  max-width: 82%;
  padding: 12px 14px;
  color: #1f2937;
  line-height: 1.7;
  white-space: pre-wrap;
  background: #f6f7f9;
  border-radius: 8px;
}

.message-row.user .message-bubble {
  color: #fff;
  background: #1677ff;
}

.markdown-body {
  overflow-x: auto;
}

.markdown-body :deep(p) {
  margin: 0 0 10px;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 14px 0 8px;
  color: #111827;
  font-weight: 700;
  line-height: 1.35;
}

.markdown-body :deep(h1) {
  font-size: 22px;
}

.markdown-body :deep(h2) {
  font-size: 19px;
}

.markdown-body :deep(h3) {
  font-size: 17px;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 8px 0 10px;
  padding-left: 22px;
}

.markdown-body :deep(li + li) {
  margin-top: 4px;
}

.markdown-body :deep(a) {
  color: #1677ff;
  word-break: break-all;
}

.markdown-body :deep(blockquote) {
  margin: 10px 0;
  padding: 8px 12px;
  color: #4b5563;
  background: #f8fafc;
  border-left: 3px solid #dbeafe;
}

.markdown-body :deep(:not(pre) > code) {
  padding: 2px 6px;
  color: #c026d3;
  background: #fdf2f8;
  border-radius: 4px;
  font-size: 13px;
}

.markdown-body :deep(pre.hljs) {
  margin: 12px 0;
  padding: 14px;
  overflow-x: auto;
  background: #f6f8fa;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  line-height: 1.55;
}

.markdown-body :deep(pre.hljs code) {
  padding: 0;
  color: inherit;
  background: transparent;
  border-radius: 0;
  font-size: 13px;
  font-family:
    ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New',
    monospace;
}

.markdown-body :deep(table) {
  width: 100%;
  margin: 12px 0;
  border-collapse: collapse;
  font-size: 14px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
}

.markdown-body :deep(th) {
  background: #f8fafc;
  font-weight: 700;
}

.preview-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px;
}

.preview-frame {
  width: 100%;
  height: 100%;
  background: #fff;
  border: 0;
  border-radius: 8px;
}

@media (max-width: 1100px) {
  .chat-page {
    height: auto;
    overflow: visible;
  }

  .workspace {
    grid-template-columns: 1fr;
  }

  .preview-frame {
    min-height: 520px;
  }
}
</style>
