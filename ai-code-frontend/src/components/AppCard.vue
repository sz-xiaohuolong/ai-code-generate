<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { buildAppDeployUrl, buildAppPreviewUrl } from '@/config/env'
import { getUserAvatar } from '@/constants/user'

const props = defineProps<{
  app: API.AppVO
  featured?: boolean
}>()

const router = useRouter()

const previewUrl = computed(() => {
  return buildAppPreviewUrl(props.app.codeGenType, props.app.id)
})

const coverStyle = computed(() => {
  if (props.app.cover) {
    return {
      backgroundImage: `url(${props.app.cover})`,
    }
  }
  return {}
})

const goChat = () => {
  if (props.app.id) {
    router.push(`/app/chat/${props.app.id}`)
  }
}

const openWork = () => {
  const deployUrl = buildAppDeployUrl(props.app.deployKey)
  if (deployUrl) {
    window.open(deployUrl, '_blank')
  }
}
</script>

<template>
  <div class="app-card">
    <div class="cover" :style="coverStyle">
      <iframe
        v-if="!app.cover && previewUrl"
        class="preview-frame"
        :src="previewUrl"
        title="应用预览"
        loading="lazy"
      />
      <div v-if="!app.cover && !previewUrl" class="cover-placeholder">
        {{ app.appName?.slice(0, 1) || 'A' }}
      </div>
    </div>
    <div class="card-body">
      <div class="app-info">
        <a-avatar :src="getUserAvatar(app.user?.userAvatar)" :size="44" />
        <div class="info-main">
          <div class="title-row">
            <h3>{{ app.appName || '未命名应用' }}</h3>
            <a-tag v-if="featured" color="gold">精选</a-tag>
          </div>
          <div class="creator-name">{{ app.user?.userName || app.user?.userAccount || '用户' }}</div>
        </div>
      </div>
      <div class="card-actions">
        <a-button type="primary" @click="goChat">查看对话</a-button>
        <a-button v-if="app.deployKey" @click="openWork">查看作品</a-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.app-card {
  height: 100%;
}

.cover {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  background:
    linear-gradient(135deg, rgba(22, 119, 255, 0.12), rgba(19, 194, 194, 0.18)),
    #f8fafc;
  background-position: center;
  background-size: cover;
  border: 1px solid #edf0f5;
  border-radius: 8px;
}

.preview-frame {
  width: 160%;
  height: 160%;
  border: 0;
  pointer-events: none;
  transform: scale(0.625);
  transform-origin: left top;
  background: #fff;
}

.cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: #1677ff;
  font-size: 48px;
  font-weight: 700;
}

.card-body {
  padding: 14px 4px 0;
}

.app-info {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.info-main {
  min-width: 0;
  flex: 1;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.title-row h3 {
  flex: 1;
  margin: 0;
  overflow: hidden;
  color: #111827;
  font-size: 18px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.creator-name {
  margin-top: 4px;
  overflow: hidden;
  color: #6b7280;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}
</style>
