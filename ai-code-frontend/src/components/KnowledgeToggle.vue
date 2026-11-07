<script setup lang="ts">
defineProps<{ modelValue: boolean; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <div class="knowledge-choice">
    <label class="knowledge-switch">
      <input type="checkbox" :checked="modelValue" :disabled="disabled" @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)" />
      使用个人知识库
    </label>
    <RouterLink to="/knowledge">管理资料</RouterLink>
    <p v-if="modelValue">检索全部可用资料；无相关内容时停止生成。相关片段会发送给当前配置的生成模型。</p>
  </div>
</template>

<style scoped>
.knowledge-choice { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 16px; padding: 8px 2px; font-size: 13px; }
.knowledge-switch { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; color: var(--ui-ink); }
.knowledge-switch input { width: 16px; height: 16px; accent-color: var(--ui-accent); }
.knowledge-choice p { flex-basis: 100%; margin: 0; color: var(--ui-ink-soft); font-size: 12px; line-height: 1.5; }
</style>
