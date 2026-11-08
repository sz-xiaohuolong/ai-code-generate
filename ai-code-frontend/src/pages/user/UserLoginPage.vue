<script setup lang="ts">
import { reactive, ref } from 'vue'
import AuthPageShell from '@/components/AuthPageShell.vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { userLogin } from '@/api/userController'
import { useLoginUserStore } from '@/stores/loginUser'

const router = useRouter()
const route = useRoute()
const loginUserStore = useLoginUserStore()

const formState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})
const submitting = ref(false)
const submitError = ref('')

const handleSubmit = async () => {
  if (submitting.value) return
  submitting.value = true
  submitError.value = ''
  try {
    const res = await userLogin(formState)
    if (res.data.code === 0 && res.data.data) {
      loginUserStore.setLoginUser(res.data.data)
      message.success('登录成功')
      const redirect = route.query.redirect as string | undefined
      if (redirect && !redirect.includes('/user/login')) {
        if (redirect.startsWith('http')) {
          window.location.href = redirect
        } else {
          await router.push(redirect)
        }
      } else {
        await router.push('/')
      }
    } else {
      submitError.value = res.data.message || '登录失败，请重试'
    }
  } catch {
    submitError.value = '登录请求未完成，请检查网络后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthPageShell title="欢迎回来。" description="登录创作空间，接着上次的灵感。">
    <a-alert
      v-if="submitError"
      class="auth-error"
      type="error"
      :message="submitError"
      show-icon
      role="alert"
    />
    <a-form
      :model="formState"
      name="userLogin"
      layout="vertical"
      :disabled="submitting"
      @finish="handleSubmit"
    >
      <a-form-item
        label="账号"
        name="userAccount"
        :rules="[
          { required: true, message: '请输入账号' },
          { min: 4, message: '账号不能少于 4 位' },
        ]"
      >
        <a-input
          v-model:value="formState.userAccount"
          placeholder="请输入账号（至少 4 位）"
          autocomplete="username"
          allow-clear
        />
      </a-form-item>

      <a-form-item
        label="密码"
        name="userPassword"
        :rules="[
          { required: true, message: '请输入密码' },
          { min: 8, message: '密码不能少于 8 位' },
        ]"
      >
        <a-input-password
          v-model:value="formState.userPassword"
          placeholder="请输入密码（至少 8 位）"
          autocomplete="current-password"
          allow-clear
        />
      </a-form-item>

      <a-form-item>
        <a-button type="primary" html-type="submit" block :loading="submitting">登录</a-button>
      </a-form-item>
    </a-form>
    <div class="form-extra">
      还没有账号？
      <RouterLink to="/user/register">立即注册</RouterLink>
    </div>
  </AuthPageShell>
</template>
