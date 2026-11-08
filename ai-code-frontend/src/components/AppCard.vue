<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  CommentOutlined,
  ExportOutlined,
  CodeOutlined,
  CopyOutlined,
  CheckOutlined,
  FileImageOutlined,
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
let copyTimer: ReturnType<typeof setTimeout> | undefined
const motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
const followPointer = (event: PointerEvent) => {
  if (event.pointerType !== 'mouse' || motionQuery.matches) return
  const card = event.currentTarget as HTMLElement
  const rect = card.getBoundingClientRect()
  card.style.setProperty('--pointer-x', `${event.clientX - rect.left}px`)
  card.style.setProperty('--pointer-y', `${event.clientY - rect.top}px`)
}
onBeforeUnmount(() => clearTimeout(copyTimer))

watch(
  () => props.app.cover,
  () => {
    coverLoadFailed.value = false
  },
)

const codeGenTypeLabel = computed(() => {
  return CODE_GEN_TYPE_CONFIG[props.app.codeGenType as CodeGenTypeEnum]?.label || '待生成'
})

// 相对时间友好格式化
const formatRelativeTime = (timeStr?: string) => {
  if (!timeStr) return '时间未知'
  try {
    const target = new Date(timeStr).getTime()
    if (!Number.isFinite(target)) return '时间未知'
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
    window.open(deployUrl, '_blank', 'noopener,noreferrer')
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
    message.success('提示词已复制到剪贴板')
    clearTimeout(copyTimer)
    copyTimer = setTimeout(() => {
      copied.value = false
    }, 2200)
  } catch {
    message.error('复制失败，请手动选择复制')
  }
}
</script>

<!-- 新增时钟来自 Iconify / Lucide，原始许可保存在 public/licenses/lucide.txt。 -->
<template>
  <article class="app-card" :class="{ 'app-card-hero': hero }" @pointermove="followPointer">
    <RouterLink
      :to="`/app/chat/${app.id}`"
      class="card-viewport"
      :aria-label="`继续编辑：${app.appName || '未命名应用'}`"
    >
      <img
        v-if="app.cover && !coverLoadFailed"
        class="real-cover"
        :src="app.cover"
        :alt="app.appName || '应用封面'"
        loading="lazy"
        @error="coverLoadFailed = true"
      />
      <div v-else class="cover-placeholder">
        <FileImageOutlined /><span>暂无预览</span><small>进入工作台查看应用</small>
      </div>
      <span class="deploy-state" :class="{ deployed: app.deployKey }"
        ><span />{{ app.deployKey ? '已部署' : '未部署' }}</span
      >
    </RouterLink>
    <div class="card-body">
      <div class="card-title-row">
        <RouterLink :to="`/app/chat/${app.id}`" class="card-title">{{
          app.appName || '未命名应用'
        }}</RouterLink
        ><span v-if="featured" class="featured-label">精选</span>
      </div>
      <div class="card-meta">
        <span><CodeOutlined /> {{ codeGenTypeLabel }}</span>
        <span class="mode-tag">{{
          app.generationMode === 'workflow' ? '工作流模式' : '标准模式'
        }}</span>
      </div>
      <p class="card-prompt" :title="app.initPrompt">{{ app.initPrompt || '暂未记录初始需求' }}</p>
      <div class="card-updated" :title="app.updateTime || app.createTime">
        <svg aria-hidden="true" width="1em" height="1em" viewBox="0 0 24 24">
          <g
            fill="none"
            stroke="currentColor"
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
          >
            <circle cx="12" cy="12" r="10" />
            <path d="M12 6v6h4" />
          </g>
        </svg>
        更新于 {{ formatRelativeTime(app.updateTime || app.createTime) }}
      </div>
      <div class="card-footer">
        <div class="creator">
          <a-avatar :src="getUserAvatar(app.user?.userAvatar)" :size="22" /><span>{{
            app.user?.userName || app.user?.userAccount || '创作者'
          }}</span>
        </div>
        <a-tooltip :title="copied ? '已复制' : '复制提示词'"
          ><button
            type="button"
            class="card-icon-button"
            :aria-label="copied ? '已复制提示词' : '复制提示词'"
            @click="copyPrompt"
          >
            <CheckOutlined v-if="copied" /><CopyOutlined v-else /></button
        ></a-tooltip>
        <a-tooltip title="进入工作台"
          ><button type="button" class="card-icon-button" aria-label="进入工作台" @click="goChat">
            <CommentOutlined /></button
        ></a-tooltip>
        <a-tooltip v-if="app.deployKey" title="打开已部署作品"
          ><button
            type="button"
            class="card-icon-button"
            aria-label="打开已部署作品"
            @click="openWork"
          >
            <ExportOutlined /></button
        ></a-tooltip>
      </div>
    </div>
  </article>
</template>

<style scoped>
.app-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--ui-surface);
  border: 1px solid var(--ui-line);
  border-radius: 16px;
  overflow: hidden;
  isolation: isolate;
  transition: border-color 0.2s;
}
.app-card::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  opacity: 0;
  background: radial-gradient(
    440px circle at var(--pointer-x, 50%) var(--pointer-y, 50%),
    #6aa5ef26,
    transparent 70%
  );
  transition: opacity 0.2s;
}
.app-card:hover {
  border-color: var(--ui-accent-border);
}
.app-card:hover::after {
  opacity: 1;
}
.card-viewport {
  position: relative;
  display: block;
  height: 194px;
  flex: none;
  overflow: hidden;
  background: var(--ui-surface-muted);
}
.app-card-hero .card-viewport {
  height: 230px;
}
.real-cover {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: top;
  transition: transform 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}
.app-card:hover .real-cover {
  transform: scale(1.025);
}
.cover-placeholder {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 8px;
  color: var(--ui-ink-faint);
  background:
    radial-gradient(ellipse at 75% 15%, var(--ui-accent-soft), transparent 80%),
    var(--ui-surface-muted);
}
.cover-placeholder > .anticon {
  font-size: 28px;
  opacity: 0.7;
}
.cover-placeholder span {
  font-size: 14px;
}
.cover-placeholder small {
  font-size: 12px;
}
.deploy-state {
  position: absolute;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  top: 16px;
  right: 16px;
  border: 1px solid #ffffff30;
  border-radius: 6px;
  padding: 5px 9px;
  background: #152237e8;
  color: #f0f4fa;
  font-size: 11px;
}
.deploy-state > span {
  width: 5px;
  height: 5px;
  background: #c3cddd;
  border-radius: 50%;
}
.deploy-state.deployed > span {
  background: #8ce1bc;
}
.card-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  padding: 20px 24px 14px;
}
.card-title-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.card-title {
  flex: 1;
  min-width: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  color: var(--ui-ink);
  font-size: 18px;
  font-weight: 600;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.app-card-hero .card-title {
  font-size: 22px;
}
.card-title:hover {
  color: var(--ui-accent);
}
.featured-label {
  font-size: 11px;
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
  padding: 4px 8px;
  border-radius: 4px;
  flex: none;
}
.card-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin-top: 12px;
  color: var(--ui-ink-soft);
  font-size: 11px;
}
.card-meta > span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.mode-tag {
  border: 1px solid var(--ui-line);
  padding: 2px 7px;
  border-radius: 4px;
}
.card-prompt {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 42px;
  margin: 14px 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--ui-ink-soft);
  overflow-wrap: anywhere;
}
.card-updated {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: auto 0 14px;
  font-size: 11px;
  color: var(--ui-ink-faint);
}
.card-footer {
  display: flex;
  align-items: center;
  gap: 4px;
  border-top: 1px solid var(--ui-line-subtle);
  padding-top: 8px;
}
.creator {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-right: auto;
  min-width: 0;
  font-size: 12px;
  color: var(--ui-ink-soft);
}
.creator span {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ui-ink-soft);
  cursor: pointer;
}
.card-icon-button:hover {
  background: var(--ui-accent-soft);
  color: var(--ui-accent);
}
@media (max-width: 960px) {
  .app-card-hero .card-viewport {
    height: 194px;
  }
  .app-card-hero .card-title {
    font-size: 18px;
  }
  .card-body {
    padding: 18px 20px 12px;
  }
}
@media (hover: none) {
  .card-icon-button {
    width: 44px;
    height: 44px;
  }
  .app-card::after {
    display: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .app-card,
  .real-cover {
    transition: none;
  }
  .app-card:hover .real-cover {
    transform: none;
  }
  .app-card::after {
    display: none;
  }
}
</style>
