<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { userLogout } from '@/api/userController'
import { useLoginUserStore } from '@/stores/loginUser'

const router = useRouter()
const loginUserStore = useLoginUserStore()
const { loginUser, isLogin } = storeToRefs(loginUserStore)

// 菜单配置 - 支持扩展
const menuItems = computed(() => {
  const items = [
    { key: '/', title: '首页' },
    { key: '/about', title: '关于' },
  ]
  if (loginUser.value.userRole === 'admin') {
    items.push({ key: '/admin/userManage', title: '用户管理' })
  }
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

const avatarText = computed(() => {
  return displayName.value.slice(0, 1).toUpperCase()
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
  <div class="global-header">
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
          <a-avatar v-if="loginUser.userAvatar" :src="loginUser.userAvatar" />
          <a-avatar v-else>{{ avatarText }}</a-avatar>
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
  padding: 0 24px;
  background: #fff;
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
  height: 32px;
  width: auto;
}

.title {
  font-size: 18px;
  font-weight: 600;
  color: #1890ff;
  margin-left: 12px;
  white-space: nowrap;
}

.nav-menu {
  border-bottom: none;
  flex: 1;
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
    height: 24px;
  }

  .logo-wrapper {
    margin-right: 12px;
  }

  .user-name {
    display: none;
  }
}
</style>
