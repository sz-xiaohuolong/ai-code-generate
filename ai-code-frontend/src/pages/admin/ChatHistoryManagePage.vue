<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AdminPageShell from '@/components/AdminPageShell.vue'
import { listChatHistoryVoByPageByAdmin } from '@/api/chatHistoryController'

const loading = ref(false)
const loadError = ref('')
let loadSequence = 0
const dataList = ref<API.ChatHistoryVO[]>([])
const total = ref(0)
const searchId = ref('')
const searchAppId = ref('')
const searchUserId = ref('')

const searchParams = reactive<API.ChatHistoryQueryRequest>({
  pageNum: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'descend',
  message: '',
  messageType: '',
})

const columns = [
  { title: '消息内容', dataIndex: 'message', width: 360 },
  { title: '类型', dataIndex: 'messageType', width: 100 },
  { title: '关联应用 / 用户', key: 'related', width: 210 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '更新时间', dataIndex: 'updateTime', width: 180 },
]
const selectedMessage = ref<API.ChatHistoryVO | null>(null)

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
  if (searchId.value.trim()) params.id = searchId.value.trim() as unknown as number
  if (searchAppId.value.trim()) params.appId = searchAppId.value.trim() as unknown as number
  if (searchUserId.value.trim()) params.userId = searchUserId.value.trim() as unknown as number
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
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = ''
  try {
    const res = await listChatHistoryVoByPageByAdmin(buildSearchParams())
    if (sequence !== loadSequence) return
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records || []
      total.value = res.data.data.totalRow || 0
    } else {
      loadError.value = res.data.message || '获取对话列表失败'
    }
  } catch {
    if (sequence === loadSequence) loadError.value = '获取对话列表失败，请检查连接后重试。'
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
  searchId.value = ''
  searchParams.message = ''
  searchParams.messageType = ''
  searchAppId.value = ''
  searchUserId.value = ''
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
  <AdminPageShell
    title="对话管理"
    description="检索用户需求与 AI 回复，查看完整对话内容。"
    :count-label="loading ? '正在加载…' : loadError ? '' : `共 ${total} 条对话`"
  >
    <div class="chat-history-manage-page">
      <a-form class="search-form" layout="vertical" :model="searchParams" @finish="handleSearch">
        <a-form-item label="对话 ID" name="id">
          <a-input v-model:value="searchId" inputmode="numeric" placeholder="对话 ID" />
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
        <a-form-item label="应用 ID" name="appId">
          <a-input v-model:value="searchAppId" inputmode="numeric" placeholder="应用 ID" />
        </a-form-item>
        <a-form-item label="用户 ID" name="userId">
          <a-input v-model:value="searchUserId" inputmode="numeric" placeholder="用户 ID" />
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
        :scroll="{ x: 1030 }"
        @change="doTableChange"
      >
        <template #emptyText
          ><div class="admin-empty">
            <p>{{ loadError ? '列表加载失败，请重试。' : '暂无匹配记录，可调整筛选条件。' }}</p>
            <a-button v-if="!loadError" @click="handleReset">清空筛选</a-button>
          </div></template
        >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'message'">
            <button
              class="record-excerpt"
              type="button"
              :aria-label="`查看完整消息 ${record.id}`"
              @click="selectedMessage = record"
            >
              {{ record.message || '空消息' }}
            </button>
            <span class="record-secondary record-id">{{ record.id }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'messageType'"
            ><span class="record-status" :class="{ 'is-accent': record.messageType === 'ai' }">{{
              getMessageTypeText(record.messageType)
            }}</span></template
          >
          <template v-else-if="column.key === 'related'"
            ><span class="record-secondary record-id">应用 {{ record.appId || '—' }}</span
            ><span class="record-secondary record-id"
              >用户 {{ record.userId || '—' }}</span
            ></template
          >
          <template v-else-if="column.dataIndex === 'createTime'"
            ><time class="record-time">{{ formatDateTime(record.createTime) }}</time></template
          >
          <template v-else-if="column.dataIndex === 'updateTime'"
            ><time class="record-time">{{ formatDateTime(record.updateTime) }}</time></template
          >
        </template>
      </a-table>
      <a-drawer
        :open="!!selectedMessage"
        title="对话详情"
        width="min(600px, 100%)"
        @close="selectedMessage = null"
      >
        <div class="record-detail-meta">
          <span>{{ getMessageTypeText(selectedMessage?.messageType) }}</span>
          <time>{{ formatDateTime(selectedMessage?.createTime) }}</time>
          <span>对话 ID：{{ selectedMessage?.id }}</span>
          <span>应用 ID：{{ selectedMessage?.appId }}</span>
          <span>用户 ID：{{ selectedMessage?.userId }}</span>
        </div>
        <p class="record-detail">{{ selectedMessage?.message || '空消息' }}</p>
      </a-drawer>
    </div>
  </AdminPageShell>
</template>

<style scoped>
/* Hallmark · dismissed 49 · record-excerpt 是两行正文摘要，完整内容由详情抽屉展示，非普通按钮标签。 */
.chat-history-manage-page {
  padding: 0;
  background: var(--ui-surface);
  border-radius: 8px;
}

.search-form {
  margin-bottom: 0;
}

.search-select {
  width: 120px;
}

:deep(.ant-form-inline .ant-form-item) {
  margin-bottom: 16px;
}
</style>
