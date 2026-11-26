<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
const route = useRoute()
const mobileMenuOpen = ref(false)
watch(() => route.fullPath, () => { mobileMenuOpen.value = false })

// 2026-08-19 邀请码注册上线:身份来自登录会话(token),不再前端切角色
import { refreshSession } from '@/api/auth'
import { clearSession, sessionDisplayName, sessionRole } from '@/utils/session'
import BrandLockup from '@/components/BrandLockup.vue'
import { startHeartbeat } from '@/utils/heartbeat'
const displayName = sessionDisplayName
const current = sessionRole
const isLp = computed(() => (current.value || '').toUpperCase() === 'LP')
const isFinance = computed(() => (current.value || '') === '财务')

const defaultOpenedMenus = computed(() => {
  const open = new Set<string>(['deal'])
  if (isFinance.value) open.add('finance')
  const p = route.path
  if (['/asset', '/supplier', '/supplier-inspection', '/inventory', '/service'].includes(p)) open.add('supply')
  if (['/cashflow', '/voucher', '/monthly'].includes(p)) open.add('finance')
  if (['/task', '/roster', '/bi-pdca', '/import'].includes(p)) open.add('admin')
  return Array.from(open)
})

async function syncProfile() {
  if (route.meta.public) return
  try {
    const profile = await refreshSession()
    // 平台应用日报的「使用时长」：报本系统账号 id（AI 中台经 yc_rent_auth_user.portal_uid 归人）
    if (profile) startHeartbeat({ id: profile.accountId, name: profile.displayName })
  } catch { /* 登录失效由请求拦截器处理 */ }
}
watch(() => route.fullPath, syncProfile, { immediate: true })
onMounted(() => window.addEventListener('focus', syncProfile))
onUnmounted(() => window.removeEventListener('focus', syncProfile))
function logout() {
  startHeartbeat(null)
  clearSession()
  window.location.href = '/login'
}

// 侧栏底部「版本 提交号 · 提交时间」:值在构建时注入(见 vite.config.ts),点击新开 GitHub 对应提交
const build = __BUILD_INFO__
const hasCommit = /^[0-9a-f]{7,40}$/i.test(build.commit)
const shortCommit = hasCommit ? build.commit.slice(0, 7) : 'unknown'
const commitUrl = hasCommit ? `${build.repoUrl}/commit/${build.commit}` : build.repoUrl
function fmtTime(iso: string): string {
  const d = new Date(iso)
  if (!iso || Number.isNaN(d.getTime())) return 'unknown'
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
const commitTimeText = fmtTime(build.commitTime)
const buildTip = `分支 ${build.branch} · 构建于 ${fmtTime(build.buildTime)}`
</script>

<template>
  <router-view v-if="route.meta.public" />
  <el-container v-else class="app-root" :class="{ 'workbench-layout': route.name === 'workbench', 'workbench-menu-open': mobileMenuOpen }">
    <el-aside width="220px" class="app-aside">
      <div class="brand">
        <BrandLockup variant="stack" :size="20" en-tracking="tight" />
      </div>
      <el-menu :default-active="route.path" :default-openeds="defaultOpenedMenus" router>
        <el-menu-item index="/workbench">🏠 经营驾驶舱</el-menu-item>

        <!-- LP 投资人极简菜单 -->
        <template v-if="isLp">
          <el-menu-item index="/cashflow">💰 资金计划与分红</el-menu-item>
          <el-menu-item index="/monthly">📑 月度经营报表</el-menu-item>
        </template>

        <!-- 经营角色 4 大清晰业务分组（按真实签单→采购→收租→核算动线排列） -->
        <template v-else>
          <el-sub-menu index="deal">
            <template #title><span>📌 租赁业务主线</span></template>
            <el-menu-item index="/project">🎯 项目中心（一单到底）</el-menu-item>
            <el-menu-item index="/quote">🧮 ① 报价与收益测算</el-menu-item>
            <el-menu-item index="/customer">🤝 ② 客户管理 (CRM)</el-menu-item>
            <el-menu-item index="/contract">📄 ③ 租赁合同与计划</el-menu-item>
            <el-menu-item index="/purchase-contract">📑 ④ 采购合同与付款</el-menu-item>
            <el-menu-item index="/purchase">🛒 ⑤ 采购入库单</el-menu-item>
            <el-menu-item index="/rent">🧾 ⑥ 收租与销项开票</el-menu-item>
            <el-menu-item index="/transfer">🔁 ⑦ 到期留购与处置</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="supply">
            <template #title><span>📦 设备与供应链</span></template>
            <el-menu-item index="/asset">📦 租赁设备台账</el-menu-item>
            <el-menu-item index="/supplier">🏭 供应商档案</el-menu-item>
            <el-menu-item index="/supplier-inspection">🔍 供应商考察</el-menu-item>
            <el-menu-item index="/inventory">🏷️ 库房短租与物资</el-menu-item>
            <el-menu-item index="/service">🛠️ 维保与服务</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="finance">
            <template #title><span>💰 财务与报表</span></template>
            <el-menu-item index="/cashflow">💰 资金计划与分红</el-menu-item>
            <el-menu-item index="/voucher">📚 会计凭证与税务</el-menu-item>
            <el-menu-item index="/monthly">📑 月度经营报表</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="admin">
            <template #title><span>⚙️ 运营与管理</span></template>
            <el-menu-item index="/task">✅ 任务与审批</el-menu-item>
            <el-menu-item index="/roster">🧑‍💼 花名册与提成</el-menu-item>
            <el-menu-item index="/bi-pdca">📊 BI 分析与 PDCA</el-menu-item>
            <el-menu-item index="/import">📥 数据导入中心</el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>
      <a class="build-info" :href="commitUrl" target="_blank" rel="noopener" :title="buildTip">
        <span>版本 {{ shortCommit }}</span>
        <span>{{ commitTimeText }}</span>
      </a>
    </el-aside>
    <el-container>
      <el-header class="app-header">
        <button v-if="route.name === 'workbench'" class="mobile-menu-toggle" type="button" :aria-expanded="mobileMenuOpen" @click="mobileMenuOpen = !mobileMenuOpen">{{ mobileMenuOpen ? '关闭菜单' : '业务菜单' }}</button>
        <span class="title">{{ (route.meta.title as string) || '曜石智能 · 租赁板块' }}</span>
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
/* 侧栏按品牌规范:曜石黑底 + 曜金标志,分隔线走低透明度金线 */
.app-aside { background: var(--brand-obsidian); color: #fff; overflow-y: auto; display: flex; flex-direction: column; }
.brand { padding: 18px 20px 14px; text-align: center; border-bottom: 1px solid var(--brand-gold-line); }
/* 暗色侧栏:el-menu 默认文字色 #303133 压深底看不见,显式设亮色主题变量 */
.app-aside .el-menu {
  background: transparent;
  border: none;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #c7ccd4;
  --el-menu-hover-text-color: #ffffff;
  --el-menu-hover-bg-color: var(--brand-graphite);
  --el-menu-active-color: var(--brand-gold-bright);
  flex: 1 0 auto;
}
.app-aside .el-sub-menu__title {
  color: var(--brand-gold-dim) !important;
  font-size: 12.5px;
  font-weight: 600;
  height: 40px;
  line-height: 40px;
}
.app-aside .el-sub-menu__title:hover {
  background-color: var(--brand-graphite) !important;
  color: var(--brand-gold-bright) !important;
}
.app-aside .el-sub-menu .el-menu-item {
  height: 38px;
  line-height: 38px;
  font-size: 13px;
  padding-left: 30px !important;
}
/* 侧栏底部构建版本小字 */
.app-aside .build-info {
  display: block;
  flex-shrink: 0;
  margin-top: auto;
  padding: 10px 20px 14px;
  font-size: 11px;
  line-height: 1.7;
  color: #6b6b70;
  text-decoration: none;
  white-space: nowrap;
  overflow: hidden;
}
.app-aside .build-info span { display: block; }
.app-aside .build-info:hover { color: var(--brand-gold-dim); }
.app-aside .el-menu-item { color: #c7ccd4 !important; }
.app-aside .el-menu-item:hover { background-color: var(--brand-graphite) !important; color: #fff !important; }
/* 选中项:亮金字 + 石墨灰底 + 左侧金色指示条 */
.app-aside .el-menu-item.is-active {
  color: var(--brand-gold-bright) !important;
  background-color: var(--brand-graphite) !important;
  box-shadow: inset 3px 0 0 var(--brand-gold);
}
.app-header { display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid #eee; height: 52px; }
.app-header .title { font-size: 15.5px; font-weight: 600; }
.app-header .right { display: flex; align-items: center; gap: 8px; }
.app-header .env { font-size: 12px; color: #999; }
.mobile-menu-toggle { display: none; }
/* 窄屏工作台保留可展开的菜单，数据图表使用完整宽度。 */
@media (max-width: 760px) {
  .workbench-layout .app-aside { display: none; }
  .workbench-layout.workbench-menu-open .app-aside { display: flex; position: fixed; z-index: 50; top: 52px; bottom: 0; left: 0; box-shadow: 8px 0 24px rgba(0,0,0,.2); }
  .workbench-layout .mobile-menu-toggle { display: inline-block; padding: 6px 10px; border: 1px solid #cfc6b7; border-radius: 5px; background: #fff; color: #514b41; }
  .workbench-layout .app-header { flex-shrink: 0; }
  .workbench-layout > .el-container { min-width: 0; }
  .workbench-layout .el-main { padding: 12px; }
  .workbench-layout .app-header { height: auto; min-height: 52px; padding: 10px 14px; gap: 10px; flex-wrap: wrap; }
  .workbench-layout .app-header .title { font-size: 13px; }
  .workbench-layout .app-header .env { color: #666; }
}
</style>
