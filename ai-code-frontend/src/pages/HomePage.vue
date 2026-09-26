<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ArrowUpOutlined } from '@ant-design/icons-vue'
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
    <section class="hero-section" aria-labelledby="home-title">
      <div class="hero-inner">
        <div class="hero-badge">
          <span class="badge-dot" />
          <span>新一代 AI 全栈应用生成</span>
        </div>
        <h1 id="home-title" class="hero-title">用自然语言，创造你的 Web 应用</h1>
        <p class="hero-subtitle">
          描述你的产品创意，AI 将实时生成交互完备、响应式的单页面与多文件应用
        </p>

        <div class="creation-studio">
          <div class="prompt-box">
            <a-textarea
              v-model:value="prompt"
              aria-label="描述你想创建的应用"
              placeholder="例如：帮我创建一个极简暗黑风格的 SaaS 产品官网，包含核心优势、特性对比、定价方案和预约演示..."
              :rows="4"
              :maxlength="800"
              show-count
              @press-enter.ctrl="createApp"
            />
            <div class="prompt-actions">
              <div class="prompt-tips">
                <span class="kbd-hint">Ctrl + Enter</span>
                <span>快速开始构建</span>
              </div>
              <a-tooltip title="立即创建并开始生成">
                <a-button
                  type="primary"
                  shape="circle"
                  size="large"
                  :loading="creating"
                  aria-label="创建应用"
                  class="submit-circle-btn"
                  @click="createApp"
                >
                  <ArrowUpOutlined />
                </a-button>
              </a-tooltip>
            </div>
          </div>

          <div class="sample-list" aria-label="灵感示例">
            <span class="samples-label">灵感探索：</span>
            <button
              v-for="sample in promptSamples"
              :key="sample.label"
              type="button"
              class="sample-chip"
              :title="sample.prompt"
              @click="prompt = sample.prompt"
            >
              {{ sample.label }}
            </button>
          </div>
        </div>
      </div>
    </section>

    <div class="library">
      <section class="app-section">
        <div class="section-header">
          <div>
            <h2>我的作品</h2>
            <p>继续完善与迭代已创建的 Web 应用</p>
          </div>
          <a-input-search
            v-model:value="mySearch.appName"
            placeholder="搜索我的应用..."
            aria-label="搜索我的应用"
            class="section-search"
            allow-clear
            @search="searchMyApps"
          />
        </div>
        <a-spin :spinning="myLoading">
          <div v-if="myApps.length" class="app-grid">
            <AppCard v-for="app in myApps" :key="app.id" :app="app" />
          </div>
          <div v-else class="empty-placeholder">
            <a-empty description="暂无作品，在上方输入灵感开始你的第一个应用" />
          </div>
        </a-spin>
        <a-pagination
          v-if="myTotal > 6"
          v-model:current="mySearch.pageNum"
          v-model:page-size="mySearch.pageSize"
          :total="myTotal"
          :page-size-options="['6', '12', '24']"
          show-size-changer
          class="section-pagination"
          @change="loadMyApps"
          @show-size-change="loadMyApps"
        />
      </section>

      <section class="app-section featured-section">
        <div class="section-header">
          <div>
            <h2>精选案例库</h2>
            <p>探索社区与官方创造的高质量灵感项目</p>
          </div>
          <a-input-search
            v-model:value="featuredSearch.appName"
            placeholder="搜索精选项目..."
            aria-label="搜索精选应用"
            class="section-search"
            allow-clear
            @search="searchFeaturedApps"
          />
        </div>
        <a-spin :spinning="featuredLoading">
          <div v-if="featuredApps.length" class="featured-catalog">
            <!-- 方案 C：首卡 Hero 展台 (第 1 页展示) -->
            <div v-if="featuredSearch.pageNum === 1" class="hero-showcase-container">
              <AppCard :app="featuredApps[0]" featured hero />
            </div>

            <!-- 其余精选项目常规网格 -->
            <div
              v-if="featuredSearch.pageNum !== 1 || featuredApps.length > 1"
              class="app-grid"
            >
              <AppCard
                v-for="app in (featuredSearch.pageNum === 1 ? featuredApps.slice(1) : featuredApps)"
                :key="app.id"
                :app="app"
                featured
              />
            </div>
          </div>
          <div v-else class="empty-placeholder">
            <a-empty description="暂无精选应用" />
          </div>
        </a-spin>
        <a-pagination
          v-if="featuredTotal > 6"
          v-model:current="featuredSearch.pageNum"
          v-model:page-size="featuredSearch.pageSize"
          :total="featuredTotal"
          :page-size-options="['6', '12', '24']"
          show-size-changer
          class="section-pagination"
          @change="loadFeaturedApps"
          @show-size-change="loadFeaturedApps"
        />
      </section>
    </div>
  </main>
</template>

<style scoped>
.home-page {
  background: var(--ui-shell);
}

.hero-section {
  position: relative;
  padding: 64px 24px 56px;
  background:
    radial-gradient(ellipse 60% 50% at 50% -10%, rgba(37, 99, 235, 0.08), transparent 70%),
    linear-gradient(180deg, #ffffff 0%, var(--ui-shell) 100%);
  border-bottom: 1px solid var(--ui-line);
}

.hero-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  max-width: 860px;
  margin: 0 auto;
  text-align: center;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  margin-bottom: 18px;
  font-size: 12px;
  font-weight: 500;
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
  border: 1px solid var(--ui-accent-border);
  border-radius: 9999px;
}

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: var(--ui-accent);
  box-shadow: 0 0 6px rgba(37, 99, 235, 0.6);
}

.hero-title {
  margin: 0 0 12px;
  color: var(--ui-ink);
  font-size: clamp(30px, 3.6vw, 46px);
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.18;
}

.hero-subtitle {
  max-width: 620px;
  margin: 0 auto 32px;
  color: var(--ui-ink-soft);
  font-size: 15px;
  line-height: 1.6;
}

.creation-studio {
  width: 100%;
}

.prompt-box {
  padding: 16px 18px 14px;
  background: var(--ui-surface);
  border: 1px solid var(--ui-line-strong);
  border-radius: var(--ui-radius-xl);
  box-shadow: var(--ui-shadow-lg);
  transition: all 180ms ease;
  text-align: left;
}

.prompt-box:focus-within {
  border-color: var(--ui-accent);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12), var(--ui-shadow-md);
}

.prompt-box :deep(.ant-input) {
  min-height: 108px;
  padding: 0;
  border: 0 !important;
  background: transparent;
  box-shadow: none !important;
  resize: none;
  font-size: 15px;
  line-height: 1.65;
  color: var(--ui-ink);
}

.prompt-box :deep(.ant-input-data-count) {
  color: var(--ui-ink-faint);
  font-family: var(--ui-mono);
  font-size: 11px;
}

.prompt-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--ui-line);
}

.prompt-tips {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ui-ink-faint);
  font-size: 12px;
}

.kbd-hint {
  padding: 2px 6px;
  font-family: var(--ui-mono);
  font-size: 11px;
  color: var(--ui-ink-soft);
  background: var(--ui-surface-muted);
  border: 1px solid var(--ui-line);
  border-radius: 4px;
}

.submit-circle-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.28);
}

.submit-circle-btn:not(:disabled):hover {
  transform: scale(1.05);
}

.sample-list {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 20px;
}

.samples-label {
  color: var(--ui-ink-faint);
  font-size: 12px;
}

.sample-chip {
  padding: 5px 12px;
  font-size: 12px;
  color: var(--ui-ink-soft);
  background: var(--ui-surface);
  border: 1px solid var(--ui-line);
  border-radius: 9999px;
  cursor: pointer;
  box-shadow: var(--ui-shadow-xs);
  transition: all 160ms cubic-bezier(0.16, 1, 0.3, 1);
}

.sample-chip:hover {
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
  border-color: var(--ui-accent-border);
  transform: translateY(-1px);
}

.library {
  max-width: 1320px;
  margin: 0 auto;
  padding: 0 32px 64px;
}

.app-section {
  padding: 40px 0;
}

.featured-section {
  border-top: 1px solid var(--ui-line);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 24px;
  margin-bottom: 24px;
}

.section-header h2 {
  margin: 0 0 4px;
  color: var(--ui-ink);
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.section-header p {
  margin: 0;
  color: var(--ui-ink-soft);
  font-size: 13px;
}

.section-search {
  width: 260px;
}

.featured-catalog {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.hero-showcase-container {
  width: 100%;
}

.app-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 22px;
}

.empty-placeholder {
  padding: 48px 0;
}

.section-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 24px;
}

@media (max-width: 960px) {
  .hero-section {
    padding: 44px 20px 40px;
  }

  .app-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .library {
    padding: 0 20px 48px;
  }
}

@media (max-width: 640px) {
  .hero-title {
    font-size: 28px;
  }

  .section-header {
    flex-direction: column;
    align-items: stretch;
    gap: 16px;
  }

  .section-search {
    width: 100%;
  }

  .app-grid {
    grid-template-columns: 1fr;
  }
}
</style>
