<script setup lang="ts">
import { onMounted } from 'vue'
import { theme } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { useLoginUserStore } from '@/stores/loginUser'

const loginUserStore = useLoginUserStore()

// 固定暗色工作台；CSS 语义变量是页面与 Ant Design Vue 的唯一色彩来源。
const rootStyles = getComputedStyle(document.documentElement)
const color = (name: string) => rootStyles.getPropertyValue(`--ui-${name}`).trim()
const antdTheme: ThemeConfig = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: color('action'),
    colorLink: color('accent'),
    colorLinkHover: color('accent-hover'),
    colorLinkActive: color('accent-hover'),
    colorText: color('ink'),
    colorTextSecondary: color('ink-soft'),
    colorTextTertiary: color('ink-faint'),
    colorTextPlaceholder: color('ink-faint'),
    colorBorder: color('line-strong'),
    colorBorderSecondary: color('line'),
    colorBgContainer: color('surface'),
    colorBgElevated: color('surface'),
    colorBgLayout: color('shell'),
    colorSuccess: color('success'),
    colorWarning: color('warning'),
    colorError: color('danger'),
    borderRadius: 8,
    fontSize: 14,
    controlHeight: 36,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'PingFang SC', sans-serif",
  },
}

onMounted(() => loginUserStore.fetchLoginUser())
</script>

<template>
  <a-config-provider :theme="antdTheme" :locale="zhCN" :auto-insert-space-in-button="false">
    <BasicLayout />
  </a-config-provider>
</template>
