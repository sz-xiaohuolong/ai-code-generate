<script setup lang="ts">
import { onMounted } from 'vue'
import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { useLoginUserStore } from '@/stores/loginUser'

const loginUserStore = useLoginUserStore()

const antdTheme: ThemeConfig = {
  token: {
    colorPrimary: '#2563eb',
    colorText: '#0f172a',
    colorTextSecondary: '#475569',
    colorBorder: '#e2e8f0',
    colorBgContainer: '#ffffff',
    colorBgLayout: '#f8fafc',
    borderRadius: 8,
    fontSize: 14,
    controlHeight: 36,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'PingFang SC', sans-serif",
  },
}

onMounted(() => {
  loginUserStore.fetchLoginUser()
})
</script>

<template>
  <a-config-provider :theme="antdTheme">
    <BasicLayout />
  </a-config-provider>
</template>

<style scoped>
</style>
