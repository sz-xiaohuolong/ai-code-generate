<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { message, Modal } from 'ant-design-vue'
import {
  DesktopOutlined,
  DownloadOutlined,
  ExportOutlined,
  InfoCircleOutlined,
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
  CheckCircleFilled,
  EditOutlined,
  SyncOutlined,
  LockOutlined,
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
  stage?: 'analyzing' | 'coding' | 'done' | 'error'
  errorMessage?: string
  rawPrompt?: string
}

type ViewportMode = 'desktop' | 'tablet' | 'mobile'
type ViewMode = 'preview' | 'code'

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

// 工作台分栏与视口状态
const leftWidthPercent = ref(38)
const isDragging = ref(false)
const isLeftCollapsed = ref(false)
const workspaceRef = ref<HTMLElement | null>(null)
const viewportMode = ref<ViewportMode>('desktop')
const currentViewMode = ref<ViewMode>('preview')
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

const isOwnApp = computed(() => {
  return String(appInfo.value.userId) === String(loginUser.value.id)
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
  return {
    id: record.id,
    role: record.messageType === 'ai' ? 'ai' : 'user',
    content: record.message || '',
    createTime: record.createTime,
    stage: 'done',
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

const hasAiResponse = () => {
  return messages.value.some((item) => item.role === 'ai' && item.content.trim())
}

const showPreviewAfterGenerated = async () => {
  streamFinished = true
  generating.value = false
  previewVisible.value = true
  const lastMessage = messages.value[messages.value.length - 1]
  if (lastMessage?.role === 'ai') {
    lastMessage.stage = 'done'
  }
  closeStream()
  await loadAppInfo()
}

const appendAiContent = (content: string) => {
  const lastMessage = messages.value[messages.value.length - 1]
  if (lastMessage?.role === 'ai') {
    lastMessage.stage = 'coding'
    lastMessage.content += content
  } else {
    messages.value.push({
      role: 'ai',
      content,
      stage: 'coding',
    })
  }
  scrollToBottom()
}

const stopGeneration = () => {
  if (!generating.value) return
  closeStream()
  streamFinished = true
  generating.value = false
  const lastMessage = messages.value[messages.value.length - 1]
  if (lastMessage?.role === 'ai') {
    lastMessage.stage = 'done'
    if (!lastMessage.content) {
      lastMessage.content = '_已手动终止生成。_'
    }
  }
  message.info('已终止本次生成')
}

const handleRetry = (prompt?: string) => {
  if (!prompt || generating.value) return
  sendMessage(prompt)
}

const handleFillPrompt = (prompt?: string) => {
  if (!prompt) return
  composerRef.value?.setInputText(prompt)
  message.success('已回填提示词到输入框')
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
    stage: 'analyzing',
    rawPrompt: content,
  })
  scrollToBottom()

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

  eventSource.addEventListener('business-error', (event: MessageEvent) => {
    if (streamFinished) return
    try {
      const errorData = JSON.parse(event.data) as { message?: string }
      const errorMessage = errorData.message || '生成过程中出现错误'
      const lastMessage = messages.value[messages.value.length - 1]
      if (lastMessage?.role === 'ai') {
        lastMessage.stage = 'error'
        lastMessage.errorMessage = errorMessage
      }
      streamFinished = true
      generating.value = false
      message.error(errorMessage)
      closeStream()
    } catch (error) {
      console.error('解析 SSE 业务错误失败:', error, event.data)
      const lastMessage = messages.value[messages.value.length - 1]
      if (lastMessage?.role === 'ai') {
        lastMessage.stage = 'error'
        lastMessage.errorMessage = '服务器返回了无法解析的错误'
      }
      streamFinished = true
      generating.value = false
      message.error('服务器返回了无法解析的错误')
      closeStream()
    }
  })

  eventSource.onerror = () => {
    if (streamFinished) return
    if (hasAiResponse()) {
      showPreviewAfterGenerated()
      return
    }
    const lastMessage = messages.value[messages.value.length - 1]
    if (lastMessage?.role === 'ai') {
      lastMessage.stage = 'error'
      lastMessage.errorMessage = '生成连接异常，请重试'
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
  window.addEventListener('keydown', handleGlobalKeydown)
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
  window.removeEventListener('keydown', handleGlobalKeydown)
  window.removeEventListener('pointermove', onDrag)
  window.removeEventListener('pointerup', stopDrag)
  window.removeEventListener('pointercancel', stopDrag)
})
</script>

<template>
  <div class="chat-page">
    <!-- 顶部工作台导航条 (Stratum 级三段论高密聚合) -->
    <header class="chat-topbar">
      <!-- 段落 1: 项目标识与状态 (Identity) -->
      <div class="topbar-section section-left">
        <a-tooltip :title="isLeftCollapsed ? '展开对话面板 (⌘\\)' : '折叠对话 (⌘\\)'">
          <button type="button" class="collapse-toggle-btn" aria-label="切换侧栏折叠" @click="toggleLeftCollapse">
            <MenuUnfoldOutlined v-if="isLeftCollapsed" />
            <MenuFoldOutlined v-else />
          </button>
        </a-tooltip>

        <div class="app-brand-info">
          <span class="app-name" :title="appInfo.appName">{{ appInfo.appName || 'AI 网页生成' }}</span>
          <span v-if="appInfo.codeGenType" class="type-badge">{{ codeGenTypeLabel }}</span>
          <span v-if="generating" class="status-indicator live">
            <span class="pulse-point" /> 构建中
          </span>
          <span v-else-if="appInfo.deployKey" class="status-indicator deployed">
            <span class="solid-point" /> 已部署
          </span>
        </div>
      </div>

      <!-- 段落 2: 视口与查看模式 Segmented 切换 (Center) -->
      <div class="topbar-section section-center">
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
        <div v-if="currentViewMode === 'preview'" class="segmented-control viewport-switcher" role="radiogroup">
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

      <!-- 段落 3: 动作操作与发布 (Actions) -->
      <div class="topbar-section section-right">
        <!-- 画布辅助快捷动作 -->
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

        <div class="topbar-divider" />

        <div class="action-btn-group">
          <button type="button" class="ghost-text-btn" @click="detailModalVisible = true">
            <InfoCircleOutlined />
            <span>详情</span>
          </button>
          <button
            type="button"
            class="ghost-text-btn"
            :disabled="downloading"
            @click="handleDownloadCode"
          >
            <SyncOutlined v-if="downloading" spin />
            <DownloadOutlined v-else />
            <span>下载代码</span>
          </button>
          <button
            type="button"
            class="primary-action-btn"
            :disabled="deploying"
            @click="handleDeploy"
          >
            <SyncOutlined v-if="deploying" spin />
            <RocketOutlined v-else />
            <span>部署上线</span>
          </button>
        </div>
      </div>
    </header>

    <!-- 工作台双栏布局 -->
    <div
      ref="workspaceRef"
      class="workspace"
      :class="{ 'is-dragging': isDragging, 'is-collapsed': isLeftCollapsed }"
    >
      <!-- 左侧：AI 对话与控制流 -->
      <section
        v-show="!isLeftCollapsed"
        class="chat-panel"
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
            v-for="(item, index) in messages"
            :key="item.id || `${item.role}-${index}`"
            class="message-row"
            :class="item.role"
          >
            <div v-if="item.role === 'ai'" class="ai-avatar-icon">AI</div>
            <div class="message-bubble" :class="{ 'is-error': item.stage === 'error' }">
              <!-- 业务异常恢复卡片 (Error Recovery Card) -->
              <div v-if="item.stage === 'error'" class="error-recovery-card">
                <div class="error-card-header">
                  <span class="error-badge">生成异常</span>
                  <span class="error-title">{{ item.errorMessage || 'AI 生成未能顺利完成' }}</span>
                </div>
                <p class="error-card-desc">后端请求中断或出现业务校验拦截，您可直接重试或修改提示词。</p>
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

              <!-- Tortuga 级流式时序时间线 (Pipeline Telemetry) -->
              <div v-else-if="item.role === 'ai' && generating && !item.content" class="tortuga-pipeline">
                <div class="pipeline-header">
                  <div class="pipeline-title">
                    <span class="live-dot-pulse" />
                    <span class="pipeline-tag">PIPELINE EXECUTION</span>
                    <span class="pipeline-hint">正在流式构建页面...</span>
                  </div>
                  <button type="button" class="stop-gen-btn" title="中断当前生成" @click="stopGeneration">
                    <span>终止</span>
                  </button>
                </div>

                <div class="pipeline-steps">
                  <div class="step-item completed">
                    <div class="step-indicator">
                      <CheckOutlined />
                    </div>
                    <div class="step-body">
                      <span class="step-name">解析自然语言需求</span>
                      <span class="step-meta">已完成语义意图拆解与工程构架分析</span>
                    </div>
                  </div>

                  <div class="step-item active">
                    <div class="step-indicator">
                      <SyncOutlined spin />
                    </div>
                    <div class="step-body">
                      <span class="step-name">合成单页代码与样式</span>
                      <span class="step-meta">实时编译 DOM 结构与响应式 CSS</span>
                    </div>
                  </div>

                  <div class="step-item pending">
                    <div class="step-indicator">
                      <span class="pending-dot" />
                    </div>
                    <div class="step-body">
                      <span class="step-name">沙盒容器热载与渲染</span>
                      <span class="step-meta">等待代码流完成自动挂载</span>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 正在流式输出代码时的遥测条 + Markdown -->
              <template v-else-if="item.role === 'ai'">
                <div v-if="generating && item.stage === 'coding'" class="stream-telemetry-bar">
                  <div class="telemetry-info">
                    <span class="telemetry-pulse" />
                    <span class="telemetry-label">STREAMING CODE</span>
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

        <AppChatComposer
          ref="composerRef"
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
      <section class="preview-panel">
        <div class="sandbox-stage">
          <!-- 拖拽期间覆盖层，防止 iframe 捕获 mouse 事件 -->
          <div v-if="isDragging" class="drag-prevent-overlay" />

          <!-- 模式 1：网站页面沙盒预览 -->
          <div
            v-if="currentViewMode === 'preview'"
            class="viewport-wrapper"
            :class="`mode-${viewportMode}`"
          >
            <div v-if="previewVisible && previewUrl" class="device-frame">
              <iframe
                ref="previewFrameRef"
                :key="previewUrl + messages.length + iframeKey"
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
              <p class="empty-desc">在左侧输入需求并发送，AI 生成的网站将实时渲染在此高精沙盒中</p>
            </div>
          </div>

          <!-- 模式 2：高阶工程代码查看器 (带行号与 Monospace 优化) -->
          <div v-else class="code-viewer-wrapper">
            <div class="code-viewer-toolbar">
              <div class="code-file-label">
                <CodeOutlined />
                <span class="file-name">{{ appInfo.appName || 'index' }}.html</span>
                <span class="file-lines">{{ codeLines.length }} 行</span>
              </div>
              <button type="button" class="copy-code-btn" @click="handleCopyCode">
                <CheckOutlined v-if="copied" style="color: #10b981" />
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
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: var(--ui-shell);
}

/* 顶部工作台状态条 (Stratum 级工程三段式) */
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
  gap: 10px;
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
  transition: all 140ms ease;
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
  padding: 2px 7px;
  font-size: 11px;
  font-weight: 500;
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
  border: 1px solid var(--ui-accent-border);
  border-radius: 4px;
  font-family: var(--ui-mono);
}

.status-indicator {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 9999px;
  font-weight: 500;
}

.status-indicator.live {
  color: #2563eb;
  background: #eff6ff;
}

.pulse-point {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: #2563eb;
  animation: pulse-glow 1.5s infinite;
}

@keyframes pulse-glow {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.8); }
}

.status-indicator.deployed {
  color: #059669;
  background: #ecfdf5;
}

.solid-point {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: #059669;
}

/* 视口与模式切换器 (Center) */
.section-center {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
}

.segmented-control {
  display: inline-flex;
  align-items: center;
  padding: 2px;
  background: var(--ui-surface-muted);
  border: 1px solid var(--ui-line);
  border-radius: 6px;
}

.segmented-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 500;
  color: var(--ui-ink-soft);
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  transition: all 120ms ease;
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
  transition: all 120ms ease;
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
  transition: all 120ms ease;
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
  margin: 0 2px;
}

.action-btn-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ghost-text-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 500;
  color: var(--ui-ink-soft);
  background: var(--ui-surface);
  border: 1px solid var(--ui-line);
  border-radius: var(--ui-radius-sm);
  cursor: pointer;
  transition: all 120ms ease;
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
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 12px;
  font-size: 12px;
  font-weight: 600;
  color: #ffffff;
  background: var(--ui-accent);
  border: 0;
  border-radius: var(--ui-radius-sm);
  cursor: pointer;
  transition: all 120ms ease;
  box-shadow: 0 1px 2px rgba(37, 99, 235, 0.2);
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
  gap: 10px;
  margin-bottom: 14px;
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
  color: #ffffff;
  background: var(--ui-accent);
  border-radius: 6px;
}

.message-bubble {
  max-width: 86%;
  padding: 10px 14px;
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
  color: #1e3a8a;
  background: var(--ui-accent-soft);
  border-color: var(--ui-accent-border);
}

.message-bubble.is-error {
  border-color: #fecaca;
  background: #fff5f5;
}

/* 异常恢复卡片 */
.error-recovery-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.error-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.error-badge {
  padding: 1px 6px;
  font-size: 11px;
  font-weight: 600;
  color: #dc2626;
  background: rgba(239, 68, 68, 0.1);
  border-radius: 3px;
}

.error-title {
  font-size: 13px;
  font-weight: 600;
  color: #991b1b;
}

.error-card-desc {
  margin: 0;
  font-size: 12px;
  color: #7f1d1d;
  line-height: 1.5;
}

.error-card-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.error-retry-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  font-size: 11.5px;
  font-weight: 500;
  color: #ffffff;
  background: #dc2626;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  transition: all 120ms ease;
}

.error-retry-btn:hover:not(:disabled) {
  background: #b91c1c;
}

.error-retry-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.error-edit-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  font-size: 11.5px;
  font-weight: 500;
  color: #991b1b;
  background: #fee2e2;
  border: 1px solid #fca5a5;
  border-radius: 4px;
  cursor: pointer;
  transition: all 120ms ease;
}

.error-edit-btn:hover {
  background: #fecaca;
}

/* Tortuga 级流式构建流水线 */
.tortuga-pipeline {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px 2px;
  min-width: 260px;
}

.pipeline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--ui-line);
}

.pipeline-title {
  display: flex;
  align-items: center;
  gap: 7px;
}

.live-dot-pulse {
  width: 7px;
  height: 7px;
  border-radius: 9999px;
  background: #2563eb;
  animation: pulse-glow 1.5s infinite;
}

.pipeline-tag {
  font-family: var(--ui-mono);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.05em;
  color: var(--ui-accent);
}

.pipeline-hint {
  font-size: 11px;
  color: var(--ui-ink-soft);
}

.stop-gen-btn {
  padding: 1px 7px;
  font-size: 11px;
  color: #dc2626;
  background: rgba(239, 68, 68, 0.08);
  border: 1px solid rgba(239, 68, 68, 0.2);
  border-radius: 4px;
  cursor: pointer;
  transition: all 120ms ease;
}

.stop-gen-btn:hover {
  background: #dc2626;
  color: #ffffff;
}

.pipeline-steps {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.step-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
}

.step-indicator {
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 9999px;
  font-size: 11px;
  flex: none;
  margin-top: 1px;
}

.step-item.completed .step-indicator {
  background: #ecfdf5;
  color: #059669;
  border: 1px solid #a7f3d0;
}

.step-item.active .step-indicator {
  background: #eff6ff;
  color: #2563eb;
  border: 1px solid #bfdbfe;
}

.step-item.pending .step-indicator {
  background: var(--ui-surface-muted);
  border: 1px solid var(--ui-line);
}

.pending-dot {
  width: 5px;
  height: 5px;
  border-radius: 9999px;
  background: var(--ui-ink-faint);
}

.step-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.step-name {
  font-weight: 500;
  color: var(--ui-ink);
}

.step-meta {
  font-size: 11px;
  color: var(--ui-ink-faint);
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
  gap: 6px;
  font-family: var(--ui-mono);
  font-size: 10.5px;
}

.telemetry-pulse {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: #2563eb;
  animation: pulse-glow 1.5s infinite;
}

.telemetry-label {
  font-weight: 700;
  color: var(--ui-accent);
}

.telemetry-count {
  color: var(--ui-ink-soft);
}

.stop-stream-btn {
  padding: 1px 6px;
  font-size: 10.5px;
  color: #dc2626;
  background: transparent;
  border: 1px solid rgba(239, 68, 68, 0.2);
  border-radius: 3px;
  cursor: pointer;
  transition: all 120ms ease;
}

.stop-stream-btn:hover {
  background: #dc2626;
  color: #ffffff;
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
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.3);
}

.splitter-handle {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 3px;
  height: 24px;
  background: rgba(0, 0, 0, 0.25);
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
  transition: all 250ms cubic-bezier(0.16, 1, 0.3, 1);
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

.device-frame {
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: #ffffff;
  border: 1px solid var(--ui-line);
  border-radius: var(--ui-radius);
  box-shadow: var(--ui-shadow-md);
  transition: all 200ms ease;
}

.viewport-wrapper.mode-mobile .device-frame {
  border-radius: 24px;
  border: 3px solid #334155;
  box-shadow: var(--ui-shadow-xl);
}

.viewport-wrapper.mode-tablet .device-frame {
  border-radius: 16px;
  border: 2px solid #64748b;
  box-shadow: var(--ui-shadow-lg);
}

.preview-frame {
  width: 100%;
  height: 100%;
  border: 0;
  background: #ffffff;
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
  margin: 0 0 6px;
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

/* 高阶代码查看器 (Monospace + 行号) */
.code-viewer-wrapper {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  background: #0d1117;
  border-radius: var(--ui-radius);
  overflow: hidden;
  border: 1px solid #30363d;
  box-shadow: var(--ui-shadow-md);
}

.code-viewer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: #161b22;
  border-bottom: 1px solid #30363d;
}

.code-file-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--ui-mono);
  font-size: 12px;
  color: #e6edf3;
}

.file-name {
  font-weight: 500;
}

.file-lines {
  font-size: 11px;
  color: #8b949e;
  background: #21262d;
  padding: 1px 6px;
  border-radius: 4px;
}

.copy-code-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  font-size: 11px;
  color: #c9d1d9;
  background: #21262d;
  border: 1px solid #30363d;
  border-radius: 4px;
  cursor: pointer;
  transition: all 120ms ease;
}

.copy-code-btn:hover {
  background: #30363d;
  color: #ffffff;
}

.code-content-area {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 0;
  background: #0d1117;
}

.code-line-numbers {
  display: flex;
  flex-direction: column;
  flex: none;
  padding: 16px 10px 16px 14px;
  background: #090d13;
  border-right: 1px solid #21262d;
  user-select: none;
  text-align: right;
  font-family: var(--ui-mono);
  font-size: 12px;
  line-height: 1.6;
  color: #484f58;
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
  margin-bottom: 14px;
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
@media (max-width: 960px) {
  .chat-topbar {
    height: auto;
    padding: 8px 12px;
    flex-wrap: wrap;
  }

  .section-center {
    order: 3;
    width: 100%;
    justify-content: flex-start;
    margin-top: 6px;
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
