import { ref } from 'vue'
import { defineStore } from 'pinia'
import { normalizeGenerationMode, type GenerationMode } from '@/utils/workflowProgress'

const STORAGE_KEY = 'ai-code-new-app-mode'

export const useGenerationModeStore = defineStore('generationMode', () => {
  const defaultMode = ref<GenerationMode>('standard')
  try {
    defaultMode.value = normalizeGenerationMode(localStorage.getItem(STORAGE_KEY))
  } catch {
    // 浏览器禁止存储时，当前页面仍可正常选择和创建。
  }

  const setDefaultMode = (value: string | number) => {
    defaultMode.value = normalizeGenerationMode(value)
    try {
      localStorage.setItem(STORAGE_KEY, defaultMode.value)
    } catch {
      // 存储不可用不阻断应用创建。
    }
  }

  return { defaultMode, setDefaultMode }
})
