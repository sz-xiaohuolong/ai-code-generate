import { createRouter, createWebHistory } from 'vue-router'
import { message } from 'ant-design-vue'
import HomePage from '../pages/HomePage.vue'
import { useLoginUserStore } from '@/stores/loginUser'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomePage,
    },
    {
      path: '/user/login',
      name: 'userLogin',
      component: () => import('../pages/user/UserLoginPage.vue'),
    },
    {
      path: '/user/register',
      name: 'userRegister',
      component: () => import('../pages/user/UserRegisterPage.vue'),
    },
    {
      path: '/user/settings',
      name: 'userSettings',
      component: () => import('../pages/user/UserSettingsPage.vue'),
      meta: {
        access: 'login',
      },
    },
    {
      path: '/admin/userManage',
      name: 'userManage',
      component: () => import('../pages/admin/UserManagePage.vue'),
      meta: {
        access: 'admin',
      },
    },
    {
      path: '/about',
      name: 'about',
      // route level code-splitting
      // this generates a separate chunk (About.[hash].js) for this route
      // which is lazy-loaded when the route is visited.
      component: () => import('../pages/AboutPage.vue'),
    },
  ],
})

router.beforeEach(async (to) => {
  const access = to.meta.access
  if (!access) {
    return true
  }

  const loginUserStore = useLoginUserStore()
  if (!loginUserStore.isLogin) {
    await loginUserStore.fetchLoginUser()
  }

  if (access === 'login' && !loginUserStore.isLogin) {
    return {
      path: '/user/login',
      query: {
        redirect: to.fullPath,
      },
    }
  }

  if (loginUserStore.loginUser.userRole !== 'admin') {
    if (!loginUserStore.isLogin) {
      return {
        path: '/user/login',
        query: {
          redirect: to.fullPath,
        },
      }
    }
    message.warning('无权限访问')
    return '/'
  }

  return true
})

export default router
