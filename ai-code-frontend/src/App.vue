<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watchEffect } from 'vue'
import { theme } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { useLoginUserStore } from '@/stores/loginUser'

const loginUserStore = useLoginUserStore()

const systemTheme = window.matchMedia('(prefers-color-scheme: dark)')
const dark = ref(systemTheme.matches)
const syncSystemTheme = (event: MediaQueryListEvent) => {
  dark.value = event.matches
}
// Ant Design Vue 与页面变量共享颜色来源，避免系统深色下出现浅色控件。
const colors = computed(() =>
  dark.value
    ? {
        shell: '#10151e',
        surface: '#171e29',
        'surface-muted': '#202938',
        'surface-hover': '#2b3748',
        line: '#303c4d',
        'line-strong': '#526078',
        'line-subtle': '#252f3e',
        ink: '#eef2f8',
        'ink-soft': '#bac6d6',
        'ink-faint': '#9aaabe',
        accent: '#7aa7ff',
        'accent-hover': '#a0c1ff',
        'accent-soft': '#202f49',
        'accent-border': '#415e89',
        'on-accent': '#101b30',
        danger: '#ff9b9b',
        'danger-soft': '#392327',
        success: '#86d7bb',
        'success-soft': '#1a3530',
        warning: '#f0c47c',
        'warning-soft': '#382e21',
      }
    : {
        shell: '#f7f9fc',
        surface: '#ffffff',
        'surface-muted': '#f0f3f7',
        'surface-hover': '#e9eef5',
        line: '#e0e5ed',
        'line-strong': '#bdc8d6',
        'line-subtle': '#edf0f5',
        ink: '#172234',
        'ink-soft': '#4c5b70',
        'ink-faint': '#637189',
        accent: '#2563eb',
        'accent-hover': '#1d4ed8',
        'accent-soft': '#edf3ff',
        'accent-border': '#c9d9fc',
        'on-accent': '#ffffff',
        danger: '#b52c3a',
        'danger-soft': '#fff1f2',
        success: '#23745b',
        'success-soft': '#edf8f3',
        warning: '#825b18',
        'warning-soft': '#fcf5e9',
      },
)

watchEffect(() => {
  document.documentElement.dataset.theme = dark.value ? 'dark' : 'light'
  document.documentElement.style.colorScheme = dark.value ? 'dark' : 'light'
  for (const [name, value] of Object.entries(colors.value)) {
    document.documentElement.style.setProperty(`--ui-${name}`, value)
  }
})

const antdTheme = computed<ThemeConfig>(() => ({
  algorithm: dark.value ? theme.darkAlgorithm : theme.defaultAlgorithm,
  token: {
    colorPrimary: colors.value.accent,
    colorLink: colors.value.accent,
    colorLinkHover: colors.value['accent-hover'],
    colorLinkActive: colors.value['accent-hover'],
    colorText: colors.value.ink,
    colorTextSecondary: colors.value['ink-soft'],
    colorTextPlaceholder: colors.value['ink-faint'],
    colorBorder: colors.value.line,
    colorBgContainer: colors.value.surface,
    colorBgElevated: colors.value.surface,
    colorBgLayout: colors.value.shell,
    borderRadius: 8,
    fontSize: 14,
    controlHeight: 36,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'PingFang SC', sans-serif",
  },
}))

onMounted(() => {
  systemTheme.addEventListener('change', syncSystemTheme)
  loginUserStore.fetchLoginUser()
})
onBeforeUnmount(() => systemTheme.removeEventListener('change', syncSystemTheme))
</script>

<template>
  <a-config-provider :theme="antdTheme" :locale="zhCN" :auto-insert-space-in-button="false">
    <BasicLayout />
  </a-config-provider>
</template>

<style scoped></style>
