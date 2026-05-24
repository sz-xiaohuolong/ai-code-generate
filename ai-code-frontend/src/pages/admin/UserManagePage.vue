<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { deleteUser, listUserVoByPage, updateUser } from '@/api/userController'
import { getUserAvatar } from '@/constants/user'

const columns = [
  {
    title: 'id',
    dataIndex: 'id',
    width: 90,
  },
  {
    title: '账号',
    dataIndex: 'userAccount',
    ellipsis: true,
  },
  {
    title: '昵称',
    dataIndex: 'userName',
    ellipsis: true,
  },
  {
    title: '头像',
    dataIndex: 'userAvatar',
    width: 90,
  },
  {
    title: '简介',
    dataIndex: 'userProfile',
    ellipsis: true,
  },
  {
    title: '角色',
    dataIndex: 'userRole',
    width: 100,
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    width: 180,
  },
  {
    title: '操作',
    key: 'action',
    width: 150,
  },
]

const dataList = ref<API.UserVO[]>([])
const loading = ref(false)
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

const getRoleColor = (role?: string) => {
  if (role === 'admin') {
    return 'red'
  }
  if (role === 'ban') {
    return 'default'
  }
  return 'blue'
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
  loading.value = true
  try {
    const res = await listUserVoByPage({ ...searchParams })
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records || []
      total.value = res.data.data.totalRow || 0
    } else {
      message.error(res.data.message || '获取用户列表失败')
    }
  } finally {
    loading.value = false
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
  <div class="user-manage-page">
    <a-form class="search-form" layout="inline" :model="searchParams" @finish="handleSearch">
      <a-form-item label="账号" name="userAccount">
        <a-input v-model:value="searchParams.userAccount" placeholder="请输入账号" allow-clear />
      </a-form-item>
      <a-form-item label="昵称" name="userName">
        <a-input v-model:value="searchParams.userName" placeholder="请输入昵称" allow-clear />
      </a-form-item>
      <a-form-item>
        <a-space>
          <a-button type="primary" html-type="submit">搜索</a-button>
          <a-button @click="handleReset">重置</a-button>
        </a-space>
      </a-form-item>
    </a-form>

    <a-table
      row-key="id"
      :columns="columns"
      :data-source="dataList"
      :loading="loading"
      :pagination="pagination"
      @change="doTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'userAvatar'">
          <a-avatar :src="getUserAvatar(record.userAvatar)" />
        </template>
        <template v-else-if="column.dataIndex === 'userRole'">
          <a-tag :color="getRoleColor(record.userRole)">
            {{ getRoleText(record.userRole) }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ formatDateTime(record.createTime) }}
        </template>
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
      <a-form :model="editForm" layout="vertical">
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
</template>

<style scoped>
.user-manage-page {
  padding: 24px;
  background: #fff;
  border-radius: 8px;
}

.search-form {
  margin-bottom: 24px;
}

:deep(.ant-form-inline .ant-form-item) {
  margin-bottom: 16px;
}
</style>
