<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  deleteAppByAdmin,
  listAppVoByPageByAdmin,
  updateAppByAdmin,
} from '@/api/appController'
import { CODE_GEN_TYPE_CONFIG, CodeGenTypeEnum } from '@/constants/codeGenType'

const router = useRouter()
const loading = ref(false)
const dataList = ref<API.AppVO[]>([])
const total = ref(0)

const searchParams = reactive<API.AppQueryRequest>({
  pageNum: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'descend',
  appName: '',
  initPrompt: '',
  codeGenType: '',
  deployKey: '',
  priority: undefined,
  userId: undefined,
})

const columns = [
  { title: 'id', dataIndex: 'id', width: 90 },
  { title: '名称', dataIndex: 'appName', ellipsis: true },
  { title: '封面', dataIndex: 'cover', width: 90 },
  { title: '提示词', dataIndex: 'initPrompt', ellipsis: true },
  { title: '类型', dataIndex: 'codeGenType', width: 120 },
  { title: '优先级', dataIndex: 'priority', width: 90 },
  { title: '创建用户', dataIndex: 'user', width: 140 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 180 },
]

const pagination = computed(() => ({
  current: searchParams.pageNum,
  pageSize: searchParams.pageSize,
  total: total.value,
  showSizeChanger: true,
  showTotal: (value: number) => `共 ${value} 条`,
}))

const buildSearchParams = () => {
  const params: API.AppQueryRequest = {}
  Object.entries(searchParams).forEach(([key, value]) => {
    if (value !== '' && value !== undefined && value !== null) {
      params[key as keyof API.AppQueryRequest] = value as never
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

const getCodeGenTypeLabel = (type?: string) => {
  return CODE_GEN_TYPE_CONFIG[type as CodeGenTypeEnum]?.label || type || '-'
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await listAppVoByPageByAdmin(buildSearchParams())
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records || []
      total.value = res.data.data.totalRow || 0
    } else {
      message.error(res.data.message || '获取应用列表失败')
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
  searchParams.appName = ''
  searchParams.initPrompt = ''
  searchParams.codeGenType = ''
  searchParams.deployKey = ''
  searchParams.priority = undefined
  searchParams.userId = undefined
  loadData()
}

const doTableChange = (page: { current?: number; pageSize?: number }) => {
  searchParams.pageNum = page.current || 1
  searchParams.pageSize = page.pageSize || 10
  loadData()
}

const goEdit = (record: API.AppVO) => {
  if (record.id) {
    router.push({
      path: `/app/edit/${record.id}`,
      query: {
        admin: '1',
      },
    })
  }
}

const handleFeatured = async (record: API.AppVO) => {
  if (!record.id) {
    return
  }
  const isFeatured = record.priority === 99
  const res = await updateAppByAdmin({
    id: record.id,
    priority: isFeatured ? 0 : 99,
  })
  if (res.data.code === 0 && res.data.data) {
    message.success(isFeatured ? '已取消精选' : '已设为精选')
    loadData()
  } else {
    message.error(res.data.message || (isFeatured ? '取消精选失败' : '设置精选失败'))
  }
}

const handleDelete = async (record: API.AppVO) => {
  if (!record.id) {
    return
  }
  const res = await deleteAppByAdmin({ id: record.id })
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
  <div class="app-manage-page">
    <a-form class="search-form" layout="inline" :model="searchParams" @finish="handleSearch">
      <a-form-item label="名称" name="appName">
        <a-input v-model:value="searchParams.appName" placeholder="应用名称" allow-clear />
      </a-form-item>
      <a-form-item label="提示词" name="initPrompt">
        <a-input v-model:value="searchParams.initPrompt" placeholder="初始化提示词" allow-clear />
      </a-form-item>
      <a-form-item label="类型" name="codeGenType">
        <a-select
          v-model:value="searchParams.codeGenType"
          placeholder="全部类型"
          allow-clear
          class="search-select"
        >
          <a-select-option
            v-for="item in CODE_GEN_TYPE_CONFIG"
            :key="item.value"
            :value="item.value"
          >
            {{ item.label }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="优先级" name="priority">
        <a-input-number v-model:value="searchParams.priority" :min="0" placeholder="优先级" />
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
        <template v-if="column.dataIndex === 'cover'">
          <a-image v-if="record.cover" :src="record.cover" :width="48" :height="32" />
          <span v-else>-</span>
        </template>
        <template v-else-if="column.dataIndex === 'codeGenType'">
          {{ getCodeGenTypeLabel(record.codeGenType) }}
        </template>
        <template v-else-if="column.dataIndex === 'priority'">
          <a-tag :color="record.priority === 99 ? 'gold' : 'default'">
            {{ record.priority ?? 0 }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'user'">
          {{ record.user?.userName || record.user?.userAccount || record.userId || '-' }}
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ formatDateTime(record.createTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a-button type="link" @click="goEdit(record)">编辑</a-button>
            <a-button type="link" @click="handleFeatured(record)">
              {{ record.priority === 99 ? '取消精选' : '精选' }}
            </a-button>
            <a-popconfirm
              title="确定删除该应用吗？"
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
  </div>
</template>

<style scoped>
.app-manage-page {
  padding: 24px;
  background: #fff;
  border-radius: 8px;
}

.search-form {
  margin-bottom: 24px;
}

.search-select {
  width: 140px;
}

:deep(.ant-form-inline .ant-form-item) {
  margin-bottom: 16px;
}
</style>
