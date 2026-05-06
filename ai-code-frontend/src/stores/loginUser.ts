import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getLoginUser } from '@/api/userController'

export const useLoginUserStore = defineStore('loginUser', () => {
  const loginUser = ref<API.LoginUserVO>({})

  const isLogin = computed(() => {
    return !!loginUser.value.id
  })

  function setLoginUser(user: API.LoginUserVO) {
    loginUser.value = user || {}
  }

  function clearLoginUser() {
    loginUser.value = {}
  }

  async function fetchLoginUser() {
    try {
      const res = await getLoginUser()
      if (res.data.code === 0 && res.data.data) {
        setLoginUser(res.data.data)
      } else {
        clearLoginUser()
      }
    } catch (error) {
      clearLoginUser()
    }
  }

  return {
    loginUser,
    isLogin,
    setLoginUser,
    clearLoginUser,
    fetchLoginUser,
  }
})
