<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'

const route = useRoute()
const isHomePage = computed(() => route.path === '/')
</script>

<template>
  <a-layout class="basic-layout" :class="{ 'home-layout': isHomePage }">
    <a-layout-header class="layout-header" :class="{ 'home-header': isHomePage }">
      <GlobalHeader :home-mode="isHomePage" />
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

.home-layout .layout-header {
  position: absolute;
  width: 100%;
  background: transparent;
  box-shadow: none;
}

.layout-content {
  padding: 24px;
  background: #f0f2f5;
  min-height: calc(100vh - 64px - 72px);
}

.home-layout .layout-content {
  padding: 0;
  background: transparent;
}

.content-wrapper {
  max-width: 1440px;
  margin: 0 auto;
}

.home-layout .content-wrapper {
  max-width: none;
  padding: 0;
}

.layout-footer {
  background: #f0f2f5;
  padding: 0;
}

.home-layout .layout-footer {
  background: #e8f4ff;
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
