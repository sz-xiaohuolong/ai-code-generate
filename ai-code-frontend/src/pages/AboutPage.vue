<template>
  <main class="about-page">
    <section class="intro-section">
      <div>
        <h1>关于 AI 代码生成平台</h1>
        <p>
          这是一个通过自然语言创建网站应用的平台。用户只需要描述想法，系统会通过 AI
          生成页面代码，并提供在线预览、持续对话优化和一键部署能力。
        </p>
      </div>
    </section>

    <section class="content-section">
      <h2>可以做什么</h2>
      <div class="feature-grid">
        <div class="feature-item">
          <h3>一句话创建应用</h3>
          <p>在首页输入应用需求，平台会创建应用记录并进入 AI 生成对话。</p>
        </div>
        <div class="feature-item">
          <h3>对话式生成网站</h3>
          <p>在应用对话页继续补充需求，AI 会流式返回生成结果并展示网站效果。</p>
        </div>
        <div class="feature-item">
          <h3>查看和部署作品</h3>
          <p>生成完成后可查看预览，部署后可通过独立访问地址打开作品。</p>
        </div>
        <div class="feature-item">
          <h3>管理个人应用</h3>
          <p>用户可以查看自己的应用、修改应用名称、删除不再需要的应用。</p>
        </div>
      </div>
    </section>

    <section class="content-section">
      <h2>使用方法</h2>
      <a-steps direction="vertical" :current="4">
        <a-step title="输入提示词" description="回到首页，描述你想创建的网站或应用。" />
        <a-step title="进入生成对话" description="创建成功后自动进入应用对话页，并发送初始需求。" />
        <a-step title="查看生成效果" description="AI 生成完成后，右侧区域会展示生成的网站预览。" />
        <a-step title="继续优化" description="在输入框继续描述修改意见，让 AI 迭代生成结果。" />
        <a-step title="部署作品" description="确认效果后点击部署，获得可访问的作品地址。" />
      </a-steps>
    </section>

<!--    <section class="content-section">-->
<!--      <h2>角色说明</h2>-->
<!--      <div class="role-list">-->
<!--        <div>-->
<!--          <h3>普通用户</h3>-->
<!--          <p>可以创建应用、生成网站、查看详情、部署作品、编辑和删除自己的应用。</p>-->
<!--        </div>-->
<!--        <div>-->
<!--          <h3>管理员</h3>-->
<!--          <p>可以进入用户管理和应用管理，对全部应用进行查询、编辑、删除和精选设置。</p>-->
<!--        </div>-->
<!--      </div>-->
<!--    </section>-->
  </main>
</template>

<style scoped>
.about-page {
  max-width: 980px;
  margin: 0 auto;
  color: var(--ui-ink);
}

.intro-section {
  padding: 36px 0 42px;
  border-bottom: 1px solid var(--ui-line);
}

.intro-section h1 {
  margin: 0 0 18px;
  font-size: 36px;
  font-weight: 700;
}

.intro-section p {
  max-width: 760px;
  margin: 0;
  color: var(--ui-ink-soft);
  font-size: 16px;
  line-height: 1.85;
}

.content-section {
  padding: 40px 0;
  border-bottom: 1px solid var(--ui-line);
}

.content-section h2 {
  margin: 0 0 26px;
  font-size: 21px;
  font-weight: 700;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 36px;
}

.feature-item,
.role-list > div {
  padding: 18px 0;
  border-top: 1px solid var(--ui-line);
}

.feature-item h3,
.role-list h3 {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 650;
}

.feature-item p,
.role-list p {
  margin: 0;
  color: var(--ui-ink-soft);
  line-height: 1.7;
}

.role-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

@media (max-width: 768px) {
  .intro-section {
    padding: 18px 0 32px;
  }

  .intro-section h1 {
    font-size: 28px;
  }

  .feature-grid,
  .role-list {
    grid-template-columns: 1fr;
  }
}
</style>
