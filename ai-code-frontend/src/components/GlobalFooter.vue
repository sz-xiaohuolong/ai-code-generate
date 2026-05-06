<script setup lang="ts">
// 版权信息组件
</script>

<template>
  <div class="global-footer">
    <a
      href="https://blog.csdn.net/m0_60496161"
      target="_blank"
      rel="noopener noreferrer"
      class="copyright-link"
    >
      不会喷火的小火龙版权所有
    </a>
  </div>
</template>

<style scoped>
.global-footer {
  padding: 24px 50px;
  text-align: center;
  background: #f0f2f5;
}

.copyright-link {
  color: #666;
  text-decoration: none;
  transition: color 0.3s;
}

.copyright-link:hover {
  color: #1890ff;
}

/* 响应式适配 */
@media (max-width: 768px) {
  .global-footer {
    padding: 16px 24px;
  }
}
</style>