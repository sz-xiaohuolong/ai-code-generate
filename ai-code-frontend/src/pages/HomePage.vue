<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import AppCard from '@/components/AppCard.vue'
import { addApp, listFeaturedAppVoByPage, listMyAppVoByPage } from '@/api/appController'

const router = useRouter()

const prompt = ref('')
const creating = ref(false)
const myApps = ref<API.AppVO[]>([])
const featuredApps = ref<API.AppVO[]>([])
const myLoading = ref(false)
const featuredLoading = ref(false)

const mySearch = reactive<API.AppQueryRequest>({
  pageNum: 1,
  pageSize: 6,
  sortField: 'createTime',
  sortOrder: 'descend',
  appName: '',
})

const featuredSearch = reactive<API.AppQueryRequest>({
  pageNum: 1,
  pageSize: 6,
  sortField: 'createTime',
  sortOrder: 'descend',
  appName: '',
})

const myTotal = ref(0)
const featuredTotal = ref(0)

const promptSamples = [
  {
    label: '个人博客网站',
    prompt:
      '帮我创建一个现代化个人博客网站，首页展示个人介绍、精选文章、技术标签、项目经历和联系入口，整体风格简洁高级，适合程序员记录技术文章、项目经验和生活思考，要求响应式布局，移动端浏览也要清晰好看。',
  },
  {
    label: '企业官网',
    prompt:
      '帮我创建一个科技企业官网，包含顶部导航、品牌介绍、核心产品、服务优势、客户案例、合作伙伴和联系我们模块，整体视觉要专业可信，使用蓝紫色科技渐变背景，适合 SaaS 公司展示业务并引导客户咨询。',
  },
  {
    label: '电商商品页',
    prompt:
      '帮我创建一个电商商品详情页，展示商品大图、价格、优惠信息、卖点标签、规格选择、购买按钮、用户评价、售后保障和推荐商品，风格清爽有转化感，适合数码产品或智能硬件销售场景。',
  },
  {
    label: '作品集页面',
    prompt:
      '帮我创建一个设计师作品集网站，首页突出个人姓名、职业定位、精选作品网格、服务能力、合作流程、客户评价和联系方式，要求视觉有创意但信息清晰，适配桌面端和移动端浏览，适合求职和接单展示。',
  },
]

const loadMyApps = async () => {
  myLoading.value = true
  try {
    const res = await listMyAppVoByPage({ ...mySearch })
    if (res.data.code === 0 && res.data.data) {
      myApps.value = res.data.data.records || []
      myTotal.value = res.data.data.totalRow || 0
    }
  } finally {
    myLoading.value = false
  }
}

const loadFeaturedApps = async () => {
  featuredLoading.value = true
  try {
    const res = await listFeaturedAppVoByPage({ ...featuredSearch })
    if (res.data.code === 0 && res.data.data) {
      featuredApps.value = res.data.data.records || []
      featuredTotal.value = res.data.data.totalRow || 0
    }
  } finally {
    featuredLoading.value = false
  }
}

const createApp = async () => {
  const initPrompt = prompt.value.trim()
  if (!initPrompt) {
    message.warning('请输入你想创建的应用')
    return
  }
  creating.value = true
  try {
    const res = await addApp({ initPrompt })
    if (res.data.code === 0 && res.data.data) {
      await router.push(`/app/chat/${res.data.data}`)
    } else {
      message.error(res.data.message || '创建应用失败')
    }
  } finally {
    creating.value = false
  }
}

const useSample = (value: string) => {
  prompt.value = value
}

const searchMyApps = () => {
  mySearch.pageNum = 1
  loadMyApps()
}

const searchFeaturedApps = () => {
  featuredSearch.pageNum = 1
  loadFeaturedApps()
}

onMounted(() => {
  loadMyApps()
  loadFeaturedApps()
})
</script>

<template>
  <main class="home-page">
    <section class="hero-section">
      <h1 class="brand">AI 应用生成平台</h1>
      <p class="subtitle">一句话轻松创建网站应用</p>
      <div class="prompt-box">
        <a-textarea
          v-model:value="prompt"
          placeholder="帮我创建个人博客网站"
          :rows="5"
          :maxlength="800"
          show-count
          @press-enter.ctrl="createApp"
        />
        <div class="prompt-actions">
          <a-button type="primary" shape="circle" :loading="creating" @click="createApp">↑</a-button>
        </div>
      </div>
      <div class="sample-list">
        <a-button
          v-for="sample in promptSamples"
          :key="sample.label"
          :title="sample.prompt"
          @click="useSample(sample.prompt)"
        >
          {{ sample.label }}
        </a-button>
      </div>
    </section>

    <section class="app-section">
      <div class="section-header">
        <h2>我的作品</h2>
        <a-input-search
          v-model:value="mySearch.appName"
          placeholder="搜索我的应用"
          class="section-search"
          allow-clear
          @search="searchMyApps"
        />
      </div>
      <a-spin :spinning="myLoading">
        <div v-if="myApps.length" class="app-grid">
          <AppCard v-for="app in myApps" :key="app.id" :app="app" />
        </div>
        <a-empty v-else description="暂无应用" />
      </a-spin>
      <a-pagination
        v-model:current="mySearch.pageNum"
        v-model:page-size="mySearch.pageSize"
        :total="myTotal"
        :page-size-options="['6', '12', '20']"
        show-size-changer
        class="section-pagination"
        @change="loadMyApps"
        @show-size-change="loadMyApps"
      />
    </section>

    <section class="app-section">
      <div class="section-header">
        <h2>精选案例</h2>
        <a-input-search
          v-model:value="featuredSearch.appName"
          placeholder="搜索精选应用"
          class="section-search"
          allow-clear
          @search="searchFeaturedApps"
        />
      </div>
      <a-spin :spinning="featuredLoading">
        <div v-if="featuredApps.length" class="app-grid">
          <AppCard v-for="app in featuredApps" :key="app.id" :app="app" featured />
        </div>
        <a-empty v-else description="暂无精选应用" />
      </a-spin>
      <a-pagination
        v-model:current="featuredSearch.pageNum"
        v-model:page-size="featuredSearch.pageSize"
        :total="featuredTotal"
        :page-size-options="['6', '12', '20']"
        show-size-changer
        class="section-pagination"
        @change="loadFeaturedApps"
        @show-size-change="loadFeaturedApps"
      />
    </section>
  </main>
</template>

<style scoped>
.home-page {
  width: 100vw;
  min-height: 100vh;
  margin: 0 0 0 calc(50% - 50vw);
  padding: 0 40px 56px;
  background:
    radial-gradient(circle at 82% 16%, rgba(125, 249, 255, 0.46), transparent 29%),
    radial-gradient(circle at 15% 75%, rgba(34, 197, 94, 0.18), transparent 30%),
    radial-gradient(circle at 82% 84%, rgba(37, 99, 235, 0.54), transparent 36%),
    linear-gradient(180deg, #fbfefc 0%, #f5fbff 26%, #69e4eb 60%, #3678ff 100%);
}

.hero-section {
  position: relative;
  width: 100%;
  min-height: 760px;
  padding: 156px 24px 150px;
  text-align: center;
  overflow: hidden;
}

.hero-section::before {
  position: absolute;
  inset: 0;
  pointer-events: none;
  content: '';
  background-image:
    linear-gradient(rgba(15, 23, 42, 0.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(15, 23, 42, 0.035) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: linear-gradient(180deg, transparent 0%, #000 22%, transparent 82%);
}

.hero-section::after {
  position: absolute;
  right: 8%;
  bottom: 74px;
  width: 520px;
  height: 220px;
  pointer-events: none;
  content: '';
  background: radial-gradient(ellipse at center, rgba(255, 255, 255, 0.48), transparent 68%);
  filter: blur(8px);
}

.brand {
  position: relative;
  z-index: 1;
  margin: 0;
  color: #0f172a;
  font-size: 52px;
  font-weight: 800;
  letter-spacing: 0;
  text-shadow: 0 18px 44px rgba(59, 130, 246, 0.16);
}

.subtitle {
  position: relative;
  z-index: 1;
  margin: 18px 0 40px;
  color: rgba(15, 23, 42, 0.62);
  font-size: 22px;
}

.prompt-box {
  position: relative;
  z-index: 1;
  max-width: 980px;
  margin: 0 auto;
  padding: 18px 20px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(255, 255, 255, 0.72);
  border-radius: 28px;
  box-shadow:
    0 34px 110px rgba(30, 64, 175, 0.2),
    inset 0 1px 0 rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(22px);
}

.prompt-box :deep(.ant-input) {
  border: 0;
  background: transparent;
  box-shadow: none;
  font-size: 18px;
  resize: none;
}

.prompt-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-top: 12px;
}

.sample-list {
  position: relative;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 14px;
  margin-top: 28px;
}

.sample-list :deep(.ant-btn) {
  height: 38px;
  color: rgba(15, 23, 42, 0.72);
  background: rgba(255, 255, 255, 0.72);
  border-color: rgba(255, 255, 255, 0.84);
  border-radius: 12px;
  box-shadow: 0 12px 28px rgba(37, 99, 235, 0.12);
  backdrop-filter: blur(12px);
}

.sample-list :deep(.ant-btn:hover) {
  color: #0f172a;
  background: rgba(255, 255, 255, 0.92);
  border-color: rgba(255, 255, 255, 0.96);
}

.app-section {
  position: relative;
  z-index: 2;
  max-width: 1660px;
  margin: 0 auto 32px;
  padding: 42px;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid rgba(255, 255, 255, 0.76);
  border-radius: 32px;
  box-shadow: 0 28px 90px rgba(29, 78, 216, 0.18);
  backdrop-filter: blur(14px);
}

.hero-section + .app-section {
  margin-top: -58px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}

.section-header h2 {
  margin: 0;
  color: #111827;
  font-size: 28px;
  font-weight: 800;
}

.section-search {
  width: 280px;
}

.app-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 28px;
}

.section-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 24px;
}

@media (max-width: 900px) {
  .home-page {
    padding: 0 16px 32px;
  }

  .hero-section {
    min-height: 640px;
    padding: 126px 0 120px;
  }

  .brand {
    font-size: 32px;
  }

  .subtitle {
    font-size: 17px;
  }

  .prompt-box {
    border-radius: 20px;
  }

  .app-section {
    padding: 24px;
    border-radius: 24px;
  }

  .app-grid {
    grid-template-columns: 1fr;
  }

  .section-header {
    align-items: stretch;
    flex-direction: column;
  }

  .section-search {
    width: 100%;
  }
}
</style>
