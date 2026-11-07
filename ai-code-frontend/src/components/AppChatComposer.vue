<script setup lang="ts">
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ArrowUpOutlined, CloseOutlined, EditOutlined, AimOutlined } from '@ant-design/icons-vue'
import type { VisualSelectedElement } from '@/utils/visualEditor'
import KnowledgeToggle from '@/components/KnowledgeToggle.vue'

const props = defineProps<{
  generating?: boolean
  editMode?: boolean
  selectedElement?: VisualSelectedElement | null
  useKnowledge?: boolean
}>()

const emit = defineEmits<{
  send: [value: string]
  toggleEditMode: []
  clearSelectedElement: []
  'update:useKnowledge': [value: boolean]
}>()

const inputMessage = ref('')

const selectedElementText = computed(() => {
  if (!props.selectedElement) {
    return ''
  }
  const tag = props.selectedElement.tagName.toLowerCase()
  const id = props.selectedElement.id ? `#${props.selectedElement.id}` : ''
  const cls = props.selectedElement.className
    ? `.${props.selectedElement.className.split(' ')[0]}`
    : ''
  return `<${tag}${id || cls}>`
})

const setInputText = (text: string) => {
  inputMessage.value = text
}

defineExpose({
  setInputText,
})

const submit = () => {
  const content = inputMessage.value.trim()
  if (!content) {
    message.warning('请输入消息')
    return
  }
  if (props.generating) {
    message.warning('AI 正在生成中')
    return
  }
  emit('send', content)
  inputMessage.value = ''
}
</script>

<template>
  <div class="composer">
    <!-- 可视化点选模式新手引导提示 -->
    <div v-if="editMode && !selectedElement" class="edit-guide-banner">
      <div class="guide-content">
        <AimOutlined class="guide-icon" />
        <span>可视化定位已激活：直接在右侧沙盒点击目标元素锁定</span>
      </div>
      <button
        type="button"
        class="guide-close-btn"
        title="退出选择 (Esc)"
        @click="emit('toggleEditMode')"
      >
        退出
      </button>
    </div>

    <!-- 紧凑精致的选中元素胶囊 -->
    <div v-if="selectedElement" class="selected-pill">
      <div class="pill-info">
        <AimOutlined class="pill-icon" />
        <span class="pill-label">目标元素：</span>
        <code class="pill-tag">{{ selectedElementText }}</code>
        <span v-if="selectedElement.text" class="pill-preview"
          >"{{ selectedElement.text.slice(0, 24) }}"</span
        >
      </div>
      <button
        type="button"
        class="pill-close-btn"
        aria-label="取消选择"
        title="清除选定元素"
        @click="emit('clearSelectedElement')"
      >
        <CloseOutlined />
      </button>
    </div>

    <div class="input-container">
      <a-textarea
        v-model:value="inputMessage"
        aria-label="向 AI 描述修改或新需求"
        placeholder="描述你想调整的地方…"
        :rows="3"
        :maxlength="1000"
        @press-enter.ctrl="submit"
        @press-enter.meta="submit"
      />
      <KnowledgeToggle
        :model-value="!!useKnowledge"
        :disabled="generating"
        @update:model-value="emit('update:useKnowledge', $event)"
      />
      <div class="composer-actions">
        <div class="actions-left">
          <a-button
            size="small"
            :type="editMode ? 'primary' : 'default'"
            :disabled="generating"
            class="mode-btn"
            @click="emit('toggleEditMode')"
          >
            <EditOutlined />
            <span>{{ editMode ? '完成选择' : '点选修改元素' }}</span>
          </a-button>
          <span class="shortcut-tip">⌘ / Ctrl ↵ 发送</span>
        </div>
        <div class="actions-right">
          <a-tooltip title="发送消息">
            <a-button
              type="primary"
              shape="default"
              size="middle"
              :loading="generating"
              :disabled="!inputMessage.trim()"
              class="send-btn"
              aria-label="发送消息"
              @click="submit"
            >
              <span v-if="!generating">发送</span><ArrowUpOutlined v-if="!generating" />
            </a-button>
          </a-tooltip>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.composer {
  display: flex;
  flex-direction: column;
  padding: 12px 16px 16px;
  background: var(--ui-surface);
  border-top: 1px solid var(--ui-line);
}

.edit-guide-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 8px;
  margin-bottom: 8px;
  font-size: 11.5px;
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
  border: 1px solid var(--ui-accent-border);
  border-radius: var(--ui-radius-sm);
  animation: fadeIn 150ms ease;
}

.guide-content {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.guide-icon {
  font-size: 13px;
  color: var(--ui-accent);
}

.guide-close-btn {
  flex: none;
  padding: 4px 8px;
  font-size: 11px;
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
  border: 1px solid var(--ui-accent-border);
  border-radius: 4px;
  cursor: pointer;
  transition: color 120ms ease, background-color 120ms ease, border-color 120ms ease;
}

.guide-close-btn:hover {
  background: var(--ui-accent);
  color: var(--ui-on-accent);
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(-3px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.selected-pill {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 8px;
  margin-bottom: 8px;
  font-size: 12px;
  background: var(--ui-accent-soft);
  border: 1px solid var(--ui-accent-border);
  border-radius: var(--ui-radius-sm);
}

.pill-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
}

.pill-icon {
  color: var(--ui-accent);
}

.pill-label {
  color: var(--ui-ink-soft);
  font-size: 11px;
}

.pill-tag {
  padding: 4px 4px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
  border-radius: 4px;
}

.pill-preview {
  overflow: hidden;
  color: var(--ui-ink-faint);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pill-close-btn {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  color: var(--ui-ink-faint);
  background: transparent;
  border: 0;
  border-radius: 9999px;
  cursor: pointer;
  transition: color 120ms ease, background-color 120ms ease, border-color 120ms ease;
}

.pill-close-btn::after {
  position: absolute;
  content: '';
  inset: -12px;
}

.pill-close-btn:hover {
  color: var(--ui-ink);
  background: var(--ui-surface-hover);
}

.input-container {
  display: flex;
  flex-direction: column;
  padding: 8px 8px 8px;
  background: var(--ui-surface-muted);
  border: 1px solid var(--ui-line);
  border-radius: var(--ui-radius);
  transition: color 160ms ease, background-color 160ms ease, border-color 160ms ease;
}

.input-container:focus-within {
  background: var(--ui-surface);
  border-color: var(--ui-accent);
  box-shadow: 0 0 0 2px var(--ui-accent-soft);
}

.input-container :deep(.ant-input) {
  min-height: 64px;
  padding: 4px 4px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--ui-ink);
  background: transparent !important;
  border: 0 !important;
  box-shadow: none !important;
  resize: none;
}

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--ui-line);
}

.actions-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mode-btn {
  font-size: 11px;
  border-radius: 4px;
}

.shortcut-tip {
  color: var(--ui-ink-faint);
  font-size: 11px;
}

.actions-right {
  display: flex;
  align-items: center;
}

.send-btn {
  position: relative;
  width: 34px;
  height: 34px;
  box-shadow: var(--ui-shadow-xs);
}

.send-btn::after {
  position: absolute;
  content: '';
  inset: -6px;
}
</style>

<style scoped>
.composer {
  padding: 12px 16px 16px;
  border-top: 0;
}
.input-container {
  padding: 12px;
  background: var(--ui-surface);
  border-color: var(--ui-line-strong);
  border-radius: 12px;
}
.input-container :deep(.ant-input) {
  font-size: 14px;
  min-height: 66px;
}
.composer-actions {
  border-top-color: var(--ui-line);
  margin-top: 8px;
  padding-top: 8px;
}
.actions-left {
  min-width: 0;
  gap: 8px;
}
.mode-btn {
  font-size: 12px;
  height: 34px;
}
.send-btn {
  width: auto;
  height: 36px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.send-btn::after {
  display: none;
}
.shortcut-tip {
  display: none;
}
.edit-guide-banner {
  font-size: 12px;
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
  border-color: var(--ui-accent-border);
}
.guide-icon,
.guide-close-btn {
  color: var(--ui-accent);
}
@media (max-width: 959px) {
  .composer {
    padding: 8px 12px 12px;
  }
  .mode-btn,
  .send-btn {
    height: 40px;
  }
}
</style>
