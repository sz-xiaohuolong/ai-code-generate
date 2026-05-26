<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { userLogout } from '@/api/userController'
import { useLoginUserStore } from '@/stores/loginUser'
import { getUserAvatar } from '@/constants/user'

defineProps<{
  homeMode?: boolean
}>()

const router = useRouter()
const loginUserStore = useLoginUserStore()
const { loginUser, isLogin } = storeToRefs(loginUserStore)

// 菜单配置 - 支持扩展
const menuItems = computed(() => {
  const items = [
    { key: '/', title: '首页' },
  ]
  if (loginUser.value.userRole === 'admin') {
    items.push({ key: '/admin/userManage', title: '用户管理' })
    items.push({ key: '/admin/appManage', title: '应用管理' })
    items.push({ key: '/admin/chatHistoryManage', title: '对话管理' })
  }
  items.push({ key: '/about', title: '关于' })
  return items
})

// 当前选中的路由
const selectedKeys = ref<string[]>([router.currentRoute.value.path])

// 监听路由变化，更新选中菜单
watch(
  () => router.currentRoute.value.path,
  (newPath) => {
    selectedKeys.value = [newPath]
  },
)

// 菜单点击处理
const handleMenuClick = ({ key }: { key: string }) => {
  router.push(key)
}

const displayName = computed(() => {
  return loginUser.value.userName || loginUser.value.userAccount || '用户'
})

const goLogin = () => {
  router.push('/user/login')
}

const handleLogout = async () => {
  try {
    const res = await userLogout()
    if (res.data.code === 0) {
      message.success('退出登录成功')
    } else {
      message.error(res.data.message || '退出登录失败')
    }
  } catch (error) {
    message.error('退出登录失败')
  } finally {
    loginUserStore.clearLoginUser()
    router.push('/user/login')
  }
}
</script>

<template>
  <div class="global-header" :class="{ 'home-mode': homeMode }">
    <div class="header-left">
      <div class="logo-wrapper">
        <img src="@/assets/logo.svg" alt="logo" class="logo" />
        <span class="title">AI代码生成平台</span>
      </div>
      <a-menu
        mode="horizontal"
        :selected-keys="selectedKeys"
        @click="handleMenuClick"
        class="nav-menu"
      >
        <a-menu-item v-for="item in menuItems" :key="item.key">
          {{ item.title }}
        </a-menu-item>
      </a-menu>
    </div>
    <div class="header-right">
      <a-button v-if="!isLogin" type="primary" @click="goLogin">登录</a-button>
      <a-dropdown v-else placement="bottomRight">
        <div class="user-entry">
          <a-avatar :src="getUserAvatar(loginUser.userAvatar)" />
          <span class="user-name">{{ displayName }}</span>
        </div>
        <template #overlay>
          <a-menu>
            <a-menu-item key="settings" @click="router.push('/user/settings')">
              个人中心 / 个人设置
            </a-menu-item>
            <a-menu-item key="logout" @click="handleLogout">退出登录</a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </div>
  </div>
</template>

<style scoped>
.global-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 64px;
  padding: 0 48px;
  background: #fff;
  transition:
    background-color 0.2s ease,
    backdrop-filter 0.2s ease;
}

.global-header.home-mode {
  background: transparent;
}

.header-left {
  display: flex;
  align-items: center;
  flex: 1;
}

.logo-wrapper {
  display: flex;
  align-items: center;
  margin-right: 24px;
}

.logo {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  box-shadow: 0 10px 30px rgba(14, 116, 144, 0.16);
}

.title {
  font-size: 18px;
  font-weight: 700;
  color: #1677ff;
  margin-left: 12px;
  white-space: nowrap;
}

.home-mode .title {
  color: #0f172a;
}

.nav-menu {
  border-bottom: none;
  flex: 1;
  background: transparent;
}

.nav-menu :deep(.ant-menu-item) {
  color: #1f2937;
  font-weight: 500;
}

.nav-menu :deep(.ant-menu-item:hover),
.nav-menu :deep(.ant-menu-item-selected) {
  color: #1677ff;
}

.home-mode .nav-menu :deep(.ant-menu-item) {
  color: rgba(15, 23, 42, 0.76);
}

.home-mode .nav-menu :deep(.ant-menu-item:hover),
.home-mode .nav-menu :deep(.ant-menu-item-selected) {
  color: #0f172a;
}

.home-mode .nav-menu :deep(.ant-menu-item-selected::after) {
  border-bottom-color: #0f172a;
}

.header-right {
  display: flex;
  align-items: center;
}

.user-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  height: 64px;
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  color: #1f2937;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 响应式适配 */
@media (max-width: 768px) {
  .global-header {
    padding: 0 12px;
  }

  .title {
    font-size: 14px;
    margin-left: 8px;
  }

  .logo {
    width: 28px;
    height: 28px;
  }

  .logo-wrapper {
    margin-right: 12px;
  }

  .user-name {
    display: none;
  }
}
</style>
