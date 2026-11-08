<script setup lang="ts">
import { reactive, ref } from 'vue'
import AuthPageShell from '@/components/AuthPageShell.vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { userRegister } from '@/api/userController'

const router = useRouter()

const formState = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})
const submitting = ref(false)
const submitError = ref('')

const validateCheckPassword = async (_rule: unknown, value: string) => {
  if (!value) {
    return Promise.reject('请再次输入密码')
  }
  if (value !== formState.userPassword) {
    return Promise.reject('两次输入的密码不一致')
  }
  return Promise.resolve()
}

const handleSubmit = async () => {
  if (submitting.value) return
  submitting.value = true
  submitError.value = ''
  try {
    const res = await userRegister(formState)
    if (res.data.code === 0 && res.data.data) {
      message.success('注册成功，请登录')
      await router.push('/user/login')
    } else {
      submitError.value = res.data.message || '注册失败，请重试'
    }
  } catch {
    submitError.value = '注册请求未完成，请检查网络后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthPageShell title="让想法，有个开始。" description="创建账号，开启你的第一段 AI 应用创作。">
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
      name="userRegister"
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
          autocomplete="new-password"
          allow-clear
        />
      </a-form-item>

      <a-form-item
        label="确认密码"
        name="checkPassword"
        :rules="[{ validator: validateCheckPassword, trigger: 'change' }]"
      >
        <a-input-password
          v-model:value="formState.checkPassword"
          placeholder="请再次输入密码"
          autocomplete="new-password"
          allow-clear
        />
      </a-form-item>

      <a-form-item>
        <a-button type="primary" html-type="submit" block :loading="submitting">注册</a-button>
      </a-form-item>
    </a-form>
    <div class="form-extra">
      已有账号？
      <RouterLink to="/user/login">去登录</RouterLink>
    </div>
  </AuthPageShell>
</template>
