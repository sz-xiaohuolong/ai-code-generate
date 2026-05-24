<script setup lang="ts">
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'
</script>

<template>
  <a-layout class="basic-layout">
    <a-layout-header class="layout-header">
      <GlobalHeader />
    </a-layout-header>
    <a-layout-content class="layout-content">
      <div class="content-wrapper">
        <RouterView />
      </div>
    </a-layout-content>
    <a-layout-footer class="layout-footer">
      <GlobalFooter />
    </a-layout-footer>
  </a-layout>
</template>

<style scoped>
.basic-layout {
  min-height: 100vh;
}

.layout-header {
  background: #fff;
  padding: 0;
  height: 64px;
  line-height: 64px;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  position: sticky;
  top: 0;
  z-index: 100;
}

.layout-content {
  padding: 24px;
  background: #f0f2f5;
  min-height: calc(100vh - 64px - 72px);
}

.content-wrapper {
  max-width: 1440px;
  margin: 0 auto;
}

.layout-footer {
  background: #f0f2f5;
  padding: 0;
}

/* 响应式适配 */
@media (max-width: 768px) {
  .layout-content {
    padding: 12px;
  }

  .content-wrapper {
    padding: 12px;
  }
}
</style>
