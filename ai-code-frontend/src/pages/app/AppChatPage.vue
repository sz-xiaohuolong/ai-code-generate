<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { message, Modal } from 'ant-design-vue'
import {
  DesktopOutlined,
  DownloadOutlined,
  ExportOutlined,
  InfoCircleOutlined,
  ArrowLeftOutlined,
  MoreOutlined,
  RocketOutlined,
  ReloadOutlined,
  MobileOutlined,
  TabletOutlined,
  CodeOutlined,
  EyeOutlined,
  CopyOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  CheckOutlined,
  EditOutlined,
  SyncOutlined,
} from '@ant-design/icons-vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/core'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import json from 'highlight.js/lib/languages/json'
import markdownLang from 'highlight.js/lib/languages/markdown'
import 'highlight.js/styles/github-dark.css'

hljs.registerLanguage('xml', xml)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('vue', xml)
hljs.registerLanguage('css', css)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('js', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('ts', typescript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('markdown', markdownLang)
hljs.registerLanguage('md', markdownLang)
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
import KnowledgeReferences from '@/components/KnowledgeReferences.vue'
import {
  nextKnowledgeRequest,
  readKnowledgeRequest,
  takeFirstKnowledgeRequest,
  type KnowledgeRequest,
} from '@/utils/knowledgeRequest'
import GenerationTracePanel from '@/components/GenerationTracePanel.vue'
import {
  GENERATION_MODE_LABELS,
  normalizeGenerationMode,
  isWorkflowHistoryComplete,
  WORKFLOW_STATUS_LABELS,
} from '@/utils/workflowProgress'
import {
  createVisualEditorBridge,
  formatVisualElementForPrompt,
  type VisualSelectedElement,
} from '@/utils/visualEditor'

import {
  applyTaskSnapshot,
  prepareTaskSubmission,
  isTaskActive,
  type GenerationTask,
  type TaskChatMessage,
} from '@/utils/generationTask'
import {
  startGenerationTask,
  getGenerationTask,
  getCurrentGenerationTask,
  stopGenerationTask,
  generationEventsUrl,
  TaskApiError,
} from '@/api/generationTask'
type ChatMessage = TaskChatMessage

type ViewportMode = 'desktop' | 'tablet' | 'mobile'
type ViewMode = 'preview' | 'code'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()
const { loginUser } = storeToRefs(loginUserStore)
const appId = computed(() => String(route.params.id))
const useKnowledge = ref(false)
const appInfo = ref<API.AppVO>({})
const isWorkflow = computed(() => appInfo.value.generationMode === 'workflow')
const generationModeLabel = computed(
  () => GENERATION_MODE_LABELS[normalizeGenerationMode(appInfo.value.generationMode)],
)
const messages = ref<ChatMessage[]>([])
const historyLoading = ref(false)
const hasMoreHistory = ref(false)
const generating = ref(false)
const deploying = ref(false)
const downloading = ref(false)
const deleting = ref(false)
const detailModalVisible = ref(false)
const tracePanelVisible = ref(false)
const previewVisible = ref(false)
const previewFrameRef = ref<HTMLIFrameElement | null>(null)
const editMode = ref(false)
const selectedElement = ref<VisualSelectedElement | null>(null)
let eventSource: EventSource | null = null
let streamFinished = false
const activeTask = ref<GenerationTask | null>(null)
const connectionLost = ref(false)
let recoveryTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let visualEditorBridge: ReturnType<typeof createVisualEditorBridge> | null = null

// 工作台分栏与视口状态
const leftWidthPercent = ref(36)
const compactScreen = window.matchMedia('(max-width: 959px)')
const isCompact = ref(compactScreen.matches)
const syncCompactScreen = (event: MediaQueryListEvent) => {
  isCompact.value = event.matches
}
const mobilePane = ref<'chat' | ViewMode>('chat')
const traceButton = ref<HTMLButtonElement | null>(null)
const handleTraceOpenChange = (open: boolean) => {
  if (!open) traceButton.value?.focus()
}
const selectMobilePane = (pane: 'chat' | ViewMode) => {
  mobilePane.value = pane
  if (pane !== 'chat') currentViewMode.value = pane
}
const isDragging = ref(false)
const isLeftCollapsed = ref(false)
const workspaceRef = ref<HTMLElement | null>(null)
const viewportMode = ref<ViewportMode>('desktop')
const currentViewMode = ref<ViewMode>('preview')
watch(currentViewMode, (mode) => {
  if (isCompact.value) mobilePane.value = mode
})
const iframeKey = ref(0)
const copied = ref(false)
const messagesContainerRef = ref<HTMLElement | null>(null)
const composerRef = ref<InstanceType<typeof AppChatComposer> | null>(null)

const scrollToBottom = () => {
  nextTick(() => {
    if (messagesContainerRef.value) {
      messagesContainerRef.value.scrollTop = messagesContainerRef.value.scrollHeight
    }
  })
}

const markdown = new MarkdownIt({
  html: true,
  linkify: true,
  breaks: true,
  typographer: true,
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(code, { language: lang }).value}</code></pre>`
      } catch {
        return ''
      }
    }
    try {
      return `<pre class="hljs"><code>${hljs.highlightAuto(code).value}</code></pre>`
    } catch {
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
  return CODE_GEN_TYPE_CONFIG[appInfo.value.codeGenType as CodeGenTypeEnum]?.label || '未知类型'
})

const canOperate = computed(() => {
  return (
    loginUser.value.userRole === 'admin' ||
    String(appInfo.value.userId) === String(loginUser.value.id)
  )
})

const isOwnApp = computed(() => {
  return (
    !!loginUser.value.id &&
    !!appInfo.value.userId &&
    String(appInfo.value.id) === appId.value &&
    String(appInfo.value.userId) === String(loginUser.value.id)
  )
})

const creatorName = computed(() => {
  return appInfo.value.user?.userName || appInfo.value.user?.userAccount || '未知用户'
})

const formatDateTime = (value?: string) => {
  if (!value) return '-'
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
  const failed =
    record.messageType === 'ai' &&
    /^(生成未完成：|工作流生成失败：|工作流已中断|AI 回复失败：)/.test(record.message || '')
  return {
    id: record.id,
    role: record.messageType === 'ai' ? 'ai' : 'user',
    content: record.message || '',
    createTime: record.createTime,
    stage: failed ? 'error' : 'done',
    errorMessage: failed ? record.message : undefined,
  }
}

const loadAppInfo = async () => {
  const requestedAppId = appId.value
  if (!loginUserStore.isLogin) {
    await loginUserStore.fetchLoginUser()
  }
  const res = await getAppVoById({ id: requestedAppId as unknown as number })
  if (disposed || appId.value !== requestedAppId) return
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
  const requestedAppId = appId.value
  historyLoading.value = true
  try {
    const oldestMessage = messages.value[0]
    const res = await listAppChatHistoryVoByPage({
      appId: requestedAppId as unknown as number,
      pageSize: 10,
      lastCreateTime: loadMore ? oldestMessage?.createTime : undefined,
    })
    if (disposed || appId.value !== requestedAppId) return false
    if (res.data.code === 0 && res.data.data) {
      const records = res.data.data.records || []
      const historyMessages = records.map(toChatMessage)
      messages.value = loadMore ? [...historyMessages, ...messages.value] : historyMessages
      hasMoreHistory.value = records.length === 10 && (res.data.data.totalRow || 0) > records.length
      if (!loadMore) {
        previewVisible.value = isWorkflow.value
          ? isWorkflowHistoryComplete(records[records.length - 1])
          : (res.data.data.totalRow || records.length) >= 2 &&
            historyMessages[historyMessages.length - 1]?.role === 'ai' &&
            historyMessages[historyMessages.length - 1]?.stage !== 'error'
        scrollToBottom()
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

const receiveTask = (task: GenerationTask) => {
  if (disposed || String(task.appId) !== appId.value) return
  const previous = activeTask.value
  if (previous?.id === task.id && previous.revision > task.revision) return
  activeTask.value = task
  applyTaskSnapshot(messages.value, task)
  generating.value = isTaskActive(task.status)
  previewVisible.value = task.previewAvailable
  streamFinished = !generating.value
  if (streamFinished) {
    closeStream()
    if (
      task.status === 'SUCCEEDED' &&
      (previous?.id !== task.id || previous.status !== 'SUCCEEDED')
    ) {
      iframeKey.value++
      loadAppInfo()
    }
  }
  scrollToBottom()
}

const subscribeTask = (id: string) => {
  closeStream()
  connectionLost.value = false
  eventSource = new EventSource(generationEventsUrl(id), { withCredentials: true })
  eventSource.addEventListener('task-snapshot', (event: MessageEvent) => {
    try {
      receiveTask(JSON.parse(event.data) as GenerationTask)
    } catch {
      message.error('任务状态解析失败，请刷新恢复')
    }
  })
  eventSource.onopen = () => {
    connectionLost.value = false
  }
  eventSource.onerror = () => {
    if (streamFinished || disposed) return
    connectionLost.value = true
    if (recoveryTimer) clearTimeout(recoveryTimer)
    recoveryTimer = setTimeout(async () => {
      try {
        receiveTask(await getGenerationTask(id))
      } catch (error) {
        if (error instanceof TaskApiError) closeStream()
      }
    }, 1000)
  }
}

const stopGeneration = async () => {
  if (!activeTask.value || !generating.value || activeTask.value.status === 'STOPPING') return
  activeTask.value = {
    ...activeTask.value,
    status: 'STOPPING',
    stage: '正在停止',
    message: '正在等待后台资源清理',
  }
  try {
    receiveTask(await stopGenerationTask(activeTask.value.id))
  } catch {
    message.warning('停止请求尚未确认，任务状态会继续更新')
  }
}

const handleRetry = (prompt?: string) => {
  if (!prompt || generating.value) return
  const pending = readKnowledgeRequest(localStorage.getItem(`generation:pending:${appId.value}`))
  sendMessage(prompt, pending?.prompt === prompt ? pending : undefined)
}

const handleFillPrompt = (prompt?: string) => {
  if (!prompt) return
  composerRef.value?.setInputText(prompt)
  message.success('已回填提示词到输入框')
}

const sendMessage = async (value?: string, recovery?: KnowledgeRequest) => {
  const content = value?.trim() || ''
  if (!content) {
    message.warning('请输入消息')
    return false
  }
  if (generating.value) {
    message.warning('当前应用正在生成，请等待完成或停止后重试')
    return false
  }
  const requestedAppId = appId.value
  const pendingKey = `generation:pending:${requestedAppId}`
  const pending = readKnowledgeRequest(localStorage.getItem(pendingKey))
  const submission =
    recovery || nextKnowledgeRequest(pending, content, useKnowledge.value, crypto.randomUUID())
  const requestId = submission.id
  localStorage.setItem(pendingKey, JSON.stringify(submission))
  prepareTaskSubmission(messages.value, content, requestId)
  generating.value = true
  streamFinished = false
  scrollToBottom()
  try {
    const task = await startGenerationTask(
      requestedAppId,
      content,
      requestId,
      submission.useKnowledge,
    )
    if (disposed || appId.value !== requestedAppId) return false
    localStorage.removeItem(pendingKey)
    receiveTask(task)
    if (isTaskActive(task.status)) subscribeTask(task.id)
    return true
  } catch (error) {
    if (disposed || appId.value !== requestedAppId) return false
    generating.value = false
    const last = messages.value.find(
      (item) => item.role === 'ai' && item.clientRequestId === requestId,
    )
    if (last) {
      last.stage = 'error'
      last.errorMessage = error instanceof Error ? error.message : '创建任务失败，请重试'
    }
    if (error instanceof TaskApiError) localStorage.removeItem(pendingKey)
    message.error(
      error instanceof TaskApiError ? error.message : '请求结果尚未确认，重试会复用同一请求标识',
    )
    return false
  }
}

const handleUserSendMessage = async (value: string) => {
  const prompt = buildPromptWithSelectedElement(value)
  if (await sendMessage(prompt)) {
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
    if (isCompact.value) mobilePane.value = 'preview'
    message.info('点选模式已开启：在右侧网站中点击需要调整的元素')
  }
}

const handlePreviewLoad = () => {
  if (editMode.value) {
    visualEditorBridge?.refresh()
  }
}

const refreshPreview = () => {
  iframeKey.value++
  message.info('已刷新预览')
}

const openPreview = () => {
  if (previewUrl.value) {
    window.open(previewUrl.value, '_blank', 'noopener,noreferrer')
  }
}

// 提取最新生成的代码
const latestGeneratedCode = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const msg = messages.value[i]
    if (msg.role === 'ai' && msg.content) {
      const match = msg.content.match(/```(?:[a-zA-Z]*)\n([\s\S]*?)```/)
      if (match && match[1]) {
        return match[1].trim()
      }
      return msg.content.trim()
    }
  }
  return '// 尚未生成代码，请在左侧输入需求启动 AI 生成'
})

const codeLines = computed(() => {
  return (latestGeneratedCode.value || '').split('\n')
})

const handleCopyCode = async () => {
  try {
    await navigator.clipboard.writeText(latestGeneratedCode.value)
    copied.value = true
    message.success('代码已复制到剪贴板')
    setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch {
    message.error('复制失败，请手动选取复制')
  }
}

// 拖拽分栏逻辑与键盘控制
const startDrag = (e: PointerEvent) => {
  if (isLeftCollapsed.value) return
  isDragging.value = true
  const target = e.currentTarget as HTMLElement
  if (target?.setPointerCapture) {
    try {
      target.setPointerCapture(e.pointerId)
    } catch {
      // ignore
    }
  }
  window.addEventListener('pointermove', onDrag)
  window.addEventListener('pointerup', stopDrag)
  window.addEventListener('pointercancel', stopDrag)
}

const onDrag = (e: PointerEvent) => {
  if (!isDragging.value || !workspaceRef.value) return
  const rect = workspaceRef.value.getBoundingClientRect()
  const offsetX = e.clientX - rect.left
  const percent = (offsetX / rect.width) * 100
  if (percent >= 22 && percent <= 78) {
    leftWidthPercent.value = Math.round(percent * 10) / 10
  }
}

const stopDrag = () => {
  if (!isDragging.value) return
  isDragging.value = false
  window.removeEventListener('pointermove', onDrag)
  window.removeEventListener('pointerup', stopDrag)
  window.removeEventListener('pointercancel', stopDrag)
}

const onSplitterKeydown = (e: KeyboardEvent) => {
  const step = 2
  if (e.key === 'ArrowLeft') {
    e.preventDefault()
    leftWidthPercent.value = Math.max(22, Math.round((leftWidthPercent.value - step) * 10) / 10)
  } else if (e.key === 'ArrowRight') {
    e.preventDefault()
    leftWidthPercent.value = Math.min(78, Math.round((leftWidthPercent.value + step) * 10) / 10)
  } else if (e.key === 'Home') {
    e.preventDefault()
    leftWidthPercent.value = 22
  } else if (e.key === 'End') {
    e.preventDefault()
    leftWidthPercent.value = 78
  }
}

const toggleLeftCollapse = () => {
  isLeftCollapsed.value = !isLeftCollapsed.value
}

const handleDeploy = async () => {
  deploying.value = true
  try {
    const res = await deployApp({ appId: appId.value as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      Modal.success({
        title: '部署成功',
        content: res.data.data,
        okText: '打开作品',
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
    message.success('已打包并开始下载')
  } catch {
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

const handleGlobalKeydown = (e: KeyboardEvent) => {
  // Cmd+\ 或 Ctrl+\ : 折叠/展开侧栏
  if ((e.metaKey || e.ctrlKey) && e.key === '\\') {
    e.preventDefault()
    toggleLeftCollapse()
    return
  }
  // Cmd+E 或 Ctrl+E : 切换预览与代码模式
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'e') {
    e.preventDefault()
    currentViewMode.value = currentViewMode.value === 'preview' ? 'code' : 'preview'
    return
  }
  // Cmd+Shift+R 或 Ctrl+Shift+R : 刷新沙盒
  if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.key.toLowerCase() === 'r') {
    e.preventDefault()
    refreshPreview()
    return
  }
  // Esc : 退出点选模式
  if (e.key === 'Escape' && editMode.value) {
    e.preventDefault()
    exitEditMode()
    return
  }
}

let hydrationEpoch = 0
const hydratePage = async () => {
  const epoch = ++hydrationEpoch
  const currentPage = () => !disposed && epoch === hydrationEpoch
  useKnowledge.value = takeFirstKnowledgeRequest(appId.value) ?? false
  closeStream()
  if (recoveryTimer) clearTimeout(recoveryTimer)
  activeTask.value = null
  messages.value = []
  generating.value = false
  historyLoading.value = false
  await loadAppInfo()
  if (!currentPage()) return
  const historyLoaded = await loadHistory()
  if (!currentPage() || !isOwnApp.value) return
  try {
    const current = await getCurrentGenerationTask(appId.value)
    if (!currentPage()) return
    previewVisible.value = current.previewAvailable
    const saved = readKnowledgeRequest(localStorage.getItem(`generation:pending:${appId.value}`))
    if (current.task) {
      receiveTask(current.task)
      const pendingKey = `generation:pending:${appId.value}`
      try {
        const pending = JSON.parse(localStorage.getItem(pendingKey) || 'null')
        if (pending?.id === current.task.clientRequestId) localStorage.removeItem(pendingKey)
      } catch {
        localStorage.removeItem(pendingKey)
      }
      if (isTaskActive(current.task.status)) subscribeTask(current.task.id)
      else if (saved && saved.id !== current.task.clientRequestId)
        await sendMessage(saved.prompt, saved)
    } else if (saved) {
      await sendMessage(saved.prompt, saved)
    } else if (historyLoaded && messages.value.length === 0 && appInfo.value.initPrompt) {
      await sendMessage(appInfo.value.initPrompt)
    }
  } catch {
    message.warning('任务状态暂时无法恢复，请稍后刷新；后台任务会继续执行')
  }
}
watch(appId, () => {
  hydratePage()
})

onMounted(() => {
  visualEditorBridge = createVisualEditorBridge({
    getIframe: () => previewFrameRef.value,
    onElementSelected: (element) => {
      selectedElement.value = element
      if (isCompact.value) mobilePane.value = 'chat'
    },
    onError: (errorMessage) => {
      message.warning(errorMessage)
    },
  })
  compactScreen.addEventListener('change', syncCompactScreen)
  window.addEventListener('keydown', handleGlobalKeydown)
  hydratePage()
})

onBeforeUnmount(() => {
  disposed = true
  compactScreen.removeEventListener('change', syncCompactScreen)
  if (recoveryTimer) clearTimeout(recoveryTimer)
  closeStream()
  visualEditorBridge?.dispose()
  window.removeEventListener('keydown', handleGlobalKeydown)
  window.removeEventListener('pointermove', onDrag)
  window.removeEventListener('pointerup', stopDrag)
  window.removeEventListener('pointercancel', stopDrag)
})
</script>

<template>
  <div class="chat-page">
    <header class="chat-topbar">
      <div class="topbar-section section-left">
        <RouterLink to="/" class="back-to-apps" aria-label="返回全部作品"
          ><ArrowLeftOutlined
        /></RouterLink>
        <button
          v-if="!isCompact"
          type="button"
          class="collapse-toggle-btn"
          :aria-label="isLeftCollapsed ? '展开对话面板' : '折叠对话面板'"
          @click="toggleLeftCollapse"
        >
          <MenuUnfoldOutlined v-if="isLeftCollapsed" /><MenuFoldOutlined v-else />
        </button>
        <div class="app-brand-info">
          <h1 class="app-name" :title="appInfo.appName">{{ appInfo.appName || '应用工作台' }}</h1>
          <span
            v-if="appInfo.id"
            class="type-badge generation-mode-badge"
            title="模式在应用创建时确定"
            >{{ generationModeLabel }}</span
          >
        </div>
      </div>
      <div class="topbar-section section-right">
        <button
          v-if="isOwnApp"
          ref="traceButton"
          type="button"
          class="ghost-text-btn trace-trigger"
          :aria-expanded="tracePanelVisible"
          aria-controls="generation-trace-panel"
          @click="tracePanelVisible = true"
        >
          生成记录
        </button>
        <a-dropdown :trigger="['click']" placement="bottomRight">
          <button type="button" class="ghost-icon-btn more-actions" aria-label="更多应用操作">
            <MoreOutlined />
          </button>
          <template #overlay
            ><a-menu>
              <a-menu-item key="details" @click="detailModalVisible = true"
                ><InfoCircleOutlined /> 应用详情</a-menu-item
              >
              <a-menu-item key="download" :disabled="downloading" @click="handleDownloadCode"
                ><DownloadOutlined /> {{ downloading ? '正在打包…' : '下载代码' }}</a-menu-item
              >
            </a-menu></template
          >
        </a-dropdown>
        <button
          type="button"
          class="primary-action-btn"
          :disabled="deploying"
          @click="handleDeploy"
        >
          <SyncOutlined v-if="deploying" spin /><RocketOutlined v-else /><span>{{
            deploying ? '正在部署' : '部署上线'
          }}</span>
        </button>
      </div>
    </header>
    <nav v-if="isCompact" class="mobile-workspace-tabs" aria-label="工作区视图">
      <button
        v-for="pane in ['chat', 'preview', 'code'] as const"
        :key="pane"
        type="button"
        :aria-pressed="mobilePane === pane"
        @click="selectMobilePane(pane)"
      >
        {{ pane === 'chat' ? '对话' : pane === 'preview' ? '预览' : '代码' }}
      </button>
    </nav>
    <a-drawer
      v-if="isOwnApp"
      v-model:open="tracePanelVisible"
      title="生成记录"
      width="min(520px, 100%)"
      root-class-name="trace-drawer"
      destroy-on-close
      @after-open-change="handleTraceOpenChange"
    >
      <GenerationTracePanel
        v-if="tracePanelVisible"
        id="generation-trace-panel"
        embedded
        :app-id="appId"
        :task-id="activeTask?.id"
        :task-active="generating"
        @close="tracePanelVisible = false"
      />
    </a-drawer>
    <div v-if="generating" class="generation-task-status" role="status">
      <SyncOutlined spin />
      <strong>{{ activeTask?.stage || '正在创建任务' }}</strong>
      <span>{{
        connectionLost ? '连接恢复中，后台任务继续执行' : activeTask?.message || '正在确认任务'
      }}</span>
      <span v-if="previewVisible">预览保留上次成功版本</span>
      <button
        type="button"
        class="stop-stream-btn"
        :disabled="!activeTask || activeTask.status === 'STOPPING'"
        @click="stopGeneration"
      >
        停止生成
      </button>
    </div>

    <!-- 工作台双栏布局 -->
    <div
      ref="workspaceRef"
      class="workspace"
      :class="{ 'is-dragging': isDragging, 'is-collapsed': isLeftCollapsed }"
    >
      <!-- 左侧：AI 对话与控制流 -->
      <section
        v-show="isCompact || !isLeftCollapsed"
        class="chat-panel"
        :class="{ 'mobile-hidden': mobilePane !== 'chat' }"
        :style="{ width: `${leftWidthPercent}%` }"
      >
        <div class="panel-header">
          <div class="header-title">
            <span>对话迭代</span>
            <span class="meta-tag">{{ messages.length }} 条记录</span>
          </div>
          <a-button
            v-if="hasMoreHistory"
            type="link"
            size="small"
            :loading="historyLoading"
            class="history-btn"
            @click="loadHistory(true)"
          >
            加载更早对话
          </a-button>
        </div>

        <div ref="messagesContainerRef" class="messages">
          <div
            v-if="historyLoading && !messages.length"
            class="chat-loading"
            role="status"
            aria-label="正在加载对话"
          >
            <a-skeleton active :paragraph="{ rows: 5 }" />
          </div>
          <div
            v-for="(item, index) in messages"
            :key="item.id || `${item.role}-${index}`"
            class="message-row"
            :class="item.role"
          >
            <div v-if="item.role === 'ai'" class="ai-avatar-icon">AI</div>
            <div class="message-bubble" :class="{ 'is-error': item.stage === 'error' }">
              <div
                v-if="isWorkflow && item.workflowSteps?.length"
                class="workflow-progress"
                aria-live="polite"
              >
                <div class="workflow-progress-title">
                  <span>工作流进度</span>
                  <button
                    v-if="generating && index === messages.length - 1"
                    type="button"
                    class="stop-gen-btn"
                    @click="stopGeneration"
                  >
                    终止
                  </button>
                </div>
                <div
                  v-for="step in item.workflowSteps"
                  :key="step.id"
                  class="workflow-step"
                  :class="step.status"
                >
                  <SyncOutlined v-if="step.status === 'running'" spin />
                  <CheckOutlined v-else-if="step.status === 'completed'" />
                  <span v-else class="workflow-step-mark">{{
                    step.status === 'failed' ? '!' : '·'
                  }}</span>
                  <div class="workflow-step-body">
                    <span
                      >{{ step.name
                      }}<small v-if="step.retryCount">
                        · 已修复 {{ step.retryCount }} 次</small
                      ></span
                    >
                    <p>{{ step.message }}</p>
                  </div>
                  <span class="workflow-step-status">{{
                    WORKFLOW_STATUS_LABELS[step.status]
                  }}</span>
                </div>
              </div>
              <!-- 业务异常恢复卡片 (Error Recovery Card) -->
              <div v-if="item.stage === 'error'" class="error-recovery-card">
                <div class="error-card-header">
                  <span class="error-badge">{{
                    item.taskStatus === 'CANCELLED'
                      ? '已停止'
                      : item.taskStatus === 'TIMED_OUT'
                        ? '已超时'
                        : item.taskStatus === 'INTERRUPTED'
                          ? '服务中断'
                          : '生成异常'
                  }}</span>
                  <span class="error-title">{{ item.errorMessage || 'AI 生成未能顺利完成' }}</span>
                </div>
                <p class="error-card-desc">
                  您可直接重试或修改需求；本次任务不会覆盖此前的成功版本。
                </p>
                <div class="error-card-actions">
                  <button
                    v-if="item.rawPrompt"
                    type="button"
                    class="error-retry-btn"
                    :disabled="generating"
                    @click="handleRetry(item.rawPrompt)"
                  >
                    <ReloadOutlined />
                    <span>一键重新生成</span>
                  </button>
                  <button
                    v-if="item.rawPrompt"
                    type="button"
                    class="error-edit-btn"
                    @click="handleFillPrompt(item.rawPrompt)"
                  >
                    <EditOutlined />
                    <span>回填修改提示词</span>
                  </button>
                </div>
              </div>

              <!-- 标准模式等待输出时只展示当前任务状态。 -->
              <div
                v-else-if="item.role === 'ai' && generating && !item.content && !isWorkflow"
                class="stream-telemetry-bar"
              >
                <div class="telemetry-info">
                  <SyncOutlined spin />
                  <span>{{ activeTask?.stage || '正在创建任务' }}</span>
                </div>
              </div>

              <!-- 正在流式输出代码时的遥测条 + Markdown -->
              <template v-else-if="item.role === 'ai'">
                <div v-if="generating && item.stage === 'coding'" class="stream-telemetry-bar">
                  <div class="telemetry-info">
                    <span class="telemetry-label">正在生成代码</span>
                    <span class="telemetry-count">{{ item.content.length }} 字符已接收</span>
                  </div>
                  <button type="button" class="stop-stream-btn" @click="stopGeneration">
                    终止
                  </button>
                </div>
                <div class="markdown-body" v-html="renderMarkdown(item.content)" />
              </template>

              <!-- 普通用户输入 -->
              <span v-else>{{ item.content }}</span>
            </div>
          </div>
        </div>

        <KnowledgeReferences
          v-if="isOwnApp"
          :app-id="appId"
          :refresh-key="`${activeTask?.id}:${activeTask?.status}`"
        />
        <AppChatComposer
          ref="composerRef"
          v-model:use-knowledge="useKnowledge"
          :generating="generating"
          :edit-mode="editMode"
          :selected-element="selectedElement"
          @send="handleUserSendMessage"
          @toggle-edit-mode="toggleEditMode"
          @clear-selected-element="clearSelectedElement"
        />
      </section>

      <!-- 中间：拖拽分隔手柄 -->
      <div
        v-show="!isLeftCollapsed"
        class="splitter-bar"
        role="separator"
        tabindex="0"
        aria-orientation="vertical"
        :aria-valuenow="leftWidthPercent"
        aria-valuemin="22"
        aria-valuemax="78"
        aria-label="调节对话面板与预览面板宽度"
        title="按住拖动或使用左右方向键调整分栏比例"
        @pointerdown="startDrag"
        @keydown="onSplitterKeydown"
      >
        <div class="splitter-handle" />
      </div>

      <!-- 右侧：无边框工程沙盒视口与代码查看器 (Stratum 级纯净画布) -->
      <section class="preview-panel" :class="{ 'mobile-hidden': mobilePane === 'chat' }">
        <div class="preview-toolbar">
          <!-- 段落 2: 视口与查看模式 Segmented 切换 (Center) -->
          <div class="preview-controls">
            <!-- 预览 / 代码 双模分段控制器 -->
            <div class="segmented-control mode-switcher" role="tablist">
              <button
                type="button"
                role="tab"
                :aria-selected="currentViewMode === 'preview'"
                class="segmented-btn"
                :class="{ active: currentViewMode === 'preview' }"
                title="预览沙盒 (⌘E)"
                @click="currentViewMode = 'preview'"
              >
                <EyeOutlined />
                <span>预览</span>
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="currentViewMode === 'code'"
                class="segmented-btn"
                :class="{ active: currentViewMode === 'code' }"
                title="代码查看 (⌘E)"
                @click="currentViewMode = 'code'"
              >
                <CodeOutlined />
                <span>代码</span>
              </button>
            </div>

            <!-- 响应式多端尺寸切换 (仅预览模式生效) -->
            <div
              v-if="currentViewMode === 'preview'"
              class="segmented-control viewport-switcher"
              role="radiogroup"
            >
              <button
                type="button"
                role="radio"
                :aria-checked="viewportMode === 'desktop'"
                class="segmented-icon-btn"
                :class="{ active: viewportMode === 'desktop' }"
                title="桌面端自适应"
                aria-label="桌面端自适应"
                @click="viewportMode = 'desktop'"
              >
                <DesktopOutlined />
              </button>
              <button
                type="button"
                role="radio"
                :aria-checked="viewportMode === 'tablet'"
                class="segmented-icon-btn"
                :class="{ active: viewportMode === 'tablet' }"
                title="平板端 (768px)"
                aria-label="平板端 (768px)"
                @click="viewportMode = 'tablet'"
              >
                <TabletOutlined />
              </button>
              <button
                type="button"
                role="radio"
                :aria-checked="viewportMode === 'mobile'"
                class="segmented-icon-btn"
                :class="{ active: viewportMode === 'mobile' }"
                title="手机端 (375px)"
                aria-label="手机端 (375px)"
                @click="viewportMode = 'mobile'"
              >
                <MobileOutlined />
              </button>
            </div>
          </div>

          <div v-if="currentViewMode === 'preview'" class="canvas-action-group">
            <a-tooltip title="重新加载预览 (⌘⇧R)">
              <button
                type="button"
                class="ghost-icon-btn"
                :disabled="!previewUrl"
                aria-label="重新加载预览"
                @click="refreshPreview"
              >
                <ReloadOutlined />
              </button>
            </a-tooltip>
            <a-tooltip title="在新窗口打开独立预览">
              <button
                type="button"
                class="ghost-icon-btn"
                :disabled="!previewVisible || !previewUrl"
                aria-label="在新窗口打开独立预览"
                @click="openPreview"
              >
                <ExportOutlined />
              </button>
            </a-tooltip>
          </div>
        </div>
        <div class="sandbox-stage">
          <!-- 拖拽期间覆盖层，防止 iframe 捕获 mouse 事件 -->
          <div v-if="isDragging" class="drag-prevent-overlay" />

          <!-- 模式 1：网站页面沙盒预览 -->
          <div
            v-if="currentViewMode === 'preview'"
            class="viewport-wrapper"
            :class="`mode-${viewportMode}`"
          >
            <div v-if="previewVisible && previewUrl" class="preview-surface">
              <iframe
                ref="previewFrameRef"
                :key="previewUrl + iframeKey"
                class="preview-frame"
                :src="previewUrl"
                title="生成后网站沙盒"
                @load="handlePreviewLoad"
              />
            </div>
            <div v-else class="preview-empty">
              <div class="empty-icon-wrap">
                <CodeOutlined />
              </div>
              <h3 class="empty-title">等待代码生成</h3>
              <p class="empty-desc">描述需求并发送，生成成功后在这里查看和调整应用。</p>
            </div>
          </div>

          <!-- 模式 2：高阶工程代码查看器 (带行号与 Monospace 优化) -->
          <div v-else class="code-viewer-wrapper">
            <div class="code-viewer-toolbar">
              <div class="code-file-label">
                <CodeOutlined />
                <span class="file-name">最近回复中的代码</span>
                <span class="file-lines">{{ codeLines.length }} 行</span>
              </div>
              <button type="button" class="copy-code-btn" @click="handleCopyCode">
                <CheckOutlined v-if="copied" style="color: var(--ui-success)" />
                <CopyOutlined v-else />
                <span>{{ copied ? '已复制' : '复制代码' }}</span>
              </button>
            </div>
            <div class="code-content-area">
              <div class="code-line-numbers" aria-hidden="true">
                <span v-for="lineNum in codeLines.length" :key="lineNum">{{ lineNum }}</span>
              </div>
              <div class="code-pre-wrap">
                <pre class="hljs"><code>{{ latestGeneratedCode }}</code></pre>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- 应用详情 Modal -->
    <a-modal v-model:open="detailModalVisible" title="应用详情" :footer="null">
      <div class="detail-section">
        <h3>基本信息</h3>
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
        <div class="detail-item">
          <span class="detail-label">生成模式</span>
          <span class="detail-value">{{ generationModeLabel }}（创建时确定）</span>
        </div>
        <div v-if="appInfo.deployKey" class="detail-item">
          <span class="detail-label">部署路径</span>
          <span class="detail-value mono">{{ appInfo.deployKey }}</span>
        </div>
      </div>

      <div v-if="canOperate" class="detail-section">
        <h3>管理操作</h3>
        <a-space>
          <a-button type="primary" @click="goEdit">修改信息</a-button>
          <a-popconfirm
            title="确定彻底删除该应用吗？"
            ok-text="确认删除"
            cancel-text="取消"
            @confirm="handleDelete"
          >
            <a-button danger :loading="deleting">删除应用</a-button>
          </a-popconfirm>
        </a-space>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.workflow-progress {
  margin-bottom: 16px;
  padding: 16px;
  border: 1px solid var(--ui-line);
  border-radius: 10px;
  background: var(--ui-surface);
}
.workflow-progress-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  font-size: 12px;
  font-weight: 600;
}
.workflow-step {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 0;
  font-size: 12px;
}
.workflow-step > .anticon,
.workflow-step-mark {
  flex: none;
  width: 16px;
  margin-top: 4px;
  text-align: center;
}
.workflow-step-body {
  flex: 1;
  min-width: 0;
}
.workflow-step-body p {
  margin: 4px 0 0;
  color: var(--ui-ink-faint);
  font-size: 11px;
}
.workflow-step-body small {
  font-size: 10px;
  color: var(--ui-ink-faint);
}
.workflow-step-status {
  flex: none;
  font-size: 10px;
  color: var(--ui-ink-faint);
}
.workflow-step.running > .anticon,
.workflow-step.running .workflow-step-status {
  color: var(--ui-accent);
}
.workflow-step.completed > .anticon {
  color: var(--ui-success);
}
.workflow-step.skipped .workflow-step-status,
.workflow-step.retrying .workflow-step-status {
  color: var(--ui-warning);
}
.workflow-step.failed .workflow-step-status,
.workflow-step.failed .workflow-step-mark {
  color: var(--ui-danger);
}
.generation-mode-badge {
  white-space: nowrap;
}

.generation-task-status {
  flex: none;
  margin: 8px 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--ui-accent-soft);
  color: var(--ui-accent);
  font-size: 12px;
}
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: var(--ui-shell);
}

/* 顶部工作台状态条 */
.chat-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: none;
  height: 50px;
  padding: 0 16px;
  background: var(--ui-surface);
  border-bottom: 1px solid var(--ui-line);
  z-index: 10;
  gap: 12px;
}

.topbar-section {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-left {
  flex: none;
  min-width: 0;
}

.collapse-toggle-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  color: var(--ui-ink-soft);
  background: transparent;
  border: 1px solid var(--ui-line);
  border-radius: var(--ui-radius-sm);
  cursor: pointer;
  transition:
    color 140ms ease,
    background-color 140ms ease,
    border-color 140ms ease;
}

.collapse-toggle-btn:hover {
  color: var(--ui-ink);
  background: var(--ui-surface-muted);
}

.app-brand-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.app-name {
  max-width: 220px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ui-ink);
  letter-spacing: -0.01em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.type-badge {
  padding: 4px 8px;
  font-size: 11px;
  font-weight: 500;
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
  border: 1px solid var(--ui-accent-border);
  border-radius: 4px;
  font-family: inherit;
}

/* 视口与模式切换器 (Center) */
.section-center {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.segmented-control {
  display: inline-flex;
  align-items: center;
  padding: 4px;
  background: var(--ui-surface-muted);
  border: 1px solid var(--ui-line);
  border-radius: 6px;
}

.segmented-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 8px;
  font-size: 12px;
  font-weight: 500;
  color: var(--ui-ink-soft);
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  transition:
    color 120ms ease,
    background-color 120ms ease,
    border-color 120ms ease;
}

.segmented-btn.active {
  color: var(--ui-accent);
  background: var(--ui-surface);
  box-shadow: var(--ui-shadow-xs);
  font-weight: 600;
}

.segmented-icon-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  font-size: 12.5px;
  color: var(--ui-ink-soft);
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  transition:
    color 120ms ease,
    background-color 120ms ease,
    border-color 120ms ease;
}

.segmented-icon-btn.active {
  color: var(--ui-accent);
  background: var(--ui-surface);
  box-shadow: var(--ui-shadow-xs);
}

/* 操作与发布组 (Right) */
.section-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.canvas-action-group {
  display: flex;
  align-items: center;
  gap: 4px;
}

.ghost-icon-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  font-size: 13px;
  color: var(--ui-ink-soft);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 5px;
  cursor: pointer;
  transition:
    color 120ms ease,
    background-color 120ms ease,
    border-color 120ms ease;
}

.ghost-icon-btn:hover:not(:disabled) {
  color: var(--ui-ink);
  background: var(--ui-surface-muted);
  border-color: var(--ui-line);
}

.ghost-icon-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.topbar-divider {
  width: 1px;
  height: 18px;
  background: var(--ui-line);
  margin: 0 4px;
}

.action-btn-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ghost-text-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 8px;
  font-size: 12px;
  font-weight: 500;
  color: var(--ui-ink-soft);
  background: var(--ui-surface);
  border: 1px solid var(--ui-line);
  border-radius: var(--ui-radius-sm);
  cursor: pointer;
  transition:
    color 120ms ease,
    background-color 120ms ease,
    border-color 120ms ease;
}

.ghost-text-btn:hover:not(:disabled) {
  color: var(--ui-ink);
  background: var(--ui-surface-muted);
  border-color: var(--ui-ink-faint);
}

.ghost-text-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.primary-action-btn {
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 28px;
  padding: 0 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ui-on-danger);
  background: var(--ui-accent);
  border: 0;
  border-radius: var(--ui-radius-sm);
  cursor: pointer;
  transition:
    color 120ms ease,
    background-color 120ms ease,
    border-color 120ms ease;
  box-shadow: 0 1px 2px var(--ui-accent-border);
}

.primary-action-btn:hover:not(:disabled) {
  background: var(--ui-accent-hover);
}

.primary-action-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 工作台主体 */
.workspace {
  display: flex;
  flex: 1;
  min-height: 0;
  position: relative;
  overflow: hidden;
}

.workspace.is-dragging {
  cursor: col-resize;
  user-select: none;
}

/* 左侧对话区 */
.chat-panel {
  display: flex;
  flex-direction: column;
  min-width: 280px;
  height: 100%;
  background: var(--ui-surface);
  border-right: 1px solid var(--ui-line);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: none;
  height: 40px;
  padding: 0 16px;
  background: var(--ui-surface);
  border-bottom: 1px solid var(--ui-line);
}

.header-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ui-ink);
}

.meta-tag {
  font-size: 11px;
  font-weight: 400;
  color: var(--ui-ink-faint);
}

.history-btn {
  font-size: 11px;
  padding: 0;
}

.messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
}

.message-row {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.message-row.user {
  justify-content: flex-end;
}

.ai-avatar-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 26px;
  height: 26px;
  font-size: 11px;
  font-weight: 700;
  color: var(--ui-on-danger);
  background: var(--ui-accent);
  border-radius: 6px;
}

.message-bubble {
  max-width: 86%;
  padding: 8px 16px;
  color: var(--ui-ink);
  line-height: 1.6;
  font-size: 13.5px;
  white-space: pre-wrap;
  background: var(--ui-surface-muted);
  border: 1px solid var(--ui-line);
  border-radius: var(--ui-radius);
  box-shadow: var(--ui-shadow-xs);
}

.message-row.user .message-bubble {
  color: var(--ui-ink);
  background: var(--ui-accent-soft);
  border-color: var(--ui-accent-border);
}

.message-bubble.is-error {
  border-color: var(--ui-danger-soft);
  background: var(--ui-danger-soft);
}

/* 异常恢复卡片 */
.error-recovery-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.error-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.error-badge {
  padding: 4px 8px;
  font-size: 11px;
  font-weight: 600;
  color: var(--ui-danger-fill);
  background: var(--ui-danger-soft);
  border-radius: 3px;
}

.error-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ui-danger);
}

.error-card-desc {
  margin: 0;
  font-size: 12px;
  color: var(--ui-danger);
  line-height: 1.5;
}

.error-card-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.error-retry-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--ui-on-danger);
  background: var(--ui-danger-fill);
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  transition:
    color 120ms ease,
    background-color 120ms ease,
    border-color 120ms ease;
}

.error-retry-btn:hover:not(:disabled) {
  background: var(--ui-danger-fill-hover);
}

.error-retry-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.error-edit-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--ui-danger);
  background: var(--ui-danger-soft);
  border: 1px solid var(--ui-danger);
  border-radius: 4px;
  cursor: pointer;
  transition:
    color 120ms ease,
    background-color 120ms ease,
    border-color 120ms ease;
}

.error-edit-btn:hover {
  background: var(--ui-danger-soft);
}

.stop-gen-btn {
  padding: 4px 8px;
  font-size: 11px;
  color: var(--ui-danger-fill);
  background: var(--ui-danger-soft);
  border: 1px solid var(--ui-danger);
  border-radius: 4px;
  cursor: pointer;
  transition:
    color 120ms ease,
    background-color 120ms ease,
    border-color 120ms ease;
}

.stop-gen-btn:hover {
  background: var(--ui-danger-fill);
  color: var(--ui-on-danger);
}

/* 流式传输遥测状态条 */
.stream-telemetry-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px;
  margin-bottom: 8px;
  background: var(--ui-surface-muted);
  border: 1px solid var(--ui-line);
  border-radius: 4px;
}

.telemetry-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: inherit;
  font-size: 12px;
}

.telemetry-label {
  font-weight: 700;
  color: var(--ui-accent);
}

.telemetry-count {
  color: var(--ui-ink-soft);
}

.stop-stream-btn {
  padding: 4px 8px;
  font-size: 10.5px;
  color: var(--ui-danger-fill);
  background: transparent;
  border: 1px solid var(--ui-danger);
  border-radius: 3px;
  cursor: pointer;
  transition:
    color 120ms ease,
    background-color 120ms ease,
    border-color 120ms ease;
}

.stop-stream-btn:hover {
  background: var(--ui-danger-fill);
  color: var(--ui-on-danger);
}

/* 拖拽中缝 */
.splitter-bar {
  position: relative;
  flex: none;
  width: 6px;
  cursor: col-resize;
  touch-action: none;
  background: var(--ui-line);
  transition: background 150ms ease;
  z-index: 5;
  outline: none;
}

.splitter-bar::after {
  position: absolute;
  content: '';
  top: 0;
  bottom: 0;
  left: -8px;
  right: -8px;
}

.splitter-bar:hover,
.splitter-bar:focus-visible,
.workspace.is-dragging .splitter-bar {
  background: var(--ui-accent);
}

.splitter-bar:focus-visible {
  box-shadow: 0 0 0 2px var(--ui-accent-border);
}

.splitter-handle {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 3px;
  height: 24px;
  background: var(--ui-line-strong);
  border-radius: 2px;
}

/* 右侧沙盒预览区 */
.preview-panel {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  height: 100%;
  background: var(--ui-shell);
}

/* 沙盒与代码展示区 */
.sandbox-stage {
  position: relative;
  display: flex;
  flex: 1;
  min-height: 0;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  padding: 16px;
}

.drag-prevent-overlay {
  position: absolute;
  inset: 0;
  z-index: 100;
  background: transparent;
}

.viewport-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  transition:
    color 250ms cubic-bezier(0.16, 1, 0.3, 1),
    background-color 250ms cubic-bezier(0.16, 1, 0.3, 1),
    border-color 250ms cubic-bezier(0.16, 1, 0.3, 1);
}

.viewport-wrapper.mode-desktop {
  width: 100%;
  height: 100%;
}

.viewport-wrapper.mode-tablet {
  width: 768px;
  max-width: 95%;
  height: 100%;
}

.viewport-wrapper.mode-mobile {
  width: 375px;
  max-width: 90%;
  height: 667px;
  max-height: 92%;
}

.preview-surface {
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: var(--ui-preview-paper);
  border: 1px solid var(--ui-line);
  border-radius: var(--ui-radius);
  box-shadow: var(--ui-shadow-md);
  transition:
    color 200ms ease,
    background-color 200ms ease,
    border-color 200ms ease;
}

.viewport-wrapper.mode-mobile .preview-surface {
  border-radius: 24px;
  border: 3px solid var(--ui-line-strong);
  box-shadow: var(--ui-shadow-xl);
}

.viewport-wrapper.mode-tablet .preview-surface {
  border-radius: 16px;
  border: 2px solid var(--ui-line-strong);
  box-shadow: var(--ui-shadow-lg);
}

.preview-frame {
  width: 100%;
  height: 100%;
  border: 0;
  background: var(--ui-preview-paper);
}

.preview-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 40px;
}

.empty-icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  font-size: 24px;
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
  border-radius: 14px;
  margin-bottom: 16px;
}

.empty-title {
  margin: 0 0 8px;
  font-size: 16px;
  font-weight: 600;
  color: var(--ui-ink);
}

.empty-desc {
  max-width: 340px;
  margin: 0;
  font-size: 13px;
  color: var(--ui-ink-soft);
  line-height: 1.5;
}

/* 代码查看器与行号 */
.code-viewer-wrapper {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  background: var(--ui-code-bg);
  border-radius: var(--ui-radius);
  overflow: hidden;
  border: 1px solid var(--ui-code-line);
  box-shadow: var(--ui-shadow-md);
}

.code-viewer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: var(--ui-code-toolbar);
  border-bottom: 1px solid var(--ui-code-line);
}

.code-file-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--ui-mono);
  font-size: 12px;
  color: var(--ui-code-ink);
}

.file-name {
  font-weight: 500;
}

.file-lines {
  font-size: 11px;
  color: var(--ui-code-muted);
  background: var(--ui-code-hover);
  padding: 4px 8px;
  border-radius: 4px;
}

.copy-code-btn {
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  font-size: 11px;
  color: var(--ui-code-ink);
  background: var(--ui-code-hover);
  border: 1px solid var(--ui-code-line);
  border-radius: 4px;
  cursor: pointer;
  transition:
    color 120ms ease,
    background-color 120ms ease,
    border-color 120ms ease;
}

.copy-code-btn:hover {
  background: var(--ui-code-line);
  color: var(--ui-on-danger);
}

.code-content-area {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 0;
  background: var(--ui-code-bg);
}

.code-line-numbers {
  display: flex;
  flex-direction: column;
  flex: none;
  padding: 16px 8px 16px 16px;
  background: var(--ui-code-gutter);
  border-right: 1px solid var(--ui-code-hover);
  user-select: none;
  text-align: right;
  font-family: var(--ui-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--ui-code-muted);
}

.code-pre-wrap {
  flex: 1;
  min-width: 0;
  padding: 16px;
}

.code-pre-wrap pre.hljs {
  margin: 0;
  padding: 0;
  background: transparent;
  border: 0;
  font-family: var(--ui-mono);
  font-size: 13px;
  line-height: 1.6;
}

/* 详情弹窗 */
.detail-section + .detail-section {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--ui-line);
}

.detail-section h3 {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ui-ink);
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
  margin-bottom: 8px;
  font-size: 13px;
}

.detail-label {
  color: var(--ui-ink-soft);
}

.detail-value {
  color: var(--ui-ink);
  font-weight: 500;
}

.detail-value.mono {
  font-family: var(--ui-mono);
  font-size: 12px;
}

/* 响应式断点 */
@media (max-width: 959px) {
  .chat-topbar {
    height: auto;
    padding: 8px 12px;
    flex-wrap: wrap;
  }

  .section-left {
    max-width: 100%;
  }

  .collapse-toggle-btn {
    flex-shrink: 0;
  }

  .app-brand-info {
    flex-wrap: wrap;
  }

  .type-badge {
    white-space: nowrap;
  }

  .section-center {
    order: 3;
    flex: 0 0 100%;
    width: 100%;
    justify-content: flex-start;
    margin-top: 8px;
  }

  .section-right {
    width: 100%;
    flex-wrap: wrap;
  }

  .action-btn-group {
    flex-wrap: wrap;
  }

  .workspace {
    flex-direction: column;
  }

  .chat-panel {
    width: 100% !important;
    height: 50%;
    min-height: 380px;
    border-right: 0;
    border-bottom: 1px solid var(--ui-line);
  }

  .splitter-bar {
    display: none;
  }

  .preview-panel {
    height: 50%;
    min-height: 400px;
  }
}
</style>

<style scoped>
.chat-topbar {
  height: 56px;
  padding: 0 20px;
  gap: 16px;
}
.section-left {
  flex: 1;
  overflow: hidden;
}
.app-brand-info {
  flex: 1;
  min-width: 0;
}
.app-name {
  max-width: 400px;
  margin: 0;
  font-size: 15px;
}
.back-to-apps {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  color: var(--ui-ink-soft);
  border-radius: 6px;
}
.back-to-apps:hover {
  background: var(--ui-surface-muted);
}
.type-badge {
  font-family: inherit;
  font-size: 12px;
  color: var(--ui-ink-faint);
  background: var(--ui-surface-muted);
  border: 0;
  padding: 4px 8px;
}
.ghost-text-btn,
.primary-action-btn {
  white-space: nowrap;
  height: 36px;
  font-size: 13px;
}
.primary-action-btn {
  white-space: nowrap;
  color: var(--ui-on-accent);
  padding: 0 16px;
  box-shadow: none;
}
.more-actions {
  width: 36px;
  height: 36px;
  font-size: 18px;
  border: 1px solid var(--ui-line);
}
.chat-panel {
  min-width: 340px;
  border-right: 0;
}
.panel-header {
  height: 48px;
  padding: 0 20px;
}
.header-title {
  font-size: 14px;
  font-weight: 600;
}
.meta-tag {
  font-size: 12px;
}
.messages {
  padding: 24px 20px;
}
.message-row {
  margin-bottom: 24px;
}
.message-bubble {
  min-width: 0;
  max-width: calc(100% - 38px);
  font-size: 14px;
  box-shadow: none;
  overflow-wrap: anywhere;
}
.message-row.ai .message-bubble {
  padding: 0;
  border: 0;
  background: transparent;
  flex: 1;
  white-space: normal;
}
.message-row.user .message-bubble {
  color: var(--ui-ink);
  background: var(--ui-surface-muted);
  border: 0;
  padding: 16px 16px;
  border-radius: 12px 12px 2px 12px;
}
.message-row.ai .message-bubble.is-error {
  padding: 16px;
  background: var(--ui-danger-soft);
}
.ai-avatar-icon {
  margin-top: 4px;
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
}
.error-title,
.error-card-desc,
.error-badge {
  color: var(--ui-danger);
}
.error-edit-btn {
  background: var(--ui-danger-soft);
  color: var(--ui-danger);
  border-color: var(--ui-danger);
}
.error-card-header,
.error-card-actions {
  flex-wrap: wrap;
}
.generation-task-status {
  margin: 0;
  padding: 8px 20px;
  border-radius: 0;
  background: var(--ui-accent-soft);
  color: var(--ui-ink-soft);
  border-bottom: 1px solid var(--ui-accent-border);
}
.generation-task-status .stop-stream-btn {
  margin-left: auto;
}
.stop-stream-btn {
  min-height: 32px;
  font-size: 12px;
  padding: 4px 8px;
  color: var(--ui-danger);
}
.splitter-bar {
  width: 5px;
  background: var(--ui-line-subtle);
  border-left: 1px solid var(--ui-line);
}
.splitter-handle {
  width: 2px;
  background: var(--ui-line-strong);
}
.preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  height: 48px;
  padding: 0 16px;
  flex: none;
  background: var(--ui-surface);
  border-bottom: 1px solid var(--ui-line);
}
.preview-controls {
  display: flex;
  align-items: center;
  gap: 16px;
}
.segmented-control {
  border: 0;
  padding: 4px;
}
.segmented-btn {
  height: 30px;
  padding: 0 12px;
  font-size: 13px;
}
.segmented-icon-btn,
.canvas-action-group .ghost-icon-btn {
  width: 32px;
  height: 32px;
  font-size: 14px;
}
.sandbox-stage {
  padding: 12px;
  background: var(--ui-surface-muted);
}
.preview-surface {
  box-shadow: none;
  border-radius: 6px;
}
.viewport-wrapper.mode-mobile .preview-surface,
.viewport-wrapper.mode-tablet .preview-surface {
  border: 1px solid var(--ui-line-strong);
  border-radius: 8px;
  box-shadow: none;
}
.viewport-wrapper {
  transition: none;
}
.code-viewer-wrapper {
  border-radius: 6px;
  box-shadow: none;
}
.code-line-numbers {
  font-size: 13px;
  color: var(--ui-code-muted);
}
.empty-icon-wrap {
  background: transparent;
  color: var(--ui-ink-faint);
}
.empty-title {
  font-size: 18px;
}
@media (max-width: 959px) {
  .chat-topbar {
    height: auto;
    min-height: 56px;
    padding: 8px 12px;
    gap: 8px;
    flex-wrap: wrap;
  }
  .section-left {
    flex: 1 1 100%;
  }
  .section-right {
    width: 100%;
    flex-wrap: nowrap;
    justify-content: flex-end;
    gap: 8px;
  }
  .app-brand-info {
    flex-wrap: nowrap;
  }
  .app-name {
    flex: 1;
    max-width: none;
  }
  .primary-action-btn,
  .ghost-text-btn,
  .more-actions,
  .back-to-apps {
    min-height: 40px;
  }
  .mobile-workspace-tabs {
    display: flex;
    flex: none;
    gap: 4px;
    padding: 0 12px;
    background: var(--ui-surface);
    border-bottom: 1px solid var(--ui-line);
  }
  .mobile-workspace-tabs button {
  white-space: nowrap;
    flex: 1;
    min-height: 44px;
    background: transparent;
    border: 0;
    border-bottom: 2px solid transparent;
    color: var(--ui-ink-soft);
    font-size: 14px;
    cursor: pointer;
  }
  .mobile-workspace-tabs button[aria-pressed='true'] {
    color: var(--ui-accent);
    border-bottom-color: var(--ui-accent);
    font-weight: 600;
  }
  .workspace {
    flex-direction: row;
  }
  .chat-panel,
  .preview-panel {
    height: 100%;
    min-height: 0;
    width: 100% !important;
    min-width: 0;
    border: 0;
  }
  .chat-panel.mobile-hidden,
  .preview-panel.mobile-hidden {
    display: none !important;
  }
  .splitter-bar {
    display: none;
  }
  .preview-controls .mode-switcher {
    display: none;
  }
  .preview-toolbar {
    height: 44px;
  }
  .preview-controls {
    gap: 8px;
  }
  .messages {
    padding: 16px 16px;
  }
  .panel-header {
    padding: 0 16px;
    height: 40px;
  }
  .sandbox-stage {
    padding: 8px;
  }
  .preview-empty {
    padding: 20px;
  }
  .generation-task-status {
    padding: 8px 12px;
    max-height: 140px;
    overflow: auto;
  }
}
@media (min-width: 960px) and (max-width: 1150px) {
  .viewport-switcher {
    display: none;
  }
}
</style>
