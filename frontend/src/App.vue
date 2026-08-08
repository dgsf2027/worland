<script setup lang="ts">
import { useRoute } from 'vue-router'
const route = useRoute()
</script>

<template>
  <el-container class="app-root">
    <el-aside width="200px" class="app-aside">
      <div class="brand">沃朗租赁</div>
      <el-menu :default-active="route.path" router>
        <el-menu-item index="/quote">报价测算器</el-menu-item>
        <el-menu-item index="/supplier">供应商 · 上游</el-menu-item>
        <el-menu-item index="/customer">客户 · CRM</el-menu-item>
        <el-menu-item index="/asset">设备 · 逐件台账</el-menu-item>
        <el-menu-item index="/contract">合同 · 租金计划</el-menu-item>
        <el-menu-item index="/dashboard">工作台</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="app-header">
        <span class="title">{{ (route.meta.title as string) || '沃朗科技租赁板块' }}</span>
        <span class="env">智慧园区平台 · 租赁板块 · 冲刺0</span>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style>
html, body, #app { height: 100%; margin: 0; }
.app-root { height: 100vh; }
.app-aside { background: #1f2d3d; color: #fff; }
.brand { font-size: 18px; font-weight: 700; padding: 18px 20px; color: #fff; letter-spacing: 2px; }
.app-aside .el-menu { background: transparent; border: none; }
.app-header { display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid #eee; }
.app-header .title { font-size: 16px; font-weight: 600; }
.app-header .env { font-size: 12px; color: #999; }
</style>
