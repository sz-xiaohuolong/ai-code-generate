<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import AdminPageShell from '@/components/AdminPageShell.vue'
import { deleteUser, listUserVoByPage, updateUser } from '@/api/userController'
import { getUserAvatar } from '@/constants/user'

const columns = [
  { title: '用户', key: 'identity', width: 300 },
  { title: '个人简介', dataIndex: 'userProfile', ellipsis: true },
  { title: '角色', dataIndex: 'userRole', width: 120 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 150 },
]

const dataList = ref<API.UserVO[]>([])
const loading = ref(false)
const loadError = ref('')
let loadSequence = 0
const editModalVisible = ref(false)
const editSubmitting = ref(false)

const searchParams = reactive<API.UserQueryRequest>({
  pageNum: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'descend',
  userAccount: '',
  userName: '',
})

const total = ref(0)

const editForm = reactive<API.UserUpdateRequest>({
  id: undefined,
  userName: '',
  userAvatar: '',
  userProfile: '',
  userRole: 'user',
})

const pagination = computed(() => ({
  current: searchParams.pageNum,
  pageSize: searchParams.pageSize,
  total: total.value,
  showSizeChanger: true,
  showTotal: (value: number) => `共 ${value} 条`,
}))

const formatDateTime = (value?: string) => {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString()
}

const getRoleText = (role?: string) => {
  if (role === 'admin') {
    return '管理员'
  }
  if (role === 'ban') {
    return '封禁'
  }
  return '普通用户'
}

const loadData = async () => {
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = ''
  try {
    const res = await listUserVoByPage({ ...searchParams })
    if (sequence !== loadSequence) return
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records || []
      total.value = res.data.data.totalRow || 0
    } else {
      loadError.value = res.data.message || '获取用户列表失败'
    }
  } catch {
    if (sequence === loadSequence) loadError.value = '获取用户列表失败，请检查连接后重试。'
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const handleSearch = () => {
  searchParams.pageNum = 1
  loadData()
}

const handleReset = () => {
  searchParams.pageNum = 1
  searchParams.userAccount = ''
  searchParams.userName = ''
  loadData()
}

const doTableChange = (page: { current?: number; pageSize?: number }) => {
  searchParams.pageNum = page.current || 1
  searchParams.pageSize = page.pageSize || 10
  loadData()
}

const openEditModal = (record: API.UserVO) => {
  editForm.id = record.id
  editForm.userName = record.userName || ''
  editForm.userAvatar = record.userAvatar || ''
  editForm.userProfile = record.userProfile || ''
  editForm.userRole = record.userRole || 'user'
  editModalVisible.value = true
}

const handleEditSubmit = async () => {
  if (!editForm.id) {
    return
  }
  editSubmitting.value = true
  try {
    const res = await updateUser({ ...editForm })
    if (res.data.code === 0 && res.data.data) {
      message.success('更新成功')
      editModalVisible.value = false
      loadData()
    } else {
      message.error(res.data.message || '更新失败')
    }
  } finally {
    editSubmitting.value = false
  }
}

const handleDelete = async (record: API.UserVO) => {
  if (!record.id) {
    return
  }
  const res = await deleteUser({ id: record.id })
  if (res.data.code === 0 && res.data.data) {
    message.success('删除成功')
    if (dataList.value.length === 1 && (searchParams.pageNum || 1) > 1) {
      searchParams.pageNum = (searchParams.pageNum || 1) - 1
    }
    loadData()
  } else {
    message.error(res.data.message || '删除失败')
  }
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <AdminPageShell
    title="用户管理"
    description="维护账户信息与角色，快速找到需要处理的用户。"
    :count-label="loading ? '正在加载…' : loadError ? '' : `共 ${total} 位用户`"
  >
    <div class="user-manage-page">
      <a-form class="search-form" layout="vertical" :model="searchParams" @finish="handleSearch">
        <a-form-item label="账号" name="userAccount">
          <a-input v-model:value="searchParams.userAccount" placeholder="请输入账号" allow-clear />
        </a-form-item>
        <a-form-item label="昵称" name="userName">
          <a-input v-model:value="searchParams.userName" placeholder="请输入昵称" allow-clear />
        </a-form-item>
        <a-form-item class="search-actions">
          <a-space>
            <a-button type="primary" html-type="submit">搜索</a-button>
            <a-button @click="handleReset">重置</a-button>
          </a-space>
        </a-form-item>
      </a-form>

      <div v-if="loadError" class="admin-inline-error" role="alert">
        <p>{{ loadError }}<span v-if="dataList.length"> 当前显示上次加载的记录。</span></p>
        <a-button :loading="loading" @click="loadData">重新加载</a-button>
      </div>
      <div
        v-if="loading && !dataList.length"
        class="admin-loading"
        role="status"
        aria-label="正在加载列表"
      >
        <a-skeleton active :paragraph="{ rows: 6 }" />
      </div>
      <a-table
        v-else
        row-key="id"
        :columns="columns"
        :data-source="dataList"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 980 }"
        @change="doTableChange"
      >
        <template #emptyText
          ><div class="admin-empty">
            <p>{{ loadError ? '列表加载失败，请重试。' : '暂无匹配记录，可调整筛选条件。' }}</p>
            <a-button v-if="!loadError" @click="handleReset">清空筛选</a-button>
          </div></template
        >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'identity'">
            <div class="record-identity">
              <a-avatar :src="getUserAvatar(record.userAvatar)" :size="38" />
              <div class="record-identity-text">
                <div class="record-user-heading">
                  <span class="record-name" :title="record.userName">{{
                    record.userName || '未设置昵称'
                  }}</span>
                  <span class="record-account" :title="record.userAccount">{{
                    record.userAccount
                  }}</span>
                </div>
                <span class="record-secondary record-id">{{ record.id }}</span>
              </div>
            </div>
          </template>
          <template v-else-if="column.dataIndex === 'userRole'">
            <span
              class="record-status"
              :class="{
                'is-accent': record.userRole === 'admin',
                'is-danger': record.userRole === 'ban',
              }"
              >{{ getRoleText(record.userRole) }}</span
            >
          </template>
          <template v-else-if="column.dataIndex === 'createTime'"
            ><time class="record-time">{{ formatDateTime(record.createTime) }}</time></template
          >
          <template v-else-if="column.dataIndex === 'userProfile'"
            ><span class="record-excerpt" :title="record.userProfile">{{
              record.userProfile || '暂无简介'
            }}</span></template
          >
          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button type="link" @click="openEditModal(record)">编辑</a-button>
              <a-popconfirm
                title="确定删除该用户吗？"
                ok-text="删除"
                cancel-text="取消"
                @confirm="handleDelete(record)"
              >
                <a-button type="link" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>

      <a-modal
        v-model:open="editModalVisible"
        title="编辑用户"
        :confirm-loading="editSubmitting"
        @ok="handleEditSubmit"
      >
        <a-form name="user-edit" :model="editForm" layout="vertical">
          <a-form-item label="昵称" name="userName">
            <a-input v-model:value="editForm.userName" placeholder="请输入昵称" allow-clear />
          </a-form-item>
          <a-form-item label="头像地址" name="userAvatar">
            <a-input
              v-model:value="editForm.userAvatar"
              placeholder="不填写时使用默认西高地小狗头像"
              allow-clear
            />
          </a-form-item>
          <a-form-item label="简介" name="userProfile">
            <a-textarea
              v-model:value="editForm.userProfile"
              placeholder="请输入简介"
              :rows="4"
              allow-clear
            />
          </a-form-item>
          <a-form-item label="角色" name="userRole">
            <a-select v-model:value="editForm.userRole">
              <a-select-option value="user">普通用户</a-select-option>
              <a-select-option value="admin">管理员</a-select-option>
              <a-select-option value="ban">封禁</a-select-option>
            </a-select>
          </a-form-item>
        </a-form>
      </a-modal>
    </div>
  </AdminPageShell>
</template>

<style scoped>
.user-manage-page {
  padding: 0;
  background: var(--ui-surface);
  border-radius: 8px;
}

.search-form {
  margin-bottom: 0;
}

:deep(.ant-form-inline .ant-form-item) {
  margin-bottom: 16px;
}
</style>
