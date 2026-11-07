<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import AdminPageShell from '@/components/AdminPageShell.vue'
import { deleteAppByAdmin, listAppVoByPageByAdmin, updateAppByAdmin } from '@/api/appController'
import { CODE_GEN_TYPE_CONFIG, CodeGenTypeEnum } from '@/constants/codeGenType'

const router = useRouter()
const loading = ref(false)
const loadError = ref('')
let loadSequence = 0
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
  { title: '应用', key: 'identity', width: 260 },
  { title: '初始需求', dataIndex: 'initPrompt', width: 230 },
  { title: '类型', dataIndex: 'codeGenType', width: 110 },
  { title: '优先级', dataIndex: 'priority', width: 100 },
  { title: '创建用户', dataIndex: 'user', width: 130 },
  { title: '创建时间', dataIndex: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 190 },
]
const selectedPrompt = ref<API.AppVO | null>(null)
const failedCovers = ref(new Set<string>())

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
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = ''
  try {
    const res = await listAppVoByPageByAdmin(buildSearchParams())
    if (sequence !== loadSequence) return
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records || []
      total.value = res.data.data.totalRow || 0
    } else {
      loadError.value = res.data.message || '获取应用列表失败'
    }
  } catch {
    if (sequence === loadSequence) loadError.value = '获取应用列表失败，请检查连接后重试。'
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
  <AdminPageShell
    title="应用管理"
    description="查看平台作品，管理应用信息与精选内容。"
    :count-label="loading ? '正在加载…' : loadError ? '' : `共 ${total} 个应用`"
  >
    <div class="app-manage-page">
      <a-form class="search-form" layout="vertical" :model="searchParams" @finish="handleSearch">
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
        :scroll="{ x: 1190 }"
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
              <div class="record-cover">
                <a-image
                  v-if="record.cover && !failedCovers.has(String(record.id))"
                  :width="56"
                  :height="42"
                  :src="record.cover"
                  :alt="`${record.appName}的封面`"
                  @error="failedCovers.add(String(record.id))"
                /><span v-else>无封面</span>
              </div>
              <div class="record-identity-text">
                <span class="record-name" :title="record.appName">{{
                  record.appName || '未命名应用'
                }}</span
                ><span class="record-secondary record-id">{{ record.id }}</span>
              </div>
            </div>
          </template>
          <template v-else-if="column.dataIndex === 'initPrompt'"
            ><button
              class="record-excerpt"
              type="button"
              :aria-label="`查看${record.appName || '应用'}的完整需求`"
              @click="selectedPrompt = record"
            >
              {{ record.initPrompt || '暂无初始需求' }}
            </button></template
          >
          <template v-else-if="column.dataIndex === 'codeGenType'"
            ><span class="record-status">{{
              getCodeGenTypeLabel(record.codeGenType)
            }}</span></template
          >
          <template v-else-if="column.dataIndex === 'priority'"
            ><span class="record-status" :class="{ 'is-accent': record.priority === 99 }">{{
              record.priority === 99 ? '精选（99）' : `普通（${record.priority ?? 0}）`
            }}</span></template
          >
          <template v-else-if="column.dataIndex === 'user'"
            ><span class="record-excerpt">{{
              record.user?.userName || record.user?.userAccount || record.userId || '未知用户'
            }}</span></template
          >
          <template v-else-if="column.dataIndex === 'createTime'"
            ><time class="record-time">{{ formatDateTime(record.createTime) }}</time></template
          >
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
      <a-drawer
        :open="!!selectedPrompt"
        title="初始需求"
        width="min(520px, 100%)"
        @close="selectedPrompt = null"
      >
        <div class="record-detail-meta">
          <strong>{{ selectedPrompt?.appName || '未命名应用' }}</strong
          ><span>应用 ID：{{ selectedPrompt?.id }}</span>
        </div>
        <p class="record-detail">{{ selectedPrompt?.initPrompt || '暂无初始需求' }}</p>
      </a-drawer>
    </div>
  </AdminPageShell>
</template>

<style scoped>
/* Hallmark · dismissed 49 · record-excerpt 是两行正文摘要，完整内容由详情抽屉展示，非普通按钮标签。 */
.app-manage-page {
  padding: 0;
  background: var(--ui-surface);
  border-radius: 8px;
}

.search-form {
  margin-bottom: 0;
}

.search-select {
  width: 140px;
}

:deep(.ant-form-inline .ant-form-item) {
  margin-bottom: 16px;
}
</style>
