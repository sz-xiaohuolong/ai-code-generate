<script setup lang="ts">
defineProps<{ title: string; description: string; countLabel?: string }>()
const sections = [
  { path: '/admin/userManage', label: '用户管理' },
  { path: '/admin/appManage', label: '应用管理' },
  { path: '/admin/chatHistoryManage', label: '对话管理' },
]
</script>

<template>
  <main class="admin-page">
    <nav class="admin-nav" aria-label="平台管理导航">
      <RouterLink
        v-for="section in sections"
        :key="section.path"
        :to="section.path"
        :aria-current="$route.path === section.path ? 'page' : undefined"
        >{{ section.label }}</RouterLink
      >
    </nav>
    <header class="admin-heading">
      <div>
        <h1>{{ title }}</h1>
        <p>{{ description }}</p>
      </div>
      <span v-if="countLabel" class="admin-count">{{ countLabel }}</span>
    </header>
    <slot />
  </main>
</template>

<style scoped>
.admin-nav {
  display: flex;
  align-items: center;
  gap: 32px;
  min-height: 60px;
  border-bottom: 1px solid var(--ui-line);
  overflow-x: auto;
}
.admin-nav a {
  display: inline-flex;
  align-items: center;
  flex: none;
  height: 60px;
  color: var(--ui-ink-soft);
  font-size: 14px;
  border-bottom: 2px solid transparent;
}
.admin-nav a[aria-current='page'] {
  color: var(--ui-accent);
  border-color: var(--ui-accent);
  font-weight: 600;
}
.admin-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 20px;
  padding: 36px 0 32px;
}
.admin-heading h1 {
  text-wrap: balance;
  margin: 0 0 8px;
  font-size: 28px;
  line-height: 1.25;
  font-weight: 650;
  letter-spacing: -0.03em;
}
.admin-heading p {
  margin: 0;
  color: var(--ui-ink-soft);
  font-size: 14px;
}
.admin-count {
  flex: none;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--ui-ink-faint);
  padding-bottom: 4px;
}
@media (max-width: 640px) {
  .admin-nav {
    gap: 28px;
    min-height: 54px;
  }
  .admin-nav a {
    height: 54px;
  }
  .admin-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
    padding: 28px 0 24px;
  }
  .admin-heading h1 {
  text-wrap: balance;
    font-size: 25px;
  }
}
</style>
