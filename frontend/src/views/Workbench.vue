<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchWorkbench, type Workbench } from '@/api/workbench'

const router = useRouter()
const wb = ref<Workbench | null>(null)
const loading = ref(false)

const levelTag: Record<string, string> = { 红灯: 'danger', 超限: 'danger', 预警: 'warning', 正常: 'success' }
const prioTag: Record<string, string> = { 高: 'danger', 中: 'warning', 低: 'info' }

async function load() {
  loading.value = true
  try {
    wb.value = await fetchWorkbench()
  } finally {
    loading.value = false
  }
}
function pct(v?: number) {
  if (v === null || v === undefined) return '0.0%'
  return (v * 100).toFixed(1) + '%'
}
function money(v?: number) {
  if (v === null || v === undefined) return '¥0'
  return '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 0 })
}
onMounted(load)
defineExpose({ load })
</script>

<template>
  <div v-loading="loading" class="wb">
    <div class="hero">
      <div>
        <h2>曜石科技 · 租赁板块工作台</h2>
        <div class="scope">
          <template v-if="wb">{{ wb.userName || '未登录' }}<template v-if="wb.role">（{{ wb.role }}）</template><template v-if="wb.scopeNote"> · {{ wb.scopeNote }}</template></template>
          <template v-else-if="loading">加载中…</template>
          <template v-else>—（无数据）</template>
        </div>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <!-- 老板驾驶舱 KPI -->
    <el-row :gutter="16" class="kpis">
      <el-col :span="6"><div class="kpi"><div class="v">{{ pct(wb?.kpi?.rentedRate) }}</div><div class="l">在租率<span v-if="wb?.kpi?.rentedCount != null" class="sub">（{{ wb?.kpi?.rentedCount }}/{{ wb?.kpi?.activeAssetCount }}）</span></div></div></el-col>
      <el-col :span="6"><div class="kpi"><div class="v">{{ money(wb?.kpi?.receivableTotal) }}</div><div class="l">应收合计</div></div></el-col>
      <el-col :span="6"><div class="kpi"><div class="v">{{ money(wb?.kpi?.monthDistribution) }}</div><div class="l">本月分配<span v-if="wb?.kpi?.period" class="sub">（{{ wb.kpi.period }}）</span></div></div></el-col>
      <el-col :span="6"><div class="kpi"><div class="v up">{{ pct(wb?.kpi?.weightedReturn) }}</div><div class="l">加权回报（税后IRR）</div></div></el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 亮灯红点 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header><b>⚠️ 该处理的事（亮灯红点）</b></template>
          <div v-if="!wb?.redPoints?.length" class="empty">当前无红点 · 一切正常 ✅</div>
          <div v-for="r in wb?.redPoints" :key="r.key" class="redpoint" @click="router.push(r.link)">
            <div class="rp-left">
              <el-tag :type="levelTag[r.level] || 'warning'" effect="dark" size="small">{{ r.level }}</el-tag>
              <span class="rp-label">{{ r.label }}</span>
            </div>
            <div class="rp-right"><b>{{ r.count }}</b><el-icon class="arrow">›</el-icon></div>
          </div>
        </el-card>
      </el-col>

      <!-- 我的待办 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <b>✅ 我的待办</b>
            <el-tag size="small" type="info" style="margin-left:8px">{{ wb?.myTaskCount || 0 }} 项</el-tag>
            <el-button size="small" text style="float:right" @click="router.push('/task')">进任务中心 ›</el-button>
          </template>
          <el-table :data="wb?.myTasks || []" size="small" empty-text="暂无待办任务">
            <el-table-column prop="title" label="任务" min-width="180" />
            <el-table-column prop="type" label="类型" width="110" />
            <el-table-column label="来源" width="80">
              <template #default="{ row }"><el-tag size="small" :type="row.source === '系统' ? 'warning' : 'info'">{{ row.source }}</el-tag></template>
            </el-table-column>
            <el-table-column label="优先级" width="80">
              <template #default="{ row }"><el-tag size="small" :type="prioTag[row.priority] || 'info'">{{ row.priority }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="90" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <div class="modules">
      <el-tag class="mtag" @click="router.push('/purchase')">🛒 采购投放</el-tag>
      <el-tag class="mtag" @click="router.push('/rent')">💰 收租</el-tag>
      <el-tag class="mtag" @click="router.push('/task')">✅ 任务·审批</el-tag>
      <el-tag class="mtag" @click="router.push('/roster')">🧑‍💼 花名册·提成</el-tag>
      <el-tag class="mtag" @click="router.push('/cashflow')">📊 现金流/分配</el-tag>
      <el-tag class="mtag" @click="router.push('/monthly')">📑 月度报表</el-tag>
    </div>
  </div>
</template>

<style scoped>
.wb { padding: 4px; }
.hero { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.hero h2 { margin: 0 0 4px; font-size: 20px; }
.scope { color: #909399; font-size: 13px; }
.kpis { margin-bottom: 16px; }
.kpi { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 16px; text-align: center; }
.kpi .v { font-size: 26px; font-weight: 700; color: #303133; }
.kpi .v.up { color: #67c23a; }
.kpi .l { color: #909399; font-size: 13px; margin-top: 6px; }
.kpi .sub { color: #c0c4cc; font-size: 11px; }
.empty { color: #c0c4cc; padding: 12px 0; text-align: center; }
.redpoint { display: flex; justify-content: space-between; align-items: center; padding: 10px 6px; border-bottom: 1px dashed #f0f0f0; cursor: pointer; }
.redpoint:hover { background: #fafafa; }
.rp-label { margin-left: 8px; }
.rp-right b { color: #f56c6c; font-size: 18px; margin-right: 4px; }
.arrow { color: #c0c4cc; }
.modules { margin-top: 16px; }
.mtag { margin-right: 10px; cursor: pointer; padding: 8px 12px; font-size: 13px; }
</style>
