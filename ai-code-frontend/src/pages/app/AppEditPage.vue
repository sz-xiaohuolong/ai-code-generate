<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { storeToRefs } from 'pinia'
import {
  deleteApp,
  deleteAppByAdmin,
  getAppVoById,
  getAppVoByIdByAdmin,
  updateApp,
  updateAppByAdmin,
} from '@/api/appController'
import { useLoginUserStore } from '@/stores/loginUser'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()
const { loginUser } = storeToRefs(loginUserStore)
const appId = computed(() => String(route.params.id))
const loading = ref(false)
const saving = ref(false)
const deleting = ref(false)
const appInfo = ref<API.AppVO>({})

const isAdmin = computed(() => loginUser.value.userRole === 'admin')
const useAdminApi = computed(() => isAdmin.value && route.query.admin === '1')
const isOwner = computed(() => String(appInfo.value.userId) === String(loginUser.value.id))

const formState = reactive<API.AppAdminUpdateRequest>({
  id: undefined,
  appName: '',
  cover: '',
  priority: 0,
})

const loadData = async () => {
  loading.value = true
  try {
    if (!loginUserStore.isLogin) {
      await loginUserStore.fetchLoginUser()
    }
    const res = useAdminApi.value
      ? await getAppVoByIdByAdmin({ id: appId.value as unknown as number })
      : await getAppVoById({ id: appId.value as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      appInfo.value = res.data.data
      if (!isAdmin.value && String(res.data.data.userId) !== String(loginUser.value.id)) {
        message.warning('只能编辑自己的应用')
        await router.push('/')
        return
      }
      formState.id = res.data.data.id
      formState.appName = res.data.data.appName || ''
      formState.cover = res.data.data.cover || ''
      formState.priority = res.data.data.priority || 0
    } else {
      message.error(res.data.message || '获取应用详情失败')
    }
  } finally {
    loading.value = false
  }
}

const handleSubmit = async () => {
  if (!formState.id) {
    return
  }
  saving.value = true
  try {
    const res = useAdminApi.value
      ? await updateAppByAdmin({ ...formState })
      : await updateApp({
          id: formState.id,
          appName: formState.appName,
        })
    if (res.data.code === 0 && res.data.data) {
      message.success('保存成功')
      await loadData()
    } else {
      message.error(res.data.message || '保存失败')
    }
  } finally {
    saving.value = false
  }
}

const handleDelete = async () => {
  if (!formState.id) {
    return
  }
  deleting.value = true
  try {
    const res = useAdminApi.value
      ? await deleteAppByAdmin({ id: formState.id })
      : await deleteApp({ id: formState.id })
    if (res.data.code === 0 && res.data.data) {
      message.success('删除成功')
      await router.push(useAdminApi.value ? '/admin/appManage' : '/')
    } else {
      message.error(res.data.message || '删除失败')
    }
  } finally {
    deleting.value = false
  }
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div class="app-edit-page">
    <a-spin :spinning="loading">
      <div class="edit-panel">
        <div class="page-header">
          <div>
            <h1>应用信息修改</h1>
            <p>{{ isAdmin ? '管理员可编辑应用名称、封面和优先级' : '普通用户仅可编辑自己的应用名称' }}</p>
          </div>
          <a-button @click="router.push(`/app/chat/${appId}`)">查看应用</a-button>
        </div>

        <a-form :model="formState" layout="vertical" @finish="handleSubmit">
          <a-form-item
            label="应用名称"
            name="appName"
            :rules="[{ required: true, message: '请输入应用名称' }]"
          >
            <a-input v-model:value="formState.appName" placeholder="请输入应用名称" allow-clear />
          </a-form-item>
          <a-form-item v-if="isAdmin" label="应用封面" name="cover">
            <a-input v-model:value="formState.cover" placeholder="请输入封面图片地址" allow-clear />
          </a-form-item>
          <a-form-item v-if="isAdmin" label="优先级" name="priority">
            <a-input-number v-model:value="formState.priority" :min="0" class="priority-input" />
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" html-type="submit" :loading="saving">保存</a-button>
              <a-popconfirm
                v-if="isOwner || isAdmin"
                title="确定删除该应用吗？"
                ok-text="删除"
                cancel-text="取消"
                @confirm="handleDelete"
              >
                <a-button danger :loading="deleting">删除应用</a-button>
              </a-popconfirm>
              <a-button @click="router.back()">返回</a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </div>
    </a-spin>
  </div>
</template>

<style scoped>
.app-edit-page {
  display: flex;
  justify-content: center;
  padding: 24px 16px;
}

.edit-panel {
  width: 100%;
  max-width: 640px;
  padding: 32px;
  background: #fff;
  border-radius: 8px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}

.page-header h1 {
  margin: 0;
  color: #111827;
  font-size: 24px;
  font-weight: 700;
}

.page-header p {
  margin: 6px 0 0;
  color: #6b7280;
}

.priority-input {
  width: 180px;
}
</style>
