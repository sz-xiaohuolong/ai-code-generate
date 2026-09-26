<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  CommentOutlined,
  ExportOutlined,
  CodeOutlined,
  CopyOutlined,
  CheckOutlined,
  RocketOutlined,
  FireOutlined,
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { buildAppDeployUrl } from '@/config/env'
import { getUserAvatar } from '@/constants/user'
import { CODE_GEN_TYPE_CONFIG, CodeGenTypeEnum } from '@/constants/codeGenType'

interface Props {
  app: API.AppVO
  featured?: boolean
  hero?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  featured: false,
  hero: false,
})

const router = useRouter()
const copied = ref(false)
const coverLoadFailed = ref(false)

watch(
  () => props.app.cover,
  () => {
    coverLoadFailed.value = false
  },
)

const codeGenTypeLabel = computed(() => {
  return CODE_GEN_TYPE_CONFIG[props.app.codeGenType as CodeGenTypeEnum]?.label || 'HTML'
})

// 确定性蓝图线框变体（0: 落地页, 1: 仪表盘, 2: 电商/作品集）
const wireframeVariant = computed(() => {
  const seed = props.app.id ? Number(props.app.id) : (props.app.appName || '').length
  return Math.abs(seed) % 3
})

// 相对时间友好格式化
const formatRelativeTime = (timeStr?: string) => {
  if (!timeStr) return '刚刚'
  try {
    const target = new Date(timeStr).getTime()
    const now = Date.now()
    const diff = Math.max(0, Math.floor((now - target) / 1000))
    if (diff < 60) return '刚刚'
    const min = Math.floor(diff / 60)
    if (min < 60) return `${min} 分钟前`
    const hr = Math.floor(min / 60)
    if (hr < 24) return `${hr} 小时前`
    const day = Math.floor(hr / 24)
    if (day < 30) return `${day} 天前`
    return timeStr.split('T')[0] || timeStr.split(' ')[0]
  } catch {
    return timeStr
  }
}

const goChat = () => {
  if (props.app.id) {
    router.push(`/app/chat/${props.app.id}`)
  }
}

const openWork = (e?: Event) => {
  if (e) e.stopPropagation()
  const deployUrl = buildAppDeployUrl(props.app.deployKey)
  if (deployUrl) {
    window.open(deployUrl, '_blank')
  } else {
    goChat()
  }
}

const copyPrompt = async (e: Event) => {
  e.stopPropagation()
  const promptText = props.app.initPrompt || props.app.appName || ''
  if (!promptText) {
    message.info('该应用暂未记录提示词')
    return
  }
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(promptText)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = promptText
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    copied.value = true
    message.success('Prompt 已复制到剪贴板')
    setTimeout(() => {
      copied.value = false
    }, 2200)
  } catch {
    message.error('复制失败，请手动选择复制')
  }
}
</script>

<template>
  <article
    class="app-card"
    :class="{ 'is-hero': hero }"
    role="article"
    :aria-label="app.appName || 'Web应用'"
  >
    <!-- 封面 / 蓝图线框视窗 -->
    <div
      class="card-viewport"
      role="link"
      tabindex="0"
      :aria-label="'进入应用 ' + (app.appName || '未命名应用')"
      @click="goChat"
      @keydown.enter="goChat"
    >
      <!-- 若有真实截图封面且未加载失败 -->
      <img
        v-if="app.cover && !coverLoadFailed"
        class="real-cover"
        :src="app.cover"
        :alt="app.appName || '应用封面'"
        loading="lazy"
        @error="coverLoadFailed = true"
      />

      <!-- 科技感 Blueprint 几何工程线框 -->
      <div v-else class="blueprint-canvas">

        <!-- 变体 0：SaaS 现代落地页 -->
        <svg
          v-if="wireframeVariant === 0"
          class="blueprint-svg"
          viewBox="0 0 320 180"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          <!-- 导航栏 -->
          <rect x="16" y="14" width="288" height="18" rx="4" fill="rgba(255,255,255,0.06)" />
          <circle cx="28" cy="23" r="3.5" fill="#3b82f6" />
          <rect x="38" y="21" width="36" height="4" rx="2" fill="rgba(255,255,255,0.3)" />
          <rect x="250" y="19" width="44" height="8" rx="3" fill="rgba(59,130,246,0.5)" />
          <!-- Hero 主视觉 -->
          <rect x="70" y="46" width="180" height="12" rx="3" fill="rgba(255,255,255,0.6)" />
          <rect x="95" y="64" width="130" height="7" rx="2" fill="rgba(255,255,255,0.25)" />
          <rect x="125" y="80" width="70" height="14" rx="4" fill="#3b82f6" />
          <!-- 三列卡片 -->
          <rect x="20" y="108" width="86" height="56" rx="6" fill="rgba(255,255,255,0.05)" stroke="rgba(255,255,255,0.1)" />
          <rect x="28" y="116" width="24" height="6" rx="2" fill="rgba(255,255,255,0.4)" />
          <rect x="28" y="128" width="60" height="4" rx="2" fill="rgba(255,255,255,0.15)" />
          <rect x="117" y="108" width="86" height="56" rx="6" fill="rgba(255,255,255,0.07)" stroke="rgba(59,130,246,0.3)" />
          <rect x="125" y="116" width="32" height="6" rx="2" fill="#60a5fa" />
          <rect x="125" y="128" width="68" height="4" rx="2" fill="rgba(255,255,255,0.2)" />
          <rect x="214" y="108" width="86" height="56" rx="6" fill="rgba(255,255,255,0.05)" stroke="rgba(255,255,255,0.1)" />
          <rect x="222" y="116" width="28" height="6" rx="2" fill="rgba(255,255,255,0.4)" />
          <rect x="222" y="128" width="56" height="4" rx="2" fill="rgba(255,255,255,0.15)" />
        </svg>

        <!-- 变体 1：数据看板与交互图表 -->
        <svg
          v-else-if="wireframeVariant === 1"
          class="blueprint-svg"
          viewBox="0 0 320 180"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          <!-- 侧边栏与顶栏 -->
          <rect x="16" y="14" width="46" height="152" rx="4" fill="rgba(255,255,255,0.06)" />
          <rect x="24" y="24" width="30" height="6" rx="2" fill="#3b82f6" />
          <rect x="24" y="38" width="22" height="4" rx="2" fill="rgba(255,255,255,0.2)" />
          <rect x="24" y="48" width="26" height="4" rx="2" fill="rgba(255,255,255,0.2)" />
          <rect x="24" y="58" width="18" height="4" rx="2" fill="rgba(255,255,255,0.2)" />
          <!-- 顶部指标卡 -->
          <rect x="70" y="14" width="72" height="34" rx="5" fill="rgba(255,255,255,0.05)" stroke="rgba(255,255,255,0.08)" />
          <rect x="78" y="22" width="24" height="4" rx="2" fill="rgba(255,255,255,0.2)" />
          <rect x="78" y="30" width="40" height="9" rx="2" fill="rgba(255,255,255,0.6)" />
          <rect x="150" y="14" width="72" height="34" rx="5" fill="rgba(255,255,255,0.05)" stroke="rgba(255,255,255,0.08)" />
          <rect x="158" y="22" width="28" height="4" rx="2" fill="rgba(255,255,255,0.2)" />
          <rect x="158" y="30" width="36" height="9" rx="2" fill="#10b981" />
          <rect x="230" y="14" width="74" height="34" rx="5" fill="rgba(255,255,255,0.05)" stroke="rgba(255,255,255,0.08)" />
          <rect x="238" y="22" width="24" height="4" rx="2" fill="rgba(255,255,255,0.2)" />
          <rect x="238" y="30" width="42" height="9" rx="2" fill="#8b5cf6" />
          <!-- 折线图与波形 -->
          <rect x="70" y="56" width="234" height="110" rx="6" fill="rgba(255,255,255,0.04)" stroke="rgba(255,255,255,0.08)" />
          <path d="M84 140 L120 115 L160 130 L200 95 L240 108 L284 76" stroke="#3b82f6" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
          <path d="M84 140 L120 115 L160 130 L200 95 L240 108 L284 76 L284 150 L84 150 Z" fill="url(#chartGrad)" opacity="0.25" />
          <defs>
            <linearGradient id="chartGrad" x1="184" y1="76" x2="184" y2="150" gradientUnits="userSpaceOnUse">
              <stop stop-color="#3b82f6" />
              <stop offset="1" stop-color="#3b82f6" stop-opacity="0" />
            </linearGradient>
          </defs>
        </svg>

        <!-- 变体 2：应用工作流与交互视口 -->
        <svg
          v-else
          class="blueprint-svg"
          viewBox="0 0 320 180"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          <!-- 顶部标签页 -->
          <rect x="18" y="14" width="284" height="24" rx="5" fill="rgba(255,255,255,0.05)" />
          <rect x="26" y="21" width="48" height="10" rx="3" fill="#3b82f6" opacity="0.8" />
          <rect x="80" y="23" width="42" height="6" rx="2" fill="rgba(255,255,255,0.2)" />
          <rect x="128" y="23" width="38" height="6" rx="2" fill="rgba(255,255,255,0.2)" />
          <!-- 左侧代码视窗 -->
          <rect x="18" y="46" width="136" height="120" rx="6" fill="rgba(0,0,0,0.3)" stroke="rgba(255,255,255,0.08)" />
          <rect x="28" y="58" width="50" height="5" rx="2" fill="#ec4899" opacity="0.8" />
          <rect x="28" y="70" width="80" height="5" rx="2" fill="#60a5fa" opacity="0.7" />
          <rect x="40" y="82" width="65" height="5" rx="2" fill="rgba(255,255,255,0.3)" />
          <rect x="40" y="94" width="90" height="5" rx="2" fill="#34d399" opacity="0.8" />
          <rect x="28" y="106" width="40" height="5" rx="2" fill="rgba(255,255,255,0.4)" />
          <!-- 右侧渲染卡片 -->
          <rect x="164" y="46" width="138" height="120" rx="6" fill="rgba(255,255,255,0.05)" stroke="rgba(59,130,246,0.3)" />
          <circle cx="233" cy="80" r="16" fill="rgba(59,130,246,0.3)" stroke="#3b82f6" stroke-width="1.5" />
          <rect x="184" y="106" width="98" height="8" rx="2" fill="rgba(255,255,255,0.6)" />
          <rect x="198" y="120" width="70" height="6" rx="2" fill="rgba(255,255,255,0.2)" />
          <rect x="206" y="136" width="54" height="14" rx="4" fill="#3b82f6" />
        </svg>
      </div>

      <!-- 顶部浮动工程标签栏 -->
      <div class="viewport-header">
        <div class="status-pill" :class="{ 'is-live': !!app.deployKey }">
          <span class="pulse-indicator" />
          <span class="status-label">{{ app.deployKey ? 'LIVE' : 'DRAFT' }}</span>
        </div>
        <div class="type-pill">
          <CodeOutlined class="type-icon" />
          <span>{{ codeGenTypeLabel }}</span>
        </div>
      </div>

      <!-- 方案 A 核心：Prompt 意图透视抽屉 (Hover Reveal) -->
      <div class="prompt-drawer" @click.stop>
        <div class="drawer-header">
          <span class="drawer-caption">GENERATION PROMPT</span>
          <span class="drawer-chip">{{ codeGenTypeLabel }}</span>
        </div>

        <p class="drawer-prompt" :title="app.initPrompt || '系统默认生成模板'">
          {{ app.initPrompt || '极简现代化 Web 交互界面，基于自然语言端到端生成。' }}
        </p>

        <div class="drawer-actions">
          <button
            type="button"
            class="drawer-btn copy-btn"
            :class="{ 'is-copied': copied }"
            :aria-label="copied ? '已复制' : '复制提示词'"
            @click="copyPrompt"
          >
            <CheckOutlined v-if="copied" class="btn-icon" />
            <CopyOutlined v-else class="btn-icon" />
            <span>{{ copied ? '已复制' : '复制 Prompt' }}</span>
          </button>
          <button
            type="button"
            class="drawer-btn run-btn"
            aria-label="立即运行预览"
            @click="openWork"
          >
            <RocketOutlined class="btn-icon" />
            <span>{{ app.deployKey ? '实时预览' : '进入生成' }}</span>
          </button>
        </div>
      </div>
    </div>

    <!-- 卡片主体内容 -->
    <div class="card-body">
      <div class="meta-row">
        <a-avatar
          :src="getUserAvatar(app.user?.userAvatar)"
          :size="hero ? 44 : 36"
          class="creator-avatar"
          :alt="app.user?.userName || '创作者头像'"
        />
        <div class="meta-content">
          <div class="title-line">
            <h3
              :title="app.appName"
              class="card-title"
              role="link"
              tabindex="0"
              @click="goChat"
              @keydown.enter="goChat"
            >
              {{ app.appName || '未命名应用' }}
            </h3>
            <span v-if="featured" class="hero-chip">
              <FireOutlined class="fire-icon" />
              精选
            </span>
          </div>
          <div class="author-line">
            <span class="author-name">
              {{ app.user?.userName || app.user?.userAccount || '创作者' }}
            </span>
            <span class="time-sep">·</span>
            <span class="created-time">{{ formatRelativeTime(app.createTime) }}</span>
          </div>
        </div>
      </div>

      <!-- 若是 Hero 模式，展示增强的 Prompt 摘录展示区 -->
      <div v-if="hero" class="hero-prompt-quote">
        <div class="quote-tag">PROMPT</div>
        <p class="quote-text">{{ app.initPrompt || '通过自然语言描述生成的精选作品。' }}</p>
      </div>

      <!-- 底部操作按钮 -->
      <div class="action-footer">
        <a-button type="default" size="small" class="footer-btn" @click="goChat">
          <template #icon><CommentOutlined /></template>
          工作台对话
        </a-button>
        <a-button
          v-if="app.deployKey"
          type="primary"
          ghost
          size="small"
          class="footer-btn preview-btn"
          @click="openWork"
        >
          <template #icon><ExportOutlined /></template>
          独立访问
        </a-button>
      </div>
    </div>
  </article>
</template>

<style scoped>
.app-card {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: var(--ui-surface);
  border: 1px solid var(--ui-line);
  border-radius: var(--ui-radius-lg);
  box-shadow: var(--ui-shadow-xs);
  cursor: pointer;
  transition:
    transform 220ms cubic-bezier(0.16, 1, 0.3, 1),
    border-color 220ms ease,
    box-shadow 220ms ease;
}

/* Linear 风格微光发光边框 */
.app-card:hover {
  border-color: rgba(59, 130, 246, 0.45);
  box-shadow:
    0 12px 24px -10px rgba(0, 0, 0, 0.12),
    0 0 0 1px rgba(59, 130, 246, 0.15);
  transform: translateY(-3px);
}

/* 视窗容器 */
.card-viewport {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 10;
  overflow: hidden;
  background: #090d16;
  border-bottom: 1px solid var(--ui-line);
}

.card-viewport:focus-visible,
.card-title:focus-visible {
  outline: 2px solid var(--ui-accent);
  outline-offset: 2px;
}

/* 真实截图 */
.real-cover {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: top center;
  display: block;
  transition: transform 320ms cubic-bezier(0.16, 1, 0.3, 1);
}

.app-card:hover .real-cover {
  transform: scale(1.04);
}

/* Blueprint 蓝图线框底图 */
.blueprint-canvas {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  padding: 12px;
  background: linear-gradient(180deg, #131b2e 0%, #0a0f1d 100%);
  overflow: hidden;
}

.blueprint-svg {
  width: 100%;
  height: 100%;
  max-width: 290px;
  object-fit: contain;
  transition: transform 300ms cubic-bezier(0.16, 1, 0.3, 1);
}

.app-card:hover .blueprint-svg {
  transform: scale(1.02);
}

/* 顶部状态浮条 */
.viewport-header {
  position: absolute;
  top: 10px;
  left: 10px;
  right: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  z-index: 2;
  pointer-events: none;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  font-family: var(--ui-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: #94a3b8;
  background: rgba(15, 23, 42, 0.75);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 9999px;
  backdrop-filter: blur(8px);
}

.pulse-indicator {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: #64748b;
}

.status-pill.is-live {
  color: #34d399;
  border-color: rgba(16, 185, 129, 0.35);
  background: rgba(6, 78, 59, 0.45);
}

.status-pill.is-live .pulse-indicator {
  background: #10b981;
  box-shadow: 0 0 8px #10b981;
  animation: pulse-glow 2s infinite cubic-bezier(0.4, 0, 0.6, 1);
}

@keyframes pulse-glow {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.55;
    transform: scale(1.25);
  }
}

.type-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  font-family: var(--ui-mono);
  font-size: 10px;
  font-weight: 600;
  color: #cbd5e1;
  background: rgba(15, 23, 42, 0.75);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 9999px;
  backdrop-filter: blur(8px);
}

.type-icon {
  font-size: 10px;
  color: #60a5fa;
}

/* ========================================================
   方案 A：Prompt 意图透视抽屉 (Hover Reveal)
   ======================================================== */
.prompt-drawer {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  padding: 14px 14px 12px;
  background: rgba(10, 15, 29, 0.88);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  z-index: 3;
  opacity: 0;
  transform: translateY(14px);
  pointer-events: none;
  transition:
    opacity 240ms cubic-bezier(0.16, 1, 0.3, 1),
    transform 240ms cubic-bezier(0.16, 1, 0.3, 1);
}

.app-card:hover .prompt-drawer {
  opacity: 1;
  transform: translateY(0);
  pointer-events: auto;
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.drawer-caption {
  font-family: var(--ui-mono);
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: #60a5fa;
}

.drawer-chip {
  padding: 1px 6px;
  font-family: var(--ui-mono);
  font-size: 9px;
  color: #94a3b8;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 4px;
}

.drawer-prompt {
  flex: 1;
  margin: 0 0 10px;
  overflow: hidden;
  font-family: var(--ui-mono);
  font-size: 11.5px;
  line-height: 1.55;
  color: #e2e8f0;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
}

.drawer-actions {
  display: flex;
  gap: 8px;
  margin-top: auto;
}

.drawer-btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 30px;
  padding: 0 10px;
  font-size: 11.5px;
  font-weight: 500;
  border-radius: var(--ui-radius-sm);
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 160ms ease;
}

.copy-btn {
  color: #e2e8f0;
  background: rgba(255, 255, 255, 0.12);
  border-color: rgba(255, 255, 255, 0.18);
}

.copy-btn:hover {
  background: rgba(255, 255, 255, 0.2);
  border-color: rgba(255, 255, 255, 0.3);
}

.copy-btn.is-copied {
  color: #34d399;
  background: rgba(16, 185, 129, 0.2);
  border-color: rgba(16, 185, 129, 0.4);
}

.run-btn {
  color: #ffffff;
  background: #2563eb;
  border-color: #3b82f6;
  box-shadow: 0 2px 6px rgba(37, 99, 235, 0.35);
}

.run-btn:hover {
  background: #1d4ed8;
  transform: translateY(-1px);
}

.btn-icon {
  font-size: 12px;
}

/* ========================================================
   卡片下半部元信息
   ======================================================== */
.card-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  padding: 14px 16px 14px;
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.creator-avatar {
  flex: none;
  border: 1px solid var(--ui-line);
}

.meta-content {
  min-width: 0;
  flex: 1;
}

.title-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-title {
  flex: 1;
  margin: 0;
  overflow: hidden;
  color: var(--ui-ink);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.015em;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  transition: color 140ms ease;
}

.card-title:hover {
  color: var(--ui-accent);
}

.hero-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 1px 6px;
  font-size: 10.5px;
  font-weight: 600;
  color: #d97706;
  background: #fef3c7;
  border: 1px solid #fde68a;
  border-radius: 4px;
  flex: none;
}

.fire-icon {
  font-size: 10px;
}

.author-line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--ui-ink-soft);
}

.author-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time-sep {
  opacity: 0.5;
}

.created-time {
  font-family: var(--ui-mono);
  font-size: 11px;
  color: var(--ui-ink-faint);
  flex: none;
}

.hero-prompt-quote {
  margin-top: 12px;
  padding: 10px 12px;
  background: var(--ui-surface-muted);
  border: 1px dashed var(--ui-line-strong);
  border-radius: var(--ui-radius-sm);
}

.quote-tag {
  font-family: var(--ui-mono);
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.05em;
  color: var(--ui-accent);
  margin-bottom: 4px;
}

.quote-text {
  margin: 0;
  font-family: var(--ui-mono);
  font-size: 12px;
  line-height: 1.5;
  color: var(--ui-ink-soft);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.action-footer {
  display: flex;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--ui-line);
}

.footer-btn {
  flex: 1;
  font-size: 12px;
}

.preview-btn {
  background: var(--ui-accent-soft);
}

/* ========================================================
   方案 C：Hero 展台模式特殊定制 (宽屏横向展现)
   ======================================================== */
.app-card.is-hero {
  border-color: rgba(59, 130, 246, 0.3);
  box-shadow: var(--ui-shadow-md);
}

@media (min-width: 960px) {
  .app-card.is-hero {
    display: grid;
    grid-template-columns: 1.25fr 1fr;
    grid-column: span 3;
    height: auto;
  }

  .app-card.is-hero .card-viewport {
    border-bottom: 0;
    border-right: 1px solid var(--ui-line);
    aspect-ratio: 16 / 9;
  }

  .app-card.is-hero .card-body {
    padding: 24px 28px;
    justify-content: center;
  }

  .app-card.is-hero .card-title {
    font-size: 18px;
    font-weight: 700;
  }

  .app-card.is-hero .hero-prompt-quote {
    margin-top: 16px;
    padding: 14px 16px;
  }

  .app-card.is-hero .quote-text {
    -webkit-line-clamp: 3;
    font-size: 13px;
  }

  .app-card.is-hero .action-footer {
    margin-top: 20px;
  }

  .app-card.is-hero .footer-btn {
    height: 34px;
    font-size: 13px;
  }
}
</style>
