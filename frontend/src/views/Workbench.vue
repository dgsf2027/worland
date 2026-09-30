<script setup lang="ts">
import BrandLockup from '@/components/BrandLockup.vue'

/**
 * 企业文化文案（《标志标准规范 V1.0》里的 slogan + 价值观条）。
 *
 * slogan 取自规范的门头招牌用法；VALUES 三条目前是占位，
 * 要改只改这里一处，页面自动跟着变。
 */
const SLOGAN = '淬炼于火 · 锋利如曜'
/** 愿景 / 使命：品牌级表述，放在品牌头里 slogan 之下 */
const MISSION = [
  { k: '愿景', v: '科技赋能云仓' },
  { k: '使命', v: '为云仓提效降本' },
]
const VALUES = [
  { k: '先签约后采购', v: '不压货、不赌行情，每一台设备都对着一份合同' },
  { k: '一台设备一条命', v: '逐件建档，从采购到转让处置全程可追溯' },
  { k: '账实相符', v: '钱该动没动有稽核，单据与台账、凭证三方对得上' },
]

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
    <!-- 品牌头（曜石智能 · 标志标准规范 V1.0：深色版标志 + slogan） -->
    <div class="brand-hero">
      <div class="bh-main">
        <div class="bh-left">
          <BrandLockup variant="stack" :size="34" />
          <div class="bh-slogan">{{ SLOGAN }}</div>
        </div>
        <div class="bh-right">
          <div class="bh-sub">租赁板块工作台</div>
          <div class="bh-scope">
            <template v-if="wb">{{ wb.userName || '未登录' }}<template v-if="wb.role">（{{ wb.role }}）</template><template v-if="wb.scopeNote"> · {{ wb.scopeNote }}</template></template>
            <template v-else-if="loading">加载中…</template>
            <template v-else>—（无数据）</template>
          </div>
          <el-button class="bh-refresh" size="small" @click="load">刷新</el-button>
        </div>
      </div>
      <!-- 愿景 / 使命 -->
      <div class="bh-mission">
        <div v-for="m in MISSION" :key="m.k" class="bh-m">
          <span class="bh-m-k">{{ m.k }}</span>
          <span class="bh-m-v">{{ m.v }}</span>
        </div>
      </div>

      <!-- 价值观条 -->
      <div class="bh-values">
        <div v-for="it in VALUES" :key="it.k" class="bh-value">
          <b>{{ it.k }}</b><span>{{ it.v }}</span>
        </div>
      </div>
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

/* ===== 品牌头：曜石黑底 + 金字，与标志标准规范的深色版一致 ===== */
.brand-hero {
  background: var(--brand-dark-gradient);
  border: 1px solid var(--brand-gold-line);
  border-radius: 10px;
  padding: 26px 28px 0;
  margin-bottom: 16px;
  overflow: hidden;
}
.bh-main { display: flex; align-items: center; justify-content: space-between; gap: 24px; flex-wrap: wrap; }
.bh-left { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; }
.bh-slogan {
  color: var(--brand-gold-dim);
  font-size: 13px;
  letter-spacing: 0.22em;
  text-indent: 0.22em;
}
.bh-right { text-align: right; display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.bh-sub { color: #e6e6ea; font-size: 15px; font-weight: 600; letter-spacing: 1px; }
.bh-scope { color: #8a8a92; font-size: 12px; }
/* 刷新按钮走金线描边，压在深色底上 */
.bh-refresh {
  background: transparent;
  border-color: var(--brand-gold-line);
  color: var(--brand-gold);
}
.bh-refresh:hover { background: var(--brand-graphite); border-color: var(--brand-gold); color: var(--brand-gold-bright); }

/* 愿景 / 使命：比价值观条更重，亮金字 + 竖金线分隔 */
.bh-mission {
  display: flex;
  gap: 28px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 20px;
}
.bh-m { display: flex; align-items: baseline; gap: 10px; }
.bh-m + .bh-m { padding-left: 28px; border-left: 1px solid var(--brand-gold-line); }
.bh-m-k {
  color: var(--brand-gold);
  font-size: 12px;
  letter-spacing: 0.24em;
  text-indent: 0.24em;
  white-space: nowrap;
}
.bh-m-v {
  color: var(--brand-gold-bright);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-indent: 0.12em;
  white-space: nowrap;
}

/* 价值观条：三栏，金线分隔 */
.bh-values {
  display: flex;
  gap: 0;
  margin: 20px -28px 0;
  border-top: 1px solid var(--brand-gold-line);
  flex-wrap: wrap;
}
.bh-value {
  flex: 1 1 200px;
  padding: 14px 28px;
  border-left: 1px solid rgba(201, 160, 99, 0.16);
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.bh-value:first-child { border-left: none; }
.bh-value b { color: var(--brand-gold-bright); font-size: 13px; letter-spacing: 1px; font-weight: 600; }
.bh-value span { color: #8a8a92; font-size: 12px; line-height: 1.6; }

.kpis { margin-bottom: 16px; }
/* KPI 卡：白底 + 金色顶边，数值用曜石黑压住 */
.kpi {
  background: #fff;
  border: 1px solid #ebeef5;
  border-top: 2px solid var(--brand-gold);
  border-radius: 8px;
  padding: 16px;
  text-align: center;
}
.kpi .v { font-size: 26px; font-weight: 700; color: var(--brand-obsidian); }
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
.mtag {
  margin-right: 10px; cursor: pointer; padding: 8px 12px; font-size: 13px;
  border-color: var(--brand-gold-line); color: var(--brand-obsidian); background: #fdfaf4;
}
.mtag:hover { border-color: var(--brand-gold); color: var(--brand-gold); }
</style>
