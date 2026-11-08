<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLoginUserStore } from '@/stores/loginUser'
import { ArrowUpOutlined } from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import AppCard from '@/components/AppCard.vue'
import HomeAtmosphere from '@/components/HomeAtmosphere.vue'
import CreationPromptInput from '@/components/CreationPromptInput.vue'
import KnowledgeToggle from '@/components/KnowledgeToggle.vue'
import { rememberFirstKnowledgeRequest } from '@/utils/knowledgeRequest'
import GenerationTracePanel from '@/components/GenerationTracePanel.vue'
import { isAxiosError } from 'axios'
import { addApp, listFeaturedAppVoByPage, listMyAppVoByPage } from '@/api/appController'
import { useGenerationModeStore } from '@/stores/generationMode'

const generationModeStore = useGenerationModeStore()
const { isLogin, loginUser } = storeToRefs(useLoginUserStore())
const generationModeOptions = [
  { label: '标准模式', value: 'standard' },
  { label: '工作流模式', value: 'workflow' },
]
const myError = ref('')
const featuredError = ref('')
const promptInput = ref<InstanceType<typeof CreationPromptInput> | null>(null)
let myRequest = 0
let featuredRequest = 0

const router = useRouter()
const prompt = ref('')
const creating = ref(false)
const useKnowledge = ref(false)
const creationError = ref('')
const creationRequestId = ref('')
const creationTraceOpen = ref(false)
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
  if (!isLogin.value) return
  const request = ++myRequest
  myLoading.value = true
  myError.value = ''
  try {
    const res = await listMyAppVoByPage({ ...mySearch })
    if (request !== myRequest) return
    if (res.data.code !== 0 || !res.data.data) throw new Error('列表不可用')
    myApps.value = res.data.data.records || []
    myTotal.value = res.data.data.totalRow || 0
  } catch {
    if (request === myRequest) myError.value = '作品暂时无法加载，请重试。'
  } finally {
    if (request === myRequest) myLoading.value = false
  }
}

const loadFeaturedApps = async () => {
  const request = ++featuredRequest
  featuredLoading.value = true
  featuredError.value = ''
  try {
    const res = await listFeaturedAppVoByPage({ ...featuredSearch })
    if (request !== featuredRequest) return
    if (res.data.code !== 0 || !res.data.data) throw new Error('列表不可用')
    featuredApps.value = res.data.data.records || []
    featuredTotal.value = res.data.data.totalRow || 0
  } catch {
    if (request === featuredRequest) featuredError.value = '精选案例暂时无法加载，请重试。'
  } finally {
    if (request === featuredRequest) featuredLoading.value = false
  }
}

const chooseSample = (value: string) => {
  if (creating.value) return
  prompt.value = value
  promptInput.value?.focus()
}
const clearMySearch = () => {
  mySearch.appName = ''
  searchMyApps()
}
const clearFeaturedSearch = () => {
  featuredSearch.appName = ''
  searchFeaturedApps()
}

const createApp = async () => {
  if (creating.value) return
  const initPrompt = prompt.value.trim()
  if (!initPrompt) {
    message.warning('请输入你想创建的应用')
    return
  }
  creating.value = true
  const selectedKnowledge = useKnowledge.value
  const generationMode = generationModeStore.defaultMode
  const animation = promptInput.value?.vanish() ?? Promise.resolve()
  creationError.value = ''
  creationRequestId.value = ''
  creationTraceOpen.value = false
  try {
    const res = await addApp({ initPrompt, generationMode })
    if (res.data.code === 0 && res.data.data) {
      await animation
      rememberFirstKnowledgeRequest(String(res.data.data), selectedKnowledge)
      await router.push(`/app/chat/${res.data.data}`)
    } else {
      creationError.value = res.data.message || '创建应用失败'
      creationRequestId.value = String(res.headers['x-request-id'] || '')
      message.error(creationError.value)
    }
  } catch (error) {
    creationError.value = '创建请求未完成，请稍后重试。'
    if (isAxiosError(error))
      creationRequestId.value = String(error.response?.headers['x-request-id'] || '')
    message.error(creationError.value)
  } finally {
    promptInput.value?.cancel()
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

watch(
  () => loginUser.value.id,
  () => {
    myRequest++
    myApps.value = []
    myTotal.value = 0
    myError.value = ''
    myLoading.value = false
    if (isLogin.value) void loadMyApps()
  },
  { immediate: true },
)

onMounted(loadFeaturedApps)
</script>

<template>
  <main class="home-page">
    <section class="hero-section" aria-labelledby="home-title">
      <HomeAtmosphere />
      <div class="hero-inner">
        <h1 id="home-title" class="hero-title">把想法，变成应用</h1>
        <p class="hero-subtitle">描述你想做什么，剩下的交给 AI。预览、修改，一步步完成你的作品。</p>
        <div class="creation-studio">
          <div class="prompt-box">
            <CreationPromptInput
              ref="promptInput"
              v-model="prompt"
              :examples="[
                '为我创建一个摄影作品集，让每一张照片成为主角…',
                '做一个清爽的个人博客，记录技术与生活…',
                '为我的品牌设计官网，展示产品和服务…',
                '我想要一个设计师作品集，分享最近的创作…',
              ]"
              :disabled="creating"
              @submit="createApp"
            />
            <div class="prompt-options">
              <a-segmented
                :value="generationModeStore.defaultMode"
                :options="generationModeOptions"
                :disabled="creating"
                aria-label="新建应用生成模式"
                @change="generationModeStore.setDefaultMode"
              />
              <span class="mode-description">{{
                generationModeStore.defaultMode === 'workflow'
                  ? '分阶段规划、生成与检查'
                  : '直接生成，持续对话修改'
              }}</span>
            </div>
            <div class="prompt-actions">
              <KnowledgeToggle v-model="useKnowledge" :disabled="creating" />
              <div class="submit-actions">
                <span class="kbd-hint">⌘ / Ctrl ↵</span>
                <a-button
                  type="primary"
                  :loading="creating"
                  :aria-label="creating ? '正在创建' : '开始生成'"
                  class="create-button"
                  @click="createApp"
                >
                  {{ creating ? '正在创建' : '开始生成' }} <ArrowUpOutlined v-if="!creating" />
                </a-button>
              </div>
            </div>
          </div>
          <div v-if="creationError" class="creation-diagnostic">
            <p role="alert">{{ creationError }}</p>
            <p v-if="creationRequestId">请求编号：{{ creationRequestId }}</p>
            <p v-else>未取得请求编号，请保留当前需求后重试。</p>
            <a-button
              v-if="creationRequestId"
              :aria-expanded="creationTraceOpen"
              @click="creationTraceOpen = !creationTraceOpen"
              >查看创建诊断</a-button
            >
            <GenerationTracePanel
              v-if="creationTraceOpen && creationRequestId"
              :request-id="creationRequestId"
              @close="creationTraceOpen = false"
            />
          </div>
          <div class="sample-list" aria-label="灵感示例">
            <button
              v-for="sample in promptSamples"
              :key="sample.label"
              type="button"
              class="sample-chip"
              :disabled="creating"
              :title="sample.prompt"
              @click="chooseSample(sample.prompt)"
            >
              {{ sample.label }}
              <span aria-hidden="true">
                <svg width="12" height="12" viewBox="0 0 24 24">
                  <path
                    fill="none"
                    stroke="currentColor"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M7 7h10v10M7 17L17 7"
                  />
                </svg>
              </span>
            </button>
          </div>
        </div>
      </div>
    </section>

    <div class="library">
      <section class="app-section" aria-labelledby="my-apps-title">
        <div class="section-header">
          <div>
            <h2 id="my-apps-title">我的作品</h2>
            <p>从上次停下的地方，继续创作。</p>
          </div>
          <a-input-search
            v-if="isLogin"
            v-model:value="mySearch.appName"
            placeholder="搜索作品"
            aria-label="搜索我的应用"
            class="section-search"
            allow-clear
            @search="searchMyApps"
          />
        </div>
        <div v-if="!isLogin" class="empty-placeholder">
          <h3>你的下一个作品，从这里开始</h3>
          <p>登录后，查看并继续编辑你创建的应用。</p>
          <a-button @click="router.push('/user/login')">登录查看作品</a-button>
        </div>
        <template v-else>
          <div v-if="myLoading" class="app-grid" aria-label="正在加载我的作品" role="status">
            <div v-for="n in 3" :key="n" class="app-skeleton">
              <a-skeleton active :paragraph="{ rows: 3 }" />
            </div>
          </div>
          <div v-else-if="myError" class="list-notice" role="alert">
            <p>{{ myError }}</p>
            <a-button @click="loadMyApps">重新加载作品</a-button>
          </div>
          <div v-else-if="myApps.length" class="app-grid">
            <AppCard
              v-for="(app, index) in myApps"
              :key="app.id"
              :app="app"
              :hero="[0, 3, 4].includes(index % 6)"
            />
          </div>
          <div v-else class="empty-placeholder">
            <h3>{{ mySearch.appName ? '没有找到匹配的作品' : '还没有作品' }}</h3>
            <p>
              {{
                mySearch.appName
                  ? '试试其他名称，或清空搜索条件。'
                  : '在上方写下想法，创建你的第一个应用。'
              }}
            </p>
            <a-button v-if="mySearch.appName" @click="clearMySearch">清空搜索</a-button>
            <a-button v-else @click="promptInput?.focus()">开始创作</a-button>
          </div>
          <a-pagination
            v-if="myTotal > 6 && !myError"
            v-model:current="mySearch.pageNum"
            v-model:page-size="mySearch.pageSize"
            :total="myTotal"
            :page-size-options="['6', '12', '24']"
            show-size-changer
            class="section-pagination"
            @change="loadMyApps"
          />
        </template>
      </section>

      <section class="app-section featured-section" aria-labelledby="featured-title">
        <div class="section-header">
          <div>
            <h2 id="featured-title">精选案例</h2>
            <p>看看其他想法，是怎样成为作品的。</p>
          </div>
          <a-input-search
            v-model:value="featuredSearch.appName"
            placeholder="搜索精选案例"
            aria-label="搜索精选应用"
            class="section-search"
            allow-clear
            @search="searchFeaturedApps"
          />
        </div>
        <div v-if="featuredLoading" class="app-grid" aria-label="正在加载精选案例" role="status">
          <div v-for="n in 3" :key="n" class="app-skeleton">
            <a-skeleton active :paragraph="{ rows: 3 }" />
          </div>
        </div>
        <div v-else-if="featuredError" class="list-notice" role="alert">
          <p>{{ featuredError }}</p>
          <a-button @click="loadFeaturedApps">重新加载案例</a-button>
        </div>
        <div v-else-if="featuredApps.length" class="app-grid">
          <AppCard
            v-for="(app, index) in featuredApps"
            :key="app.id"
            :app="app"
            :hero="[0, 3, 4].includes(index % 6)"
            featured
          />
        </div>
        <div v-else class="empty-placeholder">
          <h3>{{ featuredSearch.appName ? '没有找到匹配的案例' : '精选案例即将呈现' }}</h3>
          <p>
            {{ featuredSearch.appName ? '试试其他关键词。' : '你可以先从上方的灵感示例开始。' }}
          </p>
          <a-button v-if="featuredSearch.appName" @click="clearFeaturedSearch">清空搜索</a-button>
        </div>
        <a-pagination
          v-if="featuredTotal > 6 && !featuredError"
          v-model:current="featuredSearch.pageNum"
          v-model:page-size="featuredSearch.pageSize"
          :total="featuredTotal"
          :page-size-options="['6', '12', '24']"
          show-size-changer
          class="section-pagination"
          @change="loadFeaturedApps"
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
  --ui-ink: #f0f5ff;
  --ui-ink-soft: #bfcbdd;
  --ui-ink-faint: #a3b2c9;
  --ui-accent: #b2cfff;
  --ui-accent-hover: #d5e5ff;
  --ui-accent-soft: #203553;
  --ui-accent-border: #7193c3;
  --ui-surface: #131d2d;
  --ui-surface-muted: #1c293e;
  --ui-line: #344359;
  --ui-line-subtle: #2a374c;
  --ui-danger: #ffc2c9;
  --ui-danger-soft: #37222e;
  position: relative;
  isolation: isolate;
  overflow: hidden;
  padding: 90px 24px 64px;
  background: #080d18;
  color: var(--ui-ink);
  color-scheme: dark;
}
.hero-inner {
  position: relative;
  z-index: 1;
  max-width: 800px;
  margin: 0 auto;
  text-align: center;
}
.hero-title {
  margin: 0 0 20px;
  font-size: clamp(36px, 4.2vw, 60px);
  font-weight: 650;
  line-height: 1.3;
  letter-spacing: -0.035em;
  color: #f3f6fc;
  text-wrap: balance;
}
.hero-subtitle {
  margin: 0 0 36px;
  font-size: 15px;
  line-height: 1.8;
  color: #bfcbdd;
}
.creation-studio {
  max-width: 740px;
  margin: auto;
}
.prompt-box {
  position: relative;
  padding: 0 0 18px;
  background: #121c2be8;
  border: 1px solid #ffffff29;
  border-radius: 18px;
  text-align: left;
  box-shadow: 0 24px 70px #0005;
}
.prompt-box:focus-within {
  outline: 2px solid #8fb8f1;
  outline-offset: 3px;
}
.prompt-options {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 22px;
}
.prompt-options :deep(.ant-segmented) {
  background: #080f1c;
  padding: 4px;
  color: #b5c3d8;
  border-radius: 8px;
}
.prompt-options :deep(.ant-segmented-item-selected),
.prompt-options :deep(.ant-segmented-thumb) {
  color: #edf4ff;
  background: #2c3c53;
  box-shadow: none;
  border-radius: 5px;
}
.prompt-options :deep(.ant-segmented-item:hover:not(.ant-segmented-item-disabled)) {
  color: #fff;
}
.prompt-options :deep(.ant-segmented-item-disabled) {
  color: #a3b2c9;
}
.mode-description {
  color: #a3b2c9;
  font-size: 12px;
}
.prompt-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 16px 22px 0;
  padding-top: 16px;
  border-top: 1px solid #ffffff14;
}
.prompt-actions :deep(.knowledge-choice) {
  flex: 1;
  min-width: 0;
  font-size: 12px;
}
.prompt-actions :deep(.knowledge-choice a) {
  color: #b2cfff;
}
.submit-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 16px;
}
.prompt-actions .create-button.ant-btn-primary {
  height: 42px;
  padding: 0 18px;
  border-radius: 9px;
  background: #edf4ff;
  border-color: #edf4ff;
  color: #142138;
  font-weight: 600;
}
.prompt-actions .create-button.ant-btn-primary:not(:disabled):hover {
  color: #142138;
  background: #cadfff;
  border-color: #cadfff;
}
.kbd-hint {
  color: #a3b2c9;
  font-size: 11px;
}
.sample-list {
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 24px;
}
.sample-chip {
  display: inline-flex;
  align-items: center;
  gap: 16px;
  min-height: 38px;
  padding: 8px 14px;
  background: #111b2980;
  border: 1px solid #ffffff1f;
  border-radius: 8px;
  color: #bdcce0;
  font-size: 12px;
  cursor: pointer;
  transition:
    background 0.18s,
    border-color 0.18s;
}
.sample-chip span {
  color: #a3b2c9;
}
.sample-chip:hover:not(:disabled) {
  color: #fff;
  background: #22334b;
  border-color: #7896bb;
}
.sample-chip:disabled {
  cursor: default;
  opacity: 0.55;
}
.library {
  max-width: 1280px;
  padding: 20px 40px 64px;
  margin: 0 auto;
}
.app-section {
  padding: 40px 0;
}
.featured-section {
  border-top: 1px solid var(--ui-line);
}
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 26px;
}
.section-header h2 {
  text-wrap: balance;
  margin: 0 0 8px;
  font-size: 25px;
  font-weight: 650;
  letter-spacing: -0.025em;
}
.section-header p {
  margin: 0;
  color: var(--ui-ink-faint);
  font-size: 13px;
}
.section-search {
  width: 240px;
}
.app-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 20px;
}
.app-grid > * {
  grid-column: span 2;
}
.app-grid > .app-card-hero,
.app-grid > .app-skeleton:first-child {
  grid-column: span 3;
}
.empty-placeholder,
.list-notice {
  text-align: center;
  padding: 44px 20px;
  border: 1px dashed var(--ui-line-strong);
  border-radius: 14px;
  background: var(--ui-surface);
}
.empty-placeholder h3 {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 8px;
}
.empty-placeholder p,
.list-notice p {
  color: var(--ui-ink-soft);
  margin-bottom: 16px;
}
.list-notice {
  color: var(--ui-danger);
  background: var(--ui-danger-soft);
  border: 0;
}
.app-skeleton {
  min-height: 300px;
  padding: 32px 24px;
  border-radius: 14px;
  background: var(--ui-surface);
}
.section-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 24px;
}
.creation-diagnostic {
  margin-top: 16px;
  padding: 16px;
  text-align: left;
  color: var(--ui-danger);
  background: var(--ui-danger-soft);
  border-radius: 8px;
  overflow-wrap: anywhere;
}
.creation-diagnostic p {
  margin: 0 0 8px;
}
@media (max-width: 960px) {
  .app-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .app-grid > *,
  .app-grid > .app-card-hero,
  .app-grid > .app-skeleton:first-child {
    grid-column: span 1;
  }
  .hero-section {
    padding-top: 64px;
  }
}
@media (max-width: 640px) {
  .hero-section {
    padding: 52px 16px 36px;
  }
  .hero-title {
    font-size: 34px;
    letter-spacing: -0.035em;
  }
  .hero-subtitle {
    font-size: 14px;
    margin: 0 auto 28px;
    max-width: 25em;
  }
  .prompt-options {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
    padding: 0 18px;
  }
  .prompt-options :deep(.ant-segmented-item-label) {
    min-height: 36px;
    line-height: 36px;
  }
  .prompt-actions {
    flex-wrap: wrap;
    margin: 14px 18px 0;
    padding-top: 12px;
  }
  .prompt-actions :deep(.knowledge-choice) {
    flex-basis: 100%;
    padding: 0;
    gap: 8px 12px;
  }
  .prompt-actions :deep(.knowledge-switch),
  .prompt-actions :deep(.knowledge-choice > a) {
    min-height: 36px;
    display: inline-flex;
    align-items: center;
  }
  .submit-actions {
    margin-left: auto;
  }
  .prompt-actions .create-button.ant-btn-primary {
    height: 44px;
  }
  .kbd-hint {
    display: none;
  }
  .sample-list {
    gap: 8px;
    margin-top: 20px;
  }
  .sample-chip {
    min-height: 44px;
    padding: 8px 12px;
  }
  .library {
    padding: 0 16px 32px;
  }
  .app-section {
    padding: 32px 0;
  }
  .section-header {
    align-items: stretch;
    flex-direction: column;
    gap: 18px;
  }
  .section-search {
    width: 100%;
  }
  .app-grid {
    grid-template-columns: 1fr;
    gap: 18px;
  }
}
@media (hover: none) {
  .kbd-hint {
    display: none;
  }
}
</style>
