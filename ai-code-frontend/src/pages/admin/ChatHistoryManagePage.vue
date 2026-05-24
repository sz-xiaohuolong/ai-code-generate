<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { listChatHistoryVoByPageByAdmin } from '@/api/chatHistoryController'

const loading = ref(false)
const dataList = ref<API.ChatHistoryVO[]>([])
const total = ref(0)

const searchParams = reactive<API.ChatHistoryQueryRequest>({
  pageNum: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'descend',
  id: undefined,
  message: '',
  messageType: '',
  appId: undefined,
  userId: undefined,
})

const columns = [
  { title: 'id', dataIndex: 'id', width: 90 },
  { title: '消息内容', dataIndex: 'message', ellipsis: true },
  { title: '消息类型', dataIndex: 'messageType', width: 110 },
  { title: '应用 id', dataIndex: 'appId', width: 140 },
  { title: '用户 id', dataIndex: 'userId', width: 140 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '更新时间', dataIndex: 'updateTime', width: 180 },
]

const pagination = computed(() => ({
  current: searchParams.pageNum,
  pageSize: searchParams.pageSize,
  total: total.value,
  showSizeChanger: true,
  showTotal: (value: number) => `共 ${value} 条`,
}))

const buildSearchParams = () => {
  const params: API.ChatHistoryQueryRequest = {}
  Object.entries(searchParams).forEach(([key, value]) => {
    if (value !== '' && value !== undefined && value !== null) {
      params[key as keyof API.ChatHistoryQueryRequest] = value as never
    }
  })
  return params
}

const formatDateTime = (value?: string) => {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString()
}

const getMessageTypeText = (type?: string) => {
  if (type === 'user') {
    return '用户'
  }
  if (type === 'ai') {
    return 'AI'
  }
  return type || '-'
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await listChatHistoryVoByPageByAdmin(buildSearchParams())
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records || []
      total.value = res.data.data.totalRow || 0
    } else {
      message.error(res.data.message || '获取对话列表失败')
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
  searchParams.id = undefined
  searchParams.message = ''
  searchParams.messageType = ''
  searchParams.appId = undefined
  searchParams.userId = undefined
  loadData()
}

const doTableChange = (page: { current?: number; pageSize?: number }) => {
  searchParams.pageNum = page.current || 1
  searchParams.pageSize = page.pageSize || 10
  loadData()
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div class="chat-history-manage-page">
    <a-form class="search-form" layout="inline" :model="searchParams" @finish="handleSearch">
      <a-form-item label="id" name="id">
        <a-input-number v-model:value="searchParams.id" :min="1" placeholder="对话 id" />
      </a-form-item>
      <a-form-item label="消息" name="message">
        <a-input v-model:value="searchParams.message" placeholder="消息内容" allow-clear />
      </a-form-item>
      <a-form-item label="类型" name="messageType">
        <a-select
          v-model:value="searchParams.messageType"
          placeholder="全部类型"
          allow-clear
          class="search-select"
        >
          <a-select-option value="user">用户</a-select-option>
          <a-select-option value="ai">AI</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="应用 id" name="appId">
        <a-input-number v-model:value="searchParams.appId" :min="1" placeholder="应用 id" />
      </a-form-item>
      <a-form-item label="用户 id" name="userId">
        <a-input-number v-model:value="searchParams.userId" :min="1" placeholder="用户 id" />
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
        <template v-if="column.dataIndex === 'messageType'">
          <a-tag :color="record.messageType === 'ai' ? 'blue' : 'green'">
            {{ getMessageTypeText(record.messageType) }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ formatDateTime(record.createTime) }}
        </template>
        <template v-else-if="column.dataIndex === 'updateTime'">
          {{ formatDateTime(record.updateTime) }}
        </template>
      </template>
    </a-table>
  </div>
</template>

<style scoped>
.chat-history-manage-page {
  padding: 24px;
  background: #fff;
  border-radius: 8px;
}

.search-form {
  margin-bottom: 24px;
}

.search-select {
  width: 120px;
}

:deep(.ant-form-inline .ant-form-item) {
  margin-bottom: 16px;
}
</style>
