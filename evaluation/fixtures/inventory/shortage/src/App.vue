<script setup>
import {ref,computed} from "vue";const keyword=ref("");const shortage=ref(false);const items=[{name:"铅笔",stock:8},{name:"笔记本",stock:0},{name:"文件夹",stock:3}];const visible=computed(()=>items.filter(i=>i.name.includes(keyword.value)&&(!shortage.value||i.stock===0)));
</script>
<template><h1>库存列表</h1><label>关键词<input data-testid="inventory-search" v-model="keyword"></label><label><input type="checkbox" data-testid="out-of-stock" v-model="shortage">只看缺货</label><ul><li v-for="i in visible" :key="i.name" data-testid="inventory-row" :data-stock="i.stock"><span data-testid="inventory-name">{{i.name}}</span> · 库存 <span data-testid="inventory-stock">{{i.stock}}</span></li></ul></template>
