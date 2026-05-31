<script setup lang="ts">
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
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

const selectedElementTitle = computed(() => {
  if (!props.selectedElement) {
    return ''
  }
  return `已选择元素：${props.selectedElement.tagName}${props.selectedElement.id ? `#${props.selectedElement.id}` : ''}`
})

const selectedElementDescription = computed(() => {
  if (!props.selectedElement) {
    return ''
  }
  const className = props.selectedElement.className ? `.${props.selectedElement.className}` : ''
  const text = props.selectedElement.text ? `，文本：${props.selectedElement.text}` : ''
  return `选择器：${props.selectedElement.selector || '-'}${className ? `，类名：${className}` : ''}${text}`
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
    <a-alert
      v-if="selectedElement"
      class="selected-element-alert"
      type="info"
      show-icon
      closable
      @close="emit('clearSelectedElement')"
    >
      <template #message>{{ selectedElementTitle }}</template>
      <template #description>{{ selectedElementDescription }}</template>
    </a-alert>
    <a-textarea
      v-model:value="inputMessage"
      placeholder="请描述你想生成的网站，越详细效果越好哦"
      :rows="4"
      :maxlength="1000"
      show-count
      @press-enter.ctrl="submit"
    />
    <div class="composer-actions">
      <span class="composer-tip">Ctrl + Enter 发送</span>
      <div class="composer-action-right">
        <a-button :type="editMode ? 'primary' : 'default'" :disabled="generating" @click="emit('toggleEditMode')">
          {{ editMode ? '退出编辑' : '编辑' }}
        </a-button>
        <a-button type="primary" shape="circle" :loading="generating" @click="submit">↑</a-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.composer {
  padding: 12px;
  border-top: 1px solid #edf0f5;
}

.composer :deep(.ant-input) {
  resize: none;
}

.selected-element-alert {
  margin-bottom: 12px;
}

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

.composer-action-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.composer-tip {
  color: #9ca3af;
  font-size: 13px;
}
</style>
