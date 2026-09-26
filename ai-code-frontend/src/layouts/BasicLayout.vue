<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'

const route = useRoute()
const isHomePage = computed(() => route.path === '/')
const isChatPage = computed(() => route.path.startsWith('/app/chat/'))
</script>

<template>
  <a-layout class="basic-layout" :class="{ 'home-layout': isHomePage, 'chat-layout': isChatPage }">
    <a-layout-header class="layout-header">
      <GlobalHeader />
    </a-layout-header>
    <a-layout-content class="layout-content">
      <div class="content-wrapper">
        <RouterView />
      </div>
    </a-layout-content>
    <a-layout-footer v-if="!isChatPage" class="layout-footer">
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
  height: 58px;
  padding: 0;
  line-height: normal;
  background: rgba(255, 255, 255, 0.94);
  border-bottom: 1px solid var(--ui-line);
  backdrop-filter: saturate(140%) blur(16px);
}

.layout-content {
  min-height: calc(100dvh - 104px);
  padding: 28px 32px 48px;
  background: var(--ui-shell);
}

.content-wrapper {
  max-width: 1480px;
  margin: 0 auto;
}

.home-layout .layout-content {
  padding: 0;
}

.home-layout .content-wrapper {
  max-width: none;
}

.chat-layout .layout-content {
  height: calc(100dvh - 58px);
  min-height: 0;
  padding: 0;
  overflow: hidden;
}

.chat-layout .content-wrapper {
  max-width: none;
  height: 100%;
}

.layout-footer {
  padding: 0;
  background: var(--ui-shell);
}

@media (max-width: 1100px) {
  .layout-content {
    padding: 20px 16px 36px;
  }

  .chat-layout .layout-content {
    height: auto;
    min-height: calc(100dvh - 58px);
    overflow: visible;
  }
}
</style>
