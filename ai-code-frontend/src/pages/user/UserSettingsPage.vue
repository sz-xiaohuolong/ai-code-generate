<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { storeToRefs } from 'pinia'
import { updateMyUser } from '@/api/userController'
import { useLoginUserStore } from '@/stores/loginUser'
import { getUserAvatar } from '@/constants/user'

const router = useRouter()
const loginUserStore = useLoginUserStore()
const { loginUser } = storeToRefs(loginUserStore)
const submitting = ref(false)

const formState = reactive<API.UserUpdateRequest>({
  userName: '',
  userAvatar: '',
  userProfile: '',
})

const syncForm = () => {
  formState.userName = loginUser.value.userName || ''
  formState.userAvatar = loginUser.value.userAvatar || ''
  formState.userProfile = loginUser.value.userProfile || ''
}

const handleSubmit = async () => {
  submitting.value = true
  try {
    const res = await updateMyUser({ ...formState })
    if (res.data.code === 0 && res.data.data) {
      message.success('保存成功')
      await loginUserStore.fetchLoginUser()
      syncForm()
    } else {
      message.error(res.data.message || '保存失败')
    }
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (!loginUserStore.isLogin) {
    await loginUserStore.fetchLoginUser()
  }
  if (!loginUserStore.isLogin) {
    await router.push('/user/login')
    return
  }
  syncForm()
})
</script>

<template>
  <div class="settings-page">
    <div class="settings-panel">
      <h1 class="page-title">个人设置</h1>
      <div class="profile-preview">
        <a-avatar :src="getUserAvatar(formState.userAvatar)" :size="64" />
        <div class="profile-meta">
          <div class="profile-name">{{ formState.userName || loginUser.userAccount || '用户' }}</div>
          <div class="profile-account">{{ loginUser.userAccount }}</div>
        </div>
      </div>

      <a-form :model="formState" layout="vertical" @finish="handleSubmit">
        <a-form-item
          label="昵称"
          name="userName"
          :rules="[{ required: true, message: '请输入昵称' }]"
        >
          <a-input v-model:value="formState.userName" placeholder="请输入昵称" allow-clear />
        </a-form-item>
        <a-form-item label="头像地址" name="userAvatar">
          <a-input
            v-model:value="formState.userAvatar"
            placeholder="不填写时使用默认西高地小狗头像"
            allow-clear
          />
        </a-form-item>
        <a-form-item label="个人简介" name="userProfile">
          <a-textarea
            v-model:value="formState.userProfile"
            placeholder="请输入个人简介"
            :rows="4"
            allow-clear
          />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" html-type="submit" :loading="submitting">保存</a-button>
            <a-button @click="router.back()">返回</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>

<style scoped>
.settings-page {
  display: flex;
  justify-content: center;
  padding: 24px 16px;
}

.settings-panel {
  width: 100%;
  max-width: 560px;
  padding: 32px;
  background: #fff;
  border-radius: 8px;
}

.page-title {
  margin: 0 0 24px;
  font-size: 24px;
  font-weight: 600;
  color: #1f2937;
}

.profile-preview {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
}

.profile-name {
  font-size: 18px;
  font-weight: 600;
  color: #1f2937;
}

.profile-account {
  margin-top: 4px;
  color: #666;
}
</style>
