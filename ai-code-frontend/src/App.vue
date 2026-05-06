<script setup lang="ts">
import { onMounted } from 'vue'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { useLoginUserStore } from '@/stores/loginUser'

const loginUserStore = useLoginUserStore()

onMounted(() => {
  loginUserStore.fetchLoginUser()
})
</script>

<template>
  <BasicLayout />
</template>

<style scoped>
</style>
