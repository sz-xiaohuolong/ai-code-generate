<script setup lang="ts">
import authArtwork from '@/assets/auth-afterglow.webp'

defineProps<{ title: string; description: string }>()
</script>

<!-- 图标来自 Iconify / Lucide，原始许可保存在 public/licenses/lucide.txt。 -->
<template>
  <main class="auth-page">
    <section class="auth-content" aria-labelledby="auth-title">
      <RouterLink class="auth-brand" to="/" aria-label="返回 AI 代码生成平台首页">
        <img src="@/assets/logo.svg" alt="" width="32" height="32" />
        <span>AI 代码生成平台</span>
      </RouterLink>
      <div class="auth-form-wrap">
        <h1 id="auth-title">{{ title }}</h1>
        <p class="auth-description">{{ description }}</p>
        <slot />
      </div>
      <RouterLink class="auth-back" to="/"
        ><svg aria-hidden="true" width="1em" height="1em" viewBox="0 0 24 24">
          <path
            fill="none"
            stroke="currentColor"
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="m12 19l-7-7l7-7m7 7H5"
          />
        </svg>
        返回首页</RouterLink
      >
    </section>
    <aside class="auth-art" aria-label="创作灵感">
      <img
        :src="authArtwork"
        alt="青绿与暖金交织的抽象光影"
        width="1200"
        height="1800"
        fetchpriority="high"
      />
      <div class="art-tiles" aria-hidden="true"><span /><span /><span /></div>
      <div class="art-caption">
        <div class="art-caption-icon" aria-hidden="true">
          <svg aria-hidden="true" width="1em" height="1em" viewBox="0 0 24 24">
            <path
              fill="none"
              stroke="currentColor"
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M7 7h10v10M7 17L17 7"
            />
          </svg>
        </div>
        <h2>从一个想法，<br />到你的下一件作品。</h2>
        <p>写下灵感，交给 AI。<br />边聊边打磨，让创意成为应用。</p>
        <div class="art-footer"><span>描述 · 生成 · 预览 · 发布</span><span>由你定义</span></div>
      </div>
    </aside>
  </main>
</template>

<style scoped>
.auth-page {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: clamp(40px, 7vw, 112px);
  min-height: 100dvh;
  max-width: 1600px;
  margin: auto;
  padding: 32px 40px 32px 64px;
  background: var(--ui-surface);
}
.auth-content {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-width: 0;
  padding: 16px 0;
}
.auth-brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  color: var(--ui-ink);
  font-weight: 650;
  font-size: 15px;
}
.auth-brand img {
  border-radius: 9px;
}
.auth-form-wrap {
  width: 100%;
  max-width: 440px;
  margin: auto;
  padding: 64px 0;
}
.auth-form-wrap h1 {
  margin: 0 0 18px;
  font-size: clamp(32px, 3.3vw, 48px);
  font-weight: 650;
  line-height: 1.25;
  letter-spacing: -0.035em;
  color: var(--ui-ink);
}
.auth-description {
  color: var(--ui-ink-soft);
  font-size: 15px;
  line-height: 1.9;
  margin: 0 0 38px;
  max-width: 32em;
}
.auth-back {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 44px;
  font-size: 13px;
  color: var(--ui-ink-soft);
}
.auth-back:hover {
  color: var(--ui-accent);
}
.auth-form-wrap :deep(.ant-form-item) {
  margin-bottom: 24px;
}
.auth-form-wrap :deep(.ant-form-item-label) {
  padding-bottom: 10px;
}
.auth-form-wrap :deep(.ant-form-item-label > label) {
  font-size: 14px;
  font-weight: 550;
}
.auth-form-wrap :deep(.ant-input-affix-wrapper) {
  min-height: 50px;
  padding: 11px 14px;
  border-radius: 9px;
  background: var(--ui-surface);
}
.auth-form-wrap :deep(input.ant-input) {
  font-size: 15px;
  background: transparent;
}
.auth-form-wrap :deep(.ant-btn-primary) {
  height: 50px;
  margin-top: 8px;
  font-size: 15px;
  font-weight: 550;
  border-radius: 9px;
  background: var(--ui-ink);
  border-color: var(--ui-ink);
  color: var(--ui-surface);
  box-shadow: none;
}
.auth-form-wrap :deep(.ant-btn-primary:hover) {
  background: var(--ui-ink-soft);
  border-color: var(--ui-ink-soft);
}
.auth-form-wrap :deep(.form-extra) {
  margin-top: 30px;
  text-align: center;
  color: var(--ui-ink-soft);
  font-size: 14px;
}
.auth-form-wrap :deep(.form-extra a) {
  margin-left: 6px;
  font-weight: 600;
}
.auth-form-wrap :deep(.auth-error) {
  margin-bottom: 20px;
}
.auth-art {
  position: relative;
  overflow: hidden;
  min-height: 680px;
  border-radius: 24px;
  isolation: isolate;
  background: #172923;
  color: #fff;
}
.auth-art > img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: saturate(0.48) brightness(0.75);
}
.auth-art::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -0;
  background:
    linear-gradient(180deg, #0a0c10 0%, #10171bba 24%, #14282100 57%, #10291ed9 100%),
    radial-gradient(ellipse at 12% 63%, #d0a869aa, transparent 65%);
}
.art-tiles {
  position: absolute;
  inset: -10% -20% 35%;
  z-index: 1;
  transform: rotate(-42deg);
  opacity: 0.6;
}
.art-tiles span {
  position: absolute;
  width: 54%;
  aspect-ratio: 1;
  border: 1px solid #fff2;
  border-radius: 40px;
  background: linear-gradient(135deg, #ffffff08, #ffffff03);
}
.art-tiles span:nth-child(1) {
  left: 5%;
  top: -16%;
}
.art-tiles span:nth-child(2) {
  left: 63%;
  top: -16%;
}
.art-tiles span:nth-child(3) {
  left: 63%;
  top: 56%;
}
.art-caption {
  position: absolute;
  z-index: 2;
  bottom: 0;
  left: 0;
  right: 0;
  padding: clamp(28px, 4vw, 56px);
}
.art-caption-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  margin-bottom: 28px;
  background: #0f20194d;
  border: 1px solid #ffffff50;
  border-radius: 50%;
  font-size: 22px;
}
.art-caption h2 {
  font-size: clamp(28px, 3vw, 42px);
  font-weight: 500;
  letter-spacing: -0.025em;
  line-height: 1.5;
  color: #fff;
  margin: 0 0 20px;
}
.art-caption p {
  font-size: 14px;
  line-height: 1.9;
  color: #f0f5f1;
  margin: 0 0 48px;
}
.art-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding-top: 24px;
  border-top: 1px solid #ffffff38;
  color: #edf5ed;
  font-size: 11px;
  letter-spacing: 0.06em;
}
@media (max-width: 1000px) {
  .auth-page {
    gap: 40px;
    padding: 24px 24px 24px 36px;
  }
  .auth-art {
    min-height: 640px;
  }
  .art-caption {
    padding: 32px;
  }
  .art-footer {
    flex-direction: column;
  }
}
@media (max-width: 760px) {
  .auth-page {
    grid-template-columns: 1fr;
    min-height: 100dvh;
    padding: 24px;
    gap: 0;
  }
  .auth-content {
    min-height: calc(100dvh - 48px);
    padding: 0;
  }
  .auth-form-wrap {
    padding: 48px 0;
    max-width: 440px;
  }
  .auth-form-wrap h1 {
    font-size: 36px;
  }
  .auth-art {
    display: none;
  }
  .auth-description {
    margin-bottom: 32px;
  }
}
</style>
