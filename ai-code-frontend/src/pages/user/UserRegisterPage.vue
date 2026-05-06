<script setup lang="ts">
import { reactive, ref } from 'vue'
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
  submitting.value = true
  try {
    const res = await userRegister(formState)
    if (res.data.code === 0 && res.data.data) {
      message.success('注册成功，请登录')
      await router.push('/user/login')
    } else {
      message.error(res.data.message || '注册失败')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="user-page">
    <div class="form-panel">
      <h1 class="form-title">用户注册</h1>
      <a-form
        :model="formState"
        name="userRegister"
        layout="vertical"
        autocomplete="off"
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
          <a-input v-model:value="formState.userAccount" placeholder="请输入账号" allow-clear />
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
            placeholder="请输入密码"
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
    </div>
  </div>
</template>

<style scoped>
.user-page {
  display: flex;
  justify-content: center;
  padding: 48px 16px;
}

.form-panel {
  width: 100%;
  max-width: 420px;
  padding: 32px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 6px 20px rgba(0, 21, 41, 0.08);
}

.form-title {
  margin: 0 0 24px;
  text-align: center;
  font-size: 24px;
  font-weight: 600;
  color: #1f2937;
}

.form-extra {
  text-align: center;
  color: #666;
}
</style>
