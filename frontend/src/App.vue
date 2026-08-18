<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
const route = useRoute()

// 2026-08-19 邀请码注册上线:身份来自登录会话(token),不再前端切角色
import { clearSession } from '@/utils/session'
const displayName = ref(localStorage.getItem('rent_user_name') || '')
const current = ref(localStorage.getItem('rent_user_role') || '')
function logout() {
  clearSession()
  window.location.href = '/login'
}
</script>

<template>
  <router-view v-if="route.meta.public" />
  <el-container v-else class="app-root">
    <el-aside width="210px" class="app-aside">
      <div class="brand">曜石科技</div>
      <el-menu :default-active="route.path" router>
        <el-menu-item index="/workbench">🏠 工作台（首页）</el-menu-item>
        <el-menu-item-group title="业务主线">
          <el-menu-item index="/quote">🧮 报价测算器</el-menu-item>
          <el-menu-item index="/supplier">🏭 供应商 · 上游</el-menu-item>
          <el-menu-item index="/customer">🤝 客户 · CRM</el-menu-item>
          <el-menu-item index="/asset">📦 设备 · 逐件台账</el-menu-item>
          <el-menu-item index="/contract">📄 合同 · 租金计划</el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="收支单据">
          <el-menu-item index="/purchase">🛒 采购入库 · 应付</el-menu-item>
          <el-menu-item index="/rent">🧾 收租 · 收租单/逾期</el-menu-item>
          <el-menu-item index="/transfer">🔁 转让·处置 · 到期/复投</el-menu-item>
          <el-menu-item index="/maintenance">🔧 维保工单 · 报修/回写</el-menu-item>
          <el-menu-item index="/stocktake">📋 盘点 · 差异/盘盈亏</el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="财务 · 报表">
          <el-menu-item index="/voucher">📚 凭证 · 双账/折旧</el-menu-item>
          <el-menu-item index="/cashflow">💰 现金流/分配驾驶舱</el-menu-item>
          <el-menu-item index="/monthly">📑 月度报表 · 六件套</el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="管理">
          <el-menu-item index="/task">✅ 任务 · 审批</el-menu-item>
          <el-menu-item index="/roster">🧑‍💼 花名册 · 提成</el-menu-item>
          <el-menu-item index="/bi-pdca">📊 BI 矩阵 · PDCA</el-menu-item>
          <el-menu-item index="/import">📥 导入中心</el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="app-header">
        <span class="title">{{ (route.meta.title as string) || '曜石科技 · 租赁板块' }}</span>
        <div class="right">
          <span class="env">{{ displayName }}（{{ current }}）</span>
          <el-button size="small" plain @click="logout">退出登录</el-button>
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
/* 暗色侧栏:el-menu 默认文字色 #303133 压深底看不见,显式设亮色主题变量 */
.app-aside .el-menu {
  background: transparent;
  border: none;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #c7ccd4;
  --el-menu-hover-text-color: #ffffff;
  --el-menu-hover-bg-color: #2a3a4d;
  --el-menu-active-color: #66b1ff;
}
.app-aside .el-menu-item { color: #c7ccd4 !important; }
.app-aside .el-menu-item:hover { background-color: #2a3a4d !important; color: #fff !important; }
.app-aside .el-menu-item.is-active { color: #66b1ff !important; background-color: #2a3a4d !important; }
/* 分组小标题(照参考:小字·灰·字距) */
.app-aside .el-menu-item-group__title {
  color: #6b7a8d !important;
  font-size: 11px;
  letter-spacing: 2px;
  padding: 14px 20px 4px !important;
}
.app-aside .el-menu-item-group .el-menu-item { padding-left: 20px !important; }
.app-header { display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid #eee; }
.app-header .title { font-size: 16px; font-weight: 600; }
.app-header .right { display: flex; align-items: center; gap: 8px; }
.app-header .env { font-size: 12px; color: #999; }
</style>
