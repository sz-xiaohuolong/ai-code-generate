<script setup lang="ts">
import { ref } from 'vue'
import { message } from 'ant-design-vue'

const props = defineProps<{
  generating?: boolean
}>()

const emit = defineEmits<{
  send: [value: string]
}>()

const inputMessage = ref('')

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
      <a-button type="primary" shape="circle" :loading="generating" @click="submit">↑</a-button>
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

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

.composer-tip {
  color: #9ca3af;
  font-size: 13px;
}
</style>
