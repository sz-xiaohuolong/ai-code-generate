<script setup lang="ts">
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ArrowUpOutlined, CloseOutlined, EditOutlined, AimOutlined } from '@ant-design/icons-vue'
import type { VisualSelectedElement } from '@/utils/visualEditor'

const props = defineProps<{
  generating?: boolean
  editMode?: boolean
  selectedElement?: VisualSelectedElement | null
}>()

const emit = defineEmits<{
  send: [value: string]
  toggleEditMode: []
  clearSelectedElement: []
}>()

const inputMessage = ref('')

const selectedElementText = computed(() => {
  if (!props.selectedElement) {
    return ''
  }
  const tag = props.selectedElement.tagName.toLowerCase()
  const id = props.selectedElement.id ? `#${props.selectedElement.id}` : ''
  const cls = props.selectedElement.className ? `.${props.selectedElement.className.split(' ')[0]}` : ''
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
        <span v-if="selectedElement.text" class="pill-preview">"{{ selectedElement.text.slice(0, 24) }}"</span>
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
        placeholder="向 AI 描述修改或新需求（例如：将顶部导航背景改为透明，调整卡片边距）..."
        :rows="3"
        :maxlength="1000"
        @press-enter.ctrl="submit"
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
          <span class="shortcut-tip">Ctrl + Enter 发送</span>
        </div>
        <div class="actions-right">
          <a-tooltip title="发送消息">
            <a-button
              type="primary"
              shape="circle"
              size="middle"
              :loading="generating"
              :disabled="!inputMessage.trim()"
              class="send-btn"
              aria-label="发送消息"
              @click="submit"
            >
              <ArrowUpOutlined />
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
  padding: 12px 16px 14px;
  background: var(--ui-surface);
  border-top: 1px solid var(--ui-line);
}

.edit-guide-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  margin-bottom: 8px;
  font-size: 11.5px;
  color: #1e40af;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: var(--ui-radius-sm);
  animation: fadeIn 150ms ease;
}

.guide-content {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.guide-icon {
  font-size: 13px;
  color: #2563eb;
}

.guide-close-btn {
  flex: none;
  padding: 1px 6px;
  font-size: 11px;
  color: #1e40af;
  background: rgba(37, 99, 235, 0.08);
  border: 1px solid #bfdbfe;
  border-radius: 4px;
  cursor: pointer;
  transition: all 120ms ease;
}

.guide-close-btn:hover {
  background: #2563eb;
  color: #ffffff;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(-3px); }
  to { opacity: 1; transform: translateY(0); }
}

.selected-pill {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 10px;
  margin-bottom: 8px;
  font-size: 12px;
  background: var(--ui-accent-soft);
  border: 1px solid var(--ui-accent-border);
  border-radius: var(--ui-radius-sm);
}

.pill-info {
  display: flex;
  align-items: center;
  gap: 6px;
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
  padding: 1px 5px;
  font-family: var(--ui-mono);
  font-size: 11px;
  color: var(--ui-accent);
  background: rgba(37, 99, 235, 0.1);
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
  transition: all 120ms ease;
}

.pill-close-btn::after {
  position: absolute;
  content: '';
  inset: -12px;
}

.pill-close-btn:hover {
  color: var(--ui-ink);
  background: rgba(0, 0, 0, 0.08);
}

.input-container {
  display: flex;
  flex-direction: column;
  padding: 8px 10px 8px;
  background: var(--ui-surface-muted);
  border: 1px solid var(--ui-line);
  border-radius: var(--ui-radius);
  transition: all 160ms ease;
}

.input-container:focus-within {
  background: var(--ui-surface);
  border-color: var(--ui-accent);
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.1);
}

.input-container :deep(.ant-input) {
  min-height: 64px;
  padding: 2px 4px;
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
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid rgba(0, 0, 0, 0.05);
}

.actions-left {
  display: flex;
  align-items: center;
  gap: 10px;
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
