<script setup lang="ts">
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { DownOutlined, LoginOutlined, LogoutOutlined, MenuOutlined, SettingOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { userLogout } from '@/api/userController'
import { useLoginUserStore } from '@/stores/loginUser'
import { getUserAvatar } from '@/constants/user'

const router = useRouter()
const loginUserStore = useLoginUserStore()
const { loginUser, isLogin } = storeToRefs(loginUserStore)

const menuItems = computed(() => {
  const items = [{ path: '/', title: '首页' }]
  if (loginUser.value.userRole === 'admin') {
    items.push({ path: '/admin/userManage', title: '用户管理' })
    items.push({ path: '/admin/appManage', title: '应用管理' })
    items.push({ path: '/admin/chatHistoryManage', title: '对话管理' })
  }
  items.push({ path: '/about', title: '关于' })
  return items
})

const displayName = computed(() => loginUser.value.userName || loginUser.value.userAccount || '用户')

const handleLogout = async () => {
  try {
    const res = await userLogout()
    if (res.data.code === 0) {
      message.success('已退出登录')
    } else {
      message.error(res.data.message || '退出登录失败')
    }
  } catch {
    message.error('退出登录失败')
  } finally {
    loginUserStore.clearLoginUser()
    router.push('/user/login')
  }
}
</script>

<template>
  <header class="global-header">
    <RouterLink class="brand-link" to="/" aria-label="返回 AI 代码生成平台工作台">
      <img src="@/assets/logo.svg" alt="" class="logo" />
      <span class="brand-name">AI代码生成平台</span>
    </RouterLink>

    <nav class="primary-nav" aria-label="主导航">
      <RouterLink
        v-for="item in menuItems"
        :key="item.path"
        :to="item.path"
        class="nav-link"
        :class="{ active: $route.path === item.path }"
      >
        {{ item.title }}
      </RouterLink>
    </nav>

    <div class="header-right">
      <a-dropdown class="mobile-navigation" :trigger="['click']" placement="bottomRight">
        <a-button aria-label="打开导航菜单"><MenuOutlined /></a-button>
        <template #overlay>
          <a-menu>
            <a-menu-item v-for="item in menuItems" :key="item.path" @click="router.push(item.path)">
              {{ item.title }}
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
      <a-button v-if="!isLogin" type="primary" @click="router.push('/user/login')">
        <template #icon><LoginOutlined /></template>
        登录
      </a-button>
      <a-dropdown v-else placement="bottomRight" :trigger="['hover', 'click']">
        <button class="user-entry" type="button" :aria-label="`${displayName}，打开账户菜单`">
          <a-avatar :src="getUserAvatar(loginUser.userAvatar)" :size="28" />
          <span class="user-name">{{ displayName }}</span>
          <DownOutlined class="dropdown-arrow" />
        </button>
        <template #overlay>
          <a-menu class="account-menu">
            <a-menu-item key="settings" @click="router.push('/user/settings')">
              <SettingOutlined /> 个人设置
            </a-menu-item>
            <a-menu-divider />
            <a-menu-item key="logout" @click="handleLogout">
              <LogoutOutlined /> 退出登录
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </div>
  </header>
</template>

<style scoped>
.global-header {
  display: flex;
  align-items: center;
  height: 58px;
  max-width: 1760px;
  margin: 0 auto;
  padding: 0 30px;
  gap: 34px;
}

.brand-link {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 10px;
  color: var(--ui-ink);
  white-space: nowrap;
}

.brand-link:hover {
  color: var(--ui-ink);
}

.logo {
  width: 30px;
  height: 30px;
  border-radius: 7px;
}

.brand-name {
  font-size: 14px;
  font-weight: 700;
}

.primary-nav {
  display: flex;
  min-width: 0;
  height: 100%;
  align-items: stretch;
  gap: 3px;
  overflow-x: auto;
  scrollbar-width: none;
}

.primary-nav::-webkit-scrollbar {
  display: none;
}

.nav-link {
  position: relative;
  display: inline-flex;
  flex: none;
  align-items: center;
  padding: 0 13px;
  color: var(--ui-ink-soft);
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  transition: color 180ms ease;
}

.nav-link:hover,
.nav-link.active {
  color: var(--ui-ink);
}

.nav-link.active::after {
  position: absolute;
  right: 13px;
  bottom: 0;
  left: 13px;
  height: 2px;
  content: '';
  background: var(--ui-accent);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  flex: none;
}

.mobile-navigation {
  display: none;
}

.user-entry {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-height: 36px;
  padding: 3px 6px;
  color: var(--ui-ink);
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: pointer;
}

.user-entry:hover {
  background: var(--ui-surface-hover);
}

.user-name {
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 600;
}

.dropdown-arrow {
  color: var(--ui-ink-faint);
  font-size: 10px;
}

@media (max-width: 760px) {
  .global-header {
    padding: 0 14px;
    gap: 12px;
  }

  .brand-name {
    display: none;
  }

  .primary-nav {
    display: none;
  }

  .mobile-navigation {
    display: inline-flex;
  }

  .user-name,
  .dropdown-arrow {
    display: none;
  }
}
</style>
