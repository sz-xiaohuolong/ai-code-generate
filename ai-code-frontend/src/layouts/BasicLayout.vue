<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'

const route = useRoute()
const isHomePage = computed(() => route.path === '/')
const isAdminPage = computed(() => route.path.startsWith('/admin/'))
const isAuthPage = computed(() => ['/user/login', '/user/register'].includes(route.path))
const isChatPage = computed(() => route.path.startsWith('/app/chat/'))
</script>

<template>
  <a-layout
    class="basic-layout"
    :class="{
      'home-layout': isHomePage,
      'chat-layout': isChatPage,
      'admin-layout': isAdminPage,
      'auth-layout': isAuthPage,
    }"
  >
    <a-layout-header v-if="!isAuthPage" class="layout-header">
      <GlobalHeader />
    </a-layout-header>
    <a-layout-content class="layout-content">
      <div class="content-wrapper">
        <RouterView />
      </div>
    </a-layout-content>
    <a-layout-footer v-if="!isChatPage && !isAuthPage" class="layout-footer">
      <GlobalFooter />
    </a-layout-footer>
  </a-layout>
</template>

<style scoped>
.basic-layout {
  min-height: 100dvh;
  background: var(--ui-shell);
}
.layout-header {
  position: sticky;
  top: 0;
  z-index: 20;
  height: var(--ui-header-height);
  padding: 0;
  line-height: normal;
  background: var(--ui-header-glass);
  border-bottom: 1px solid var(--ui-line);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}
.layout-content {
  min-height: calc(100dvh - var(--ui-header-height) - 46px);
  padding: 28px 32px 48px;
  background: var(--ui-shell);
}
.content-wrapper {
  max-width: 1480px;
  margin: 0 auto;
}
.auth-layout .layout-content {
  padding: 0;
  background: var(--ui-surface);
}
.auth-layout .content-wrapper,
.home-layout .content-wrapper,
.chat-layout .content-wrapper {
  max-width: none;
}
.home-layout .layout-content {
  padding: 0;
}
.chat-layout .layout-content {
  height: calc(100dvh - var(--ui-header-height));
  min-height: 0;
  padding: 0;
  overflow: hidden;
}
.chat-layout .content-wrapper {
  height: 100%;
}
.layout-footer {
  padding: 0;
  background: var(--ui-shell);
}
.admin-layout .layout-content {
  padding: 0 40px 48px;
  background: var(--ui-surface);
}
.admin-layout .content-wrapper {
  max-width: 1440px;
}
.admin-layout .layout-footer {
  background: var(--ui-surface);
}
@media (max-width: 1100px) {
  .layout-content {
    padding: 20px 16px 36px;
  }
}
@media (max-width: 760px) {
  .admin-layout .layout-content {
    padding: 0 16px 28px;
  }
}
</style>
