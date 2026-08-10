<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
const route = useRoute()

// 占位期"登录身份"切换(生产由可信网关注入 X-User-*·ADR-001)
const roles = [
  { name: '小洪', role: '老板' },
  { name: '财务', role: '财务' },
  { name: '李工', role: '供应链' },
  { name: '王业务', role: '业务' },
  { name: '刘总', role: 'LP' },
]
const current = ref(localStorage.getItem('rent_user_role') || '老板')
function switchRole(r: { name: string; role: string }) {
  localStorage.setItem('rent_user_name', r.name)
  localStorage.setItem('rent_user_role', r.role)
  current.value = r.role
  window.location.reload()
}
</script>

<template>
  <el-container class="app-root">
    <el-aside width="210px" class="app-aside">
      <div class="brand">沃朗租赁</div>
      <el-menu :default-active="route.path" router>
        <el-menu-item index="/workbench">🏠 工作台（首页）</el-menu-item>
        <el-menu-item index="/quote">报价测算器</el-menu-item>
        <el-menu-item index="/supplier">供应商 · 上游</el-menu-item>
        <el-menu-item index="/customer">客户 · CRM</el-menu-item>
        <el-menu-item index="/asset">设备 · 逐件台账</el-menu-item>
        <el-menu-item index="/contract">合同 · 租金计划</el-menu-item>
        <el-menu-item index="/purchase">采购入库 · 应付</el-menu-item>
        <el-menu-item index="/rent">收租 · 收租单/逾期</el-menu-item>
        <el-menu-item index="/transfer">转让·处置 · 到期/复投</el-menu-item>
        <el-menu-item index="/maintenance">维保工单 · 报修/回写</el-menu-item>
        <el-menu-item index="/stocktake">盘点 · 差异/盘盈亏</el-menu-item>
        <el-menu-item index="/voucher">凭证 · 双账/折旧/红线</el-menu-item>
        <el-menu-item index="/cashflow">现金流/分配驾驶舱</el-menu-item>
        <el-menu-item index="/monthly">月度报表 · 六件套/报告</el-menu-item>
        <el-menu-item index="/bi-pdca">📊 BI 矩阵 · PDCA 改进</el-menu-item>
        <el-menu-item index="/import">📥 导入中心</el-menu-item>
        <el-menu-item index="/task">✅ 任务 · 审批</el-menu-item>
        <el-menu-item index="/roster">🧑‍💼 花名册 · 提成</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="app-header">
        <span class="title">{{ (route.meta.title as string) || '沃朗科技租赁板块' }}</span>
        <div class="right">
          <span class="env">占位登录（网关注入 X-User-*）：</span>
          <el-dropdown @command="switchRole">
            <el-button size="small" type="primary" plain>当前：{{ current }} <el-icon class="el-icon--right">▾</el-icon></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="r in roles" :key="r.role" :command="r">{{ r.name }}（{{ r.role }}）</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
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
.app-aside { background: #1f2d3d; color: #fff; overflow-y: auto; }
.brand { font-size: 18px; font-weight: 700; padding: 18px 20px; color: #fff; letter-spacing: 2px; }
.app-aside .el-menu { background: transparent; border: none; }
.app-header { display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid #eee; }
.app-header .title { font-size: 16px; font-weight: 600; }
.app-header .right { display: flex; align-items: center; gap: 8px; }
.app-header .env { font-size: 12px; color: #999; }
</style>
