<template>
  <footer class="global-footer">
    <span>AI 代码生成平台</span>
    <a href="https://blog.csdn.net/m0_60496161" target="_blank" rel="noopener noreferrer">
      不会喷火的小火龙版权所有
    </a>
  </footer>
</template>

<style scoped>
.global-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  max-width: 1480px;
  min-height: 46px;
  margin: 0 auto;
  padding: 0 32px;
  color: var(--ui-ink-faint);
  border-top: 1px solid var(--ui-line);
  font-size: 12px;
}

.global-footer a {
  color: var(--ui-ink-soft);
}

@media (max-width: 760px) {
  .global-footer {
    padding: 0 16px;
  }
}
</style>
