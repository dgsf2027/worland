<script setup lang="ts">
/**
 * 工作台 · 经营总览。
 * 版式自上而下：标题与时间范围 → 需要处理（红点 + 待办，唯一入口）→ KPI → 两张按月图 → 客户阶段 + 我的待办。
 * 颜色含义固定：青绿 = 收进来的钱（浅 = 应收，深 = 已收/计划收款），金 = 付出去的钱，墨色圆点 = 净额；
 * 红 / 琥珀只用于提醒等级，且总配图标和文字。
 */
import { computed, ref } from 'vue'
import MonthlyColumnChart, { type ColumnRow, type ColumnSeries } from './MonthlyColumnChart.vue'
import type { Workbench } from '@/api/workbench'
import type { DashboardData, Resource } from '@/api/dashboard'
import { compactYuan, isNumber, yuan } from '@/utils/dashboardCharts'

const props = defineProps<{
  workbench: Workbench | null
  charts: DashboardData
  loading: boolean
  chartsLoading: boolean
  failed: boolean
  updatedAt: string
  months: number
}>()
const emit = defineEmits<{ (e: 'change-months', value: number): void; (e: 'refresh'): void }>()

const COLORS = { in: '#138a78', inPlan: '#7cc4b5', out: '#c08a2e' }
const kpi = computed(() => props.workbench?.kpi)
const count = (value: unknown) => (isNumber(value) ? value.toLocaleString('zh-CN') : '—')
/** 十万以上用「万」短写，完整金额放在悬停提示里 */
const amount = (value: unknown) => (isNumber(value) && Math.abs(value) >= 1e5 ? compactYuan(value) : yuan(value))

// ---------- 需要处理 ----------
type Tone = 'critical' | 'warning' | 'good'
const toneOf = (level: string): Tone => (level === '红灯' || level === '超限' ? 'critical' : level === '正常' ? 'good' : 'warning')
const redPoints = computed(() => props.workbench?.redPoints || [])
/** 「需要处理」格子数：红点 + 待办（无红点时多一格「正常」） */
const attCount = computed(() => redPoints.value.length + 1 + (props.workbench && !redPoints.value.length ? 1 : 0))

// ---------- KPI ----------
const cash = computed(() => (props.charts.cashflow.status === 'ready' ? props.charts.cashflow.data : null))
const forecastIn = computed(() => cash.value?.forecast.reduce((s, p) => s + p.inflow, 0))
const forecastOut = computed(() => cash.value?.forecast.reduce((s, p) => s + p.outflow, 0))
const forecastNet = computed(() => cash.value?.forecast.reduce((s, p) => s + p.net, 0))
const inventory = computed(() => (props.charts.inventory.status === 'ready' ? props.charts.inventory.data : null))
const assetValid = computed(() => {
  const r = kpi.value?.rentedCount, a = kpi.value?.activeAssetCount
  return isNumber(r) && isNumber(a) && r >= 0 && a >= r
})
const rentedShare = computed(() => (assetValid.value && kpi.value!.activeAssetCount! > 0 ? kpi.value!.rentedCount! / kpi.value!.activeAssetCount! : 0))

interface Tile { key: string; label: string; value: string; title?: string; sub: string; meter?: number; tone?: Tone; subTone?: Tone }
const tiles = computed<Tile[]>(() => {
  const out: Tile[] = []
  const k = kpi.value
  if (isNumber(k?.receivableTotal)) {
    out.push({ key: 'receivable', label: '待收租金', value: amount(k!.receivableTotal), title: yuan(k!.receivableTotal, 2),
      sub: cash.value ? `一年内到期 ${amount(cash.value.receivable.within1Year)}` : '未核销租金计划 · 含未到期' })
  }
  if (cash.value && isNumber(forecastNet.value)) {
    out.push({ key: 'net', label: `未来 ${props.months} 个月计划净额`, value: amount(forecastNet.value), title: yuan(forecastNet.value, 2),
      sub: `收 ${amount(forecastIn.value)} · 付 ${amount(forecastOut.value)}`, tone: forecastNet.value < 0 ? 'critical' : undefined })
  }
  if (isNumber(k?.monthDistribution)) {
    out.push({ key: 'distribution', label: '本月可分配', value: amount(k!.monthDistribution), title: yuan(k!.monthDistribution, 2), sub: k?.period ? `${k.period} 分配方案` : '当月分配方案' })
  }
  if (isNumber(k?.weightedReturn)) {
    out.push({ key: 'irr', label: '加权回报', value: `${(k!.weightedReturn! * 100).toFixed(1)}%`, sub: '税后 IRR' })
  }
  if (assetValid.value) {
    out.push({ key: 'rented', label: '设备在租率', value: `${(rentedShare.value * 100).toFixed(1)}%`, meter: rentedShare.value,
      sub: k!.activeAssetCount === 0 ? '暂无有效设备' : `${count(k!.rentedCount)} / ${count(k!.activeAssetCount)} 台有效设备在租` })
  }
  if (inventory.value) {
    out.push({ key: 'warehouse', label: '仓库出租单', value: `${count(inventory.value.activeRentalCount)} 单`,
      sub: `逾期未归还 ${count(inventory.value.overdueRentalCount)} 单`, subTone: inventory.value.overdueRentalCount > 0 ? 'warning' : undefined })
  }
  return out
})

// ---------- 图表 ----------
const tableView = ref<Record<string, boolean>>({})
const toggleTable = (key: string) => { tableView.value = { ...tableView.value, [key]: !tableView.value[key] } }

const rentSeries: ColumnSeries[] = [
  { key: 'receivable', label: '应收', color: COLORS.inPlan },
  { key: 'collected', label: '已收', color: COLORS.in },
]
const rentRows = computed<ColumnRow[]>(() => (props.charts.rentTrend.data?.points || [])
  .map(p => ({ period: p.period, values: { receivable: p.receivable, collected: p.collected } })))
const rentTotals = computed(() => {
  const pts = props.charts.rentTrend.data?.points || []
  return { receivable: pts.reduce((s, p) => s + p.receivable, 0), collected: pts.reduce((s, p) => s + p.collected, 0) }
})

const cashSeries: ColumnSeries[] = [
  { key: 'inflow', label: '计划收款', color: COLORS.in },
  { key: 'outflow', label: '计划付款', color: COLORS.out, sign: -1 },
]
const cashNet = { key: 'net', label: '净额' }
const cashRows = computed<ColumnRow[]>(() => (cash.value?.forecast || [])
  .map(p => ({ period: p.period, values: { inflow: p.inflow, outflow: p.outflow, net: p.net } })))

const showRent = computed(() => props.charts.rentTrend.status !== 'restricted')
const showCash = computed(() => props.charts.cashflow.status !== 'restricted')
const showCustomers = computed(() => props.charts.customerStages.status !== 'restricted')

function stateText(resource: Resource<unknown>) {
  if (resource.status === 'error') return '数据加载失败'
  if (resource.status === 'idle' || resource.status === 'loading' || props.chartsLoading) return '正在读取业务数据…'
  return '暂无可用数据'
}

// ---------- 客户阶段 ----------
const stages = computed(() => (props.charts.customerStages.status === 'ready' ? props.charts.customerStages.data || [] : []))
const stageTotal = computed(() => stages.value.reduce((s, x) => s + x.count, 0))
const stageMax = computed(() => Math.max(1, ...stages.value.map(s => s.count)))

// ---------- 待办 ----------
const tasks = computed(() => props.workbench?.myTasks || [])
const prioTone: Record<string, Tone | 'low'> = { 高: 'critical', 中: 'warning', 低: 'low' }
const shortDate = (d?: string) => (d && /^\d{4}-\d{2}-\d{2}/.test(d) ? `${Number(d.slice(5, 7))}月${Number(d.slice(8, 10))}日` : '')

const statusLine = computed(() => {
  if (props.loading) return '正在更新…'
  if (props.failed) return '工作台数据加载失败'
  return props.updatedAt ? `更新于 ${props.updatedAt}` : '等待业务数据'
})
</script>

<template>
  <section class="ov" :aria-busy="loading || chartsLoading" aria-labelledby="ov-title">
    <!-- 标题行：一处时间范围、一处刷新 -->
    <header class="ov-head">
      <div class="ov-title">
        <h1 id="ov-title">经营总览</h1>
        <p>
          <span>{{ workbench?.scopeNote || '数据按账号权限展示' }}</span>
          <span class="dot-sep" aria-hidden="true">·</span>
          <span role="status">{{ statusLine }}</span>
          <button v-if="failed" type="button" class="text-btn" @click="emit('refresh')">重试</button>
        </p>
      </div>
      <div class="ov-ctrls">
        <div v-if="showRent || showCash" class="seg" role="group" aria-label="图表时间范围">
          <button v-for="m in [6, 12]" :key="m" type="button" :aria-pressed="months === m" :class="{ on: months === m }"
                  :disabled="chartsLoading" @click="months !== m && emit('change-months', m)">近 {{ m }} 个月</button>
        </div>
        <button type="button" class="refresh" :disabled="loading" @click="emit('refresh')">
          <svg :class="{ spin: loading }" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M13.5 8a5.5 5.5 0 1 1-1.6-3.9M13.5 2.5v3h-3" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          刷新
        </button>
      </div>
    </header>

    <!-- 需要处理：红点 + 待办，唯一入口 -->
    <nav class="attention" aria-label="需要处理的事项" :data-n="attCount" :style="{ '--n': attCount }">
      <RouterLink v-for="r in redPoints" :key="r.key" :to="r.link" class="att" :class="toneOf(r.level)">
        <span class="att-top">
          <svg class="st-icon" viewBox="0 0 16 16" width="15" height="15" aria-hidden="true">
            <template v-if="toneOf(r.level) === 'critical'"><circle cx="8" cy="8" r="7" /><path d="M8 4.4v4.4M8 11.2v.4" class="glyph" /></template>
            <template v-else-if="toneOf(r.level) === 'warning'"><path d="M8 1.6 15 14H1z" /><path d="M8 6v3.6M8 11.6v.3" class="glyph" /></template>
            <template v-else><circle cx="8" cy="8" r="7" /><path d="m4.8 8.2 2.1 2.1 4.3-4.4" class="glyph" /></template>
          </svg>
          <span class="att-level">{{ r.level }}</span>
        </span>
        <span class="att-main"><span class="att-label">{{ r.label }}</span><b>{{ count(r.count) }}</b></span>
      </RouterLink>
      <div v-if="workbench && !redPoints.length" class="att good static">
        <span class="att-top"><svg class="st-icon" viewBox="0 0 16 16" width="15" height="15" aria-hidden="true"><circle cx="8" cy="8" r="7" /><path d="m4.8 8.2 2.1 2.1 4.3-4.4" class="glyph" /></svg><span class="att-level">正常</span></span>
        <span class="att-main"><span class="att-label">暂无亮灯提醒</span></span>
      </div>
      <RouterLink to="/task" class="att todo">
        <span class="att-top"><svg class="st-icon" viewBox="0 0 16 16" width="15" height="15" aria-hidden="true"><rect x="2" y="2" width="12" height="12" rx="3" /><path d="m5 8.2 2 2 4-4.2" class="glyph" /></svg><span class="att-level">任务中心</span></span>
        <span class="att-main"><span class="att-label">我的待办</span><b>{{ count(workbench?.myTaskCount) }}</b></span>
      </RouterLink>
    </nav>

    <!-- KPI -->
    <dl v-if="tiles.length" class="kpis" :class="{ stale: loading, few: tiles.length <= 3 }" :data-n="tiles.length" :style="{ '--n': tiles.length }">
      <div v-for="t in tiles" :key="t.key" class="kpi">
        <dt>{{ t.label }}</dt>
        <dd :title="t.title" :class="t.tone">{{ t.value }}</dd>
        <div v-if="t.meter != null" class="meter" aria-hidden="true"><span :style="{ width: `${Math.min(100, t.meter * 100)}%` }"></span></div>
        <p :class="t.subTone"><svg v-if="t.subTone === 'warning'" class="sub-icon" viewBox="0 0 16 16" width="12" height="12" aria-hidden="true"><path d="M8 1.6 15 14H1z" /><path d="M8 6v3.6M8 11.6v.3" class="glyph" /></svg>{{ t.sub }}</p>
      </div>
    </dl>
    <div v-else-if="loading" class="kpis placeholder" aria-hidden="true"><div v-for="n in 4" :key="n" class="kpi"></div></div>

    <!-- 按月图 -->
    <div v-if="showRent || showCash" class="charts" :class="{ single: !(showRent && showCash) }">
      <article v-if="showRent" class="card" :class="{ stale: chartsLoading && rentRows.length }">
        <header class="card-head">
          <div>
            <h2>收租走势</h2>
            <p>近 {{ months }} 个月 · 按月汇总正常收租原单</p>
          </div>
          <div class="tools">
            <el-tooltip :content="charts.rentTrend.source || '按真实收租单归集；缺失数据不按零填充。'" placement="top" :show-after="150" popper-class="ov-tip">
              <button type="button" class="icon-btn" aria-label="统计口径"><svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true"><circle cx="8" cy="8" r="6.6" fill="none" stroke="currentColor" stroke-width="1.3"/><path d="M8 7.2v4M8 4.9v.2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg></button>
            </el-tooltip>
            <button v-if="rentRows.length" type="button" class="text-btn" @click="toggleTable('rent')">{{ tableView.rent ? '看图' : '看表' }}</button>
            <RouterLink to="/rent" class="text-btn">明细</RouterLink>
          </div>
        </header>
        <template v-if="charts.rentTrend.status === 'ready' && rentRows.length">
          <div class="legend">
            <span v-for="s in rentSeries" :key="s.key"><i class="sw" :style="{ background: s.color }"></i>{{ s.label }}<b :title="yuan(rentTotals[s.key as 'receivable' | 'collected'], 2)">{{ amount(rentTotals[s.key as 'receivable' | 'collected']) }}</b></span>
          </div>
          <MonthlyColumnChart :rows="rentRows" :series="rentSeries" title="收租走势" :table="!!tableView.rent" />
        </template>
        <div v-else class="state" :class="{ error: charts.rentTrend.status === 'error' }">{{ stateText(charts.rentTrend) }}<button v-if="charts.rentTrend.status === 'error'" type="button" class="text-btn" @click="emit('refresh')">重试</button></div>
      </article>

      <article v-if="showCash" class="card" :class="{ stale: chartsLoading && cashRows.length }">
        <header class="card-head">
          <div>
            <h2>未来收付计划</h2>
            <p>本月起 {{ months }} 个月<template v-if="cash?.asOf"> · 截至 {{ cash.asOf }}</template></p>
          </div>
          <div class="tools">
            <el-tooltip :content="charts.cashflow.source || '依据待收与待付的到期计划，不代表实际流水或银行余额。'" placement="top" :show-after="150" popper-class="ov-tip">
              <button type="button" class="icon-btn" aria-label="统计口径"><svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true"><circle cx="8" cy="8" r="6.6" fill="none" stroke="currentColor" stroke-width="1.3"/><path d="M8 7.2v4M8 4.9v.2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg></button>
            </el-tooltip>
            <button v-if="cashRows.length" type="button" class="text-btn" @click="toggleTable('cash')">{{ tableView.cash ? '看图' : '看表' }}</button>
            <RouterLink to="/cashflow" class="text-btn">明细</RouterLink>
          </div>
        </header>
        <template v-if="cash && cashRows.length">
          <div class="legend">
            <span><i class="sw" :style="{ background: COLORS.in }"></i>计划收款<b>{{ amount(forecastIn) }}</b></span>
            <span><i class="sw" :style="{ background: COLORS.out }"></i>计划付款<b>{{ amount(forecastOut) }}</b></span>
            <span><i class="sw dot"></i>净额<b>{{ amount(forecastNet) }}</b></span>
          </div>
          <MonthlyColumnChart :rows="cashRows" :series="cashSeries" :net="cashNet" title="未来收付计划" :table="!!tableView.cash" />
        </template>
        <div v-else class="state" :class="{ error: charts.cashflow.status === 'error' }">{{ stateText(charts.cashflow) }}<button v-if="charts.cashflow.status === 'error'" type="button" class="text-btn" @click="emit('refresh')">重试</button></div>
      </article>
    </div>

    <!-- 客户阶段 + 我的待办 -->
    <div class="lower" :class="{ single: !showCustomers }">
      <article v-if="showCustomers" class="card" :class="{ stale: chartsLoading && stages.length }">
        <header class="card-head">
          <div>
            <h2>客户阶段</h2>
            <p>当前可见客户 {{ count(stages.length ? stageTotal : undefined) }} 家 · 存量分布</p>
          </div>
          <div class="tools">
            <el-tooltip :content="charts.customerStages.source || '当前阶段存量分布，不代表阶段转化率。'" placement="top" :show-after="150" popper-class="ov-tip">
              <button type="button" class="icon-btn" aria-label="统计口径"><svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true"><circle cx="8" cy="8" r="6.6" fill="none" stroke="currentColor" stroke-width="1.3"/><path d="M8 7.2v4M8 4.9v.2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg></button>
            </el-tooltip>
            <RouterLink to="/customer" class="text-btn">明细</RouterLink>
          </div>
        </header>
        <ul v-if="stages.length" class="stages" aria-label="各阶段客户数">
          <li v-for="s in stages" :key="s.phase" :class="{ lost: s.phase === '流失' }">
            <span class="st-name">{{ s.phase }}</span>
            <span class="st-track"><span :style="{ width: s.count > 0 ? `max(${s.count / stageMax * 100}%, 3px)` : '0' }"></span></span>
            <b>{{ count(s.count) }}</b>
          </li>
        </ul>
        <div v-else class="state" :class="{ error: charts.customerStages.status === 'error' }">{{ stateText(charts.customerStages) }}<button v-if="charts.customerStages.status === 'error'" type="button" class="text-btn" @click="emit('refresh')">重试</button></div>
      </article>

      <article id="workbench-tasks" class="card">
        <header class="card-head">
          <div>
            <h2>我的待办</h2>
            <p>{{ workbench ? `共 ${count(workbench.myTaskCount)} 项，显示最近 ${tasks.length} 项` : '当前账号待处理任务' }}</p>
          </div>
          <div class="tools"><RouterLink to="/task" class="text-btn">进任务中心</RouterLink></div>
        </header>
        <ul v-if="tasks.length" class="tasks">
          <li v-for="t in tasks" :key="t.id">
            <span class="prio" :class="prioTone[t.priority] || 'low'">{{ t.priority }}</span>
            <span class="t-main">
              <span class="t-title" :title="t.title">{{ t.title }}</span>
              <span class="t-meta">{{ t.type }} · {{ t.source }}<template v-if="t.dueDate"> · <span :class="{ overdue: t.overdue }">{{ t.overdue ? '已逾期 ' : '' }}{{ shortDate(t.dueDate) }}</span></template></span>
            </span>
            <span class="t-status">{{ t.status }}</span>
          </li>
        </ul>
        <div v-else class="state small">{{ loading ? '正在加载待办…' : !workbench ? '暂无可见数据' : '暂无待办任务' }}</div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.ov {
  --viz-surface: #ffffff;
  --viz-ink: #1d2129;
  --viz-secondary: #5b616e;
  --viz-muted: #8b919c;
  --viz-grid: #eef0f3;
  --viz-axis: #c5cad3;
  --viz-border: rgba(17, 24, 39, 0.1);
  --viz-wash: rgba(17, 24, 39, 0.045);
  --viz-focus: #a5803f;
  --line: #e7e9ed;
  --critical: #c8423a; --critical-text: #b03a32; --critical-bg: #fdf3f2;
  --warning: #d4910f; --warning-text: #8f5d00; --warning-bg: #fdf8ee;
  --good: #2f8f5b; --good-text: #23744a; --good-bg: #f1f8f4;
  color: var(--viz-ink);
  font-variant-numeric: normal;
  container-type: inline-size;
}
.ov * { box-sizing: border-box; }
:where(.ov) :where(h1, h2, p, dl, dd, ul) { margin: 0; padding: 0; }
.ov :is(a, button):focus-visible { outline: 2px solid var(--viz-focus); outline-offset: 2px; border-radius: 6px; }
.ov a { color: inherit; text-decoration: none; }

/* ---------- 标题行 ---------- */
.ov-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 14px; }
.ov-title h1 { font-size: 20px; font-weight: 600; line-height: 1.3; letter-spacing: .02em; }
.ov-title p { margin-top: 4px; font-size: 12.5px; color: var(--viz-secondary); display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.dot-sep { color: var(--viz-muted); }
.ov-ctrls { display: flex; align-items: center; gap: 10px; }
.seg { display: inline-flex; padding: 3px; border-radius: 8px; background: #f1f2f4; }
.seg button { border: 0; background: transparent; font: inherit; font-size: 12.5px; color: var(--viz-secondary); padding: 6px 12px; border-radius: 6px; cursor: pointer; }
.seg button.on { background: #fff; color: var(--viz-ink); font-weight: 600; box-shadow: 0 1px 2px rgba(15, 18, 24, .08), 0 0 0 1px rgba(15, 18, 24, .04); }
.seg button:disabled { cursor: progress; }
.refresh { display: inline-flex; align-items: center; gap: 6px; font: inherit; font-size: 12.5px; color: var(--viz-ink); background: #fff; border: 1px solid var(--line); border-radius: 8px; padding: 6px 12px; cursor: pointer; }
.refresh:hover { border-color: #cfd3da; background: #fafafb; }
.refresh:disabled { cursor: progress; color: var(--viz-secondary); }
.spin { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.text-btn { font: inherit; font-size: 12.5px; color: var(--viz-secondary); background: none; border: 0; padding: 4px 6px; border-radius: 6px; cursor: pointer; }
.text-btn:hover { color: var(--viz-ink); background: #f3f4f6; }
.icon-btn { display: inline-grid; place-items: center; width: 26px; height: 26px; color: var(--viz-muted); background: none; border: 0; border-radius: 6px; cursor: help; }
.icon-btn:hover { color: var(--viz-ink); background: #f3f4f6; }

/* ---------- 需要处理 ---------- */
.attention { display: grid; grid-template-columns: repeat(var(--n), minmax(0, 1fr)); border: 1px solid var(--line); border-radius: 12px; background: #fff; overflow: hidden; margin-bottom: 14px; }
/* 分隔线画在每格的右、下两侧，外框裁掉最外一圈：换行到几列都不会出现双线或缺线 */
.att { position: relative; display: flex; flex-direction: column; gap: 6px; padding: 12px 16px 12px 18px; box-shadow: 1px 0 0 var(--line), 0 1px 0 var(--line); transition: background .15s; }
.att::before { content: ''; position: absolute; left: 0; top: 12px; bottom: 12px; width: 3px; border-radius: 0 3px 3px 0; }
.att:not(.static):hover { background: #fafafb; }
.att-top { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.att-main { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.att-label { font-size: 13.5px; color: var(--viz-ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.att-main b { font-size: 22px; font-weight: 600; line-height: 1; }
.st-icon .glyph { fill: none; stroke: #fff; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.att.critical::before { background: var(--critical); }
.att.critical .st-icon { fill: var(--critical); }
.att.critical .att-level { color: var(--critical-text); }
.att.warning::before { background: var(--warning); }
.att.warning .st-icon { fill: var(--warning); }
.att.warning .att-level { color: var(--warning-text); }
.att.good .st-icon { fill: var(--good); }
.att.good .att-level { color: var(--good-text); }
.att.todo .st-icon { fill: #5b6472; }
.att.todo .att-level { color: var(--viz-secondary); }

/* ---------- KPI ---------- */
.kpis { display: grid; grid-template-columns: repeat(var(--n), minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; transition: opacity .2s; }
.kpis.few { grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); }
.kpi { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 14px 16px 13px; min-width: 0; min-height: 104px; display: flex; flex-direction: column; }
.kpi dt { font-size: 12.5px; color: var(--viz-secondary); }
.kpi dd { margin-top: 8px; font-size: 25px; font-weight: 600; line-height: 1.15; letter-spacing: -.01em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.kpi dd.critical { color: var(--critical-text); }
.kpi dd.warning { color: var(--warning-text); }
.kpi p { margin-top: auto; padding-top: 8px; font-size: 12px; color: var(--viz-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.kpi p.warning { color: var(--warning-text); }
.sub-icon { fill: var(--warning); vertical-align: -1px; margin-right: 4px; }
.sub-icon .glyph { fill: none; stroke: #fff; stroke-width: 1.7; stroke-linecap: round; }
.meter { margin-top: 10px; height: 5px; border-radius: 3px; background: #e3f1ee; overflow: hidden; }
.meter span { display: block; height: 100%; border-radius: 3px; background: #138a78; }
.placeholder .kpi { background: #f6f7f8; border-color: transparent; }
.stale { opacity: .55; }

/* ---------- 卡片 ---------- */
.charts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-bottom: 14px; }
.charts.single { grid-template-columns: minmax(0, 1fr); }
.lower { display: grid; grid-template-columns: minmax(0, 5fr) minmax(0, 7fr); gap: 14px; }
.lower.single { grid-template-columns: minmax(0, 1fr); }
.card { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 16px 18px 14px; min-width: 0; display: flex; flex-direction: column; transition: opacity .2s; }
.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.card-head h2 { font-size: 15px; font-weight: 600; line-height: 1.4; }
.card-head p { margin-top: 2px; font-size: 12px; color: var(--viz-muted); }
.tools { display: flex; align-items: center; gap: 2px; margin-right: -6px; }
.legend { display: flex; flex-wrap: wrap; gap: 6px 20px; margin: 12px 0 6px; font-size: 12.5px; color: var(--viz-secondary); }
.legend > span { display: inline-flex; align-items: center; gap: 7px; }
.legend b { color: var(--viz-ink); font-weight: 600; font-size: 14px; margin-left: 2px; }
.sw { width: 10px; height: 10px; border-radius: 3px; display: inline-block; }
.sw.dot { border-radius: 50%; background: var(--viz-ink); width: 9px; height: 9px; }
.state { flex: 1; min-height: 200px; display: flex; align-items: center; justify-content: center; gap: 8px; margin-top: 12px; border-radius: 8px; background: #f8f9fa; color: var(--viz-muted); font-size: 13px; text-align: center; padding: 20px; }
.state.small { min-height: 120px; }
.state.error { color: var(--critical-text); background: #fbf8f7; }

/* ---------- 客户阶段 ---------- */
.stages { list-style: none; margin-top: 12px; display: flex; flex-direction: column; gap: 4px; }
.stages li { display: grid; grid-template-columns: 44px minmax(0, 1fr) 36px; align-items: center; gap: 12px; height: 32px; font-size: 13px; }
.stages li.lost { margin-top: 6px; padding-top: 10px; height: 42px; border-top: 1px dashed #e3e5e9; }
.st-name { color: var(--viz-secondary); }
.st-track { height: 10px; border-radius: 0 4px 4px 0; background: transparent; position: relative; }
.st-track::before { content: ''; position: absolute; inset: 4px 0 auto 0; height: 2px; background: #f1f2f4; border-radius: 1px; }
.st-track span { position: relative; display: block; height: 100%; border-radius: 0 4px 4px 0; background: #5b6472; }
.lost .st-track span { background: #c3c8d0; }
.stages b { text-align: right; font-weight: 600; font-variant-numeric: tabular-nums; }

/* ---------- 待办 ---------- */
.tasks { list-style: none; margin-top: 8px; }
.tasks li { display: grid; grid-template-columns: 26px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid #f0f1f3; }
.tasks li:last-child { border-bottom: 0; }
.prio { display: inline-grid; place-items: center; width: 24px; height: 24px; border-radius: 6px; font-size: 12px; font-weight: 600; }
.prio.critical { color: var(--critical-text); background: var(--critical-bg); }
.prio.warning { color: var(--warning-text); background: var(--warning-bg); }
.prio.low { color: var(--viz-secondary); background: #f3f4f6; }
.t-main { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.t-title { font-size: 13.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.t-meta { font-size: 12px; color: var(--viz-muted); }
.t-meta .overdue { color: var(--critical-text); }
.t-status { font-size: 12px; color: var(--viz-secondary); background: #f3f4f6; padding: 3px 8px; border-radius: 6px; white-space: nowrap; }

/* ---------- 窄屏：KPI 按容器宽度排成整齐的行，不让最后一块落单 ---------- */
@container (max-width: 1120px) {
  .kpi { padding: 13px 14px 12px; }
  .kpi dd { font-size: 22px; }
}
@container (max-width: 900px) {
  .kpis[data-n="5"], .kpis[data-n="6"], .attention[data-n="5"], .attention[data-n="6"] { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .attention[data-n="4"] { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@container (max-width: 680px) {
  .kpis[data-n], .attention[data-n] { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 1100px) {
  .charts { grid-template-columns: minmax(0, 1fr); }
  .lower { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 640px) {
  .ov-head { align-items: flex-start; }
  .ov-ctrls { width: 100%; justify-content: space-between; }
  .att { padding: 11px 12px 11px 14px; }
  .kpis { gap: 10px; }
  .kpi { padding: 12px 13px; min-height: 96px; }
  .kpi dd { font-size: 21px; }
  .card { padding: 14px 14px 12px; }
}
</style>

<style>
/* 口径说明气泡（挂在 body 上，不能用 scoped） */
.ov-tip.el-popper { max-width: 340px; font-size: 12px; line-height: 1.7; }
</style>
