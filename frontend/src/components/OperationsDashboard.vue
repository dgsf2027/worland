<script setup lang="ts">
import { computed } from 'vue'
import DashboardTimeChart from './DashboardTimeChart.vue'
import type { Workbench } from '@/api/workbench'
import type { DashboardData, Resource } from '@/api/dashboard'
import { buildDonutSegments, isNumber, money } from '@/utils/dashboardCharts'

const props = defineProps<{ workbench: Workbench | null; charts: DashboardData; loading: boolean; failed: boolean; updatedAt: string; months: number }>()
const emit = defineEmits<{ (e: 'change-months', value: number): void; (e: 'refresh'): void }>()
const kpi = computed(() => props.workbench?.kpi)
const count = (value: unknown) => isNumber(value) ? value.toLocaleString('zh-CN') : '—'
const currency = (value: unknown) => isNumber(value) ? `¥${money(value)}` : '—'
const rented = computed(() => kpi.value?.rentedCount)
const active = computed(() => kpi.value?.activeAssetCount)
const assetValid = computed(() => isNumber(rented.value) && isNumber(active.value) && rented.value! >= 0 && active.value! >= rented.value!)
const donut = computed(() => buildDonutSegments(assetValid.value ? [
  { label: '在租设备', value: rented.value!, color: '#328279' },
  { label: '其他有效设备', value: active.value! - rented.value!, color: '#d1b17b' },
] : []))
const rate = computed(() => assetValid.value ? `${(active.value! > 0 ? rented.value! / active.value! * 100 : 0).toFixed(1)}%` : '—')
const customerTotal = computed(() => props.charts.customerStages.data?.reduce((sum, stage) => sum + stage.count, 0))
const customerMax = computed(() => Math.max(1, ...(props.charts.customerStages.data || []).map(stage => stage.count)))
const riskMax = computed(() => Math.max(1, ...(props.workbench?.redPoints || []).map(point => point.count)))
const rentPoints = computed(() => (props.charts.rentTrend.data?.points || []).map(p => ({ period: p.period, first: p.receivable, second: p.collected })))
const forecastPoints = computed(() => (props.charts.cashflow.data?.forecast || []).map(p => ({ period: p.period, first: p.inflow, second: p.outflow })))
const forecastNet = computed(() => props.charts.cashflow.data?.forecast.reduce((sum, item) => sum + item.net, 0))
const inventory = computed(() => props.charts.inventory.data)
const hasFinancialCharts = computed(() => props.charts.rentTrend.status !== 'restricted' || props.charts.cashflow.status !== 'restricted')
const showReceivable = computed(() => hasFinancialCharts.value || isNumber(kpi.value?.receivableTotal))
const inventoryBreakdown = computed(() => inventory.value ? [
  { label: '逾期未归还', count: inventory.value.overdueRentalCount, color: '#b26649' },
  { label: '其余活跃单', count: Math.max(0, inventory.value.activeRentalCount - inventory.value.overdueRentalCount), color: '#328279' },
] : [])
const errors = computed(() => [props.charts.customerStages, props.charts.inventory, props.charts.rentTrend, props.charts.cashflow].some(r => r.status === 'error'))
function stateText(resource: Resource<unknown>) {
  if (resource.status === 'restricted') return '当前角色未展示此项经营数据'
  if (resource.status === 'error' || props.failed) return '数据加载失败，请重试'
  if (resource.status === 'loading' || props.loading) return '正在读取业务数据…'
  return '暂无可用数据'
}
const asOf = computed(() => props.charts.cashflow.data?.asOf || '')
</script>

<template>
  <section class="operations-dashboard" aria-labelledby="dashboard-title" :aria-busy="loading">
    <header class="dashboard-heading"><div><h1 id="dashboard-title">经营数据总览</h1><p>设备、客户、收租与履约风险，一屏掌握当前经营情况。</p></div><div class="dashboard-controls"><div v-if="hasFinancialCharts" class="period-control" aria-label="图表月份范围"><button v-for="period in [6, 12]" :key="period" type="button" :aria-pressed="months === period" :class="{ chosen: months === period }" :disabled="loading" @click="emit('change-months', period)">{{ period }} 个月</button></div><button class="refresh-button" type="button" :disabled="loading" @click="emit('refresh')">{{ loading ? '更新中…' : '刷新数据' }}</button></div></header>
    <div class="data-status" role="status"><span>{{ workbench?.scopeNote || '数据按账号权限展示' }} · {{ loading ? '正在更新图表' : failed ? '工作台数据加载失败' : errors ? '部分图表暂不可用' : updatedAt ? `更新于 ${updatedAt}` : '等待业务数据' }}</span><button v-if="errors || failed" type="button" @click="emit('refresh')">重新加载</button></div>

    <dl class="summary-strip" :class="{ 'operations-only': !showReceivable }">
      <div><dt>有效设备</dt><dd>{{ count(active) }}<small>台</small></dd><span>不含已转让、报废</span></div>
      <div><dt>设备在租率</dt><dd>{{ rate }}</dd><span>{{ count(rented) }} 台在租</span></div>
      <div><dt>活跃出租单</dt><dd>{{ count(inventory?.activeRentalCount) }}<small>单</small></dd><span>仓库 · 已预订及出租中</span></div>
      <div v-if="showReceivable"><dt>待收租金</dt><dd class="money-value">{{ currency(kpi?.receivableTotal) }}</dd><span>未核销租金计划 · 含未到期</span></div>
      <div><dt>我的待办</dt><dd>{{ count(workbench?.myTaskCount) }}<small>项</small></dd><span>当前账号待处理任务</span></div>
    </dl>

    <div class="chart-grid">
      <article v-if="charts.rentTrend.status !== 'restricted'" class="chart-panel wide"><header><div><h2>收租单金额走势</h2><p>近 {{ months }} 个月 · 应收与已收金额</p></div><RouterLink to="/rent" class="detail-link">收租明细 ↗</RouterLink></header>
        <DashboardTimeChart v-if="charts.rentTrend.status === 'ready' && rentPoints.length" :points="rentPoints" first-label="应收原单" second-label="已收原单" title="收租单金额走势" />
        <div v-else class="chart-state">{{ stateText(charts.rentTrend) }}</div>
        <p class="source-note">{{ charts.rentTrend.source || '按真实收租单归集；缺失数据不按零填充。' }}</p>
      </article>

      <article class="chart-panel"><header><div><h2>客户阶段分布</h2><p>当前可见客户 <b>{{ count(customerTotal) }}</b> 家</p></div><RouterLink to="/customer" class="detail-link">明细 ↗</RouterLink></header>
        <div v-if="charts.customerStages.status === 'ready' && charts.customerStages.data?.length" class="horizontal-chart customer-chart" role="list" aria-label="各客户阶段数量"><div v-for="(stage, index) in charts.customerStages.data" :key="stage.phase" class="horizontal-row" role="listitem"><div class="bar-caption"><span>{{ stage.phase }}</span><b>{{ count(stage.count) }} <small>家</small></b></div><div class="bar-track"><span :style="{ width: `${stage.count / customerMax * 100}%`, background: index === 5 ? '#b7a99b' : index > 2 ? '#328279' : '#c4a268' }"></span></div></div></div>
        <div v-else class="chart-state">{{ stateText(charts.customerStages) }}</div>
        <p class="source-note">{{ charts.customerStages.source || '当前阶段存量分布，不代表阶段转化率。' }}</p>
      </article>

      <article v-if="charts.cashflow.status !== 'restricted'" class="chart-panel wide"><header><div><h2>未来收付计划</h2><p>从本月起 {{ months }} 个月{{ asOf ? ` · 截至 ${asOf}` : '' }}</p></div><RouterLink to="/cashflow" class="detail-link">现金流明细 ↗</RouterLink></header>
        <template v-if="charts.cashflow.status === 'ready' && forecastPoints.length"><DashboardTimeChart :points="forecastPoints" first-label="计划收款" second-label="计划付款" mode="line" title="未来收付计划"/><div class="forecast-result"><span>区间计划净额</span><b :class="{ negative: (forecastNet || 0) < 0 }">{{ currency(forecastNet) }}</b><span>计划收款 − 计划付款</span></div></template>
        <div v-else class="chart-state">{{ stateText(charts.cashflow) }}</div>
        <p class="source-note">{{ charts.cashflow.source || '依据待收与待付的到期计划，不代表实际流水或银行余额。' }}</p>
      </article>

      <article class="chart-panel"><header><div><h2>设备在租结构</h2><p>逐台设备台账 · {{ count(active) }} 台有效设备</p></div><RouterLink to="/asset" class="detail-link">明细 ↗</RouterLink></header>
        <template v-if="assetValid"><div class="donut-layout"><svg viewBox="0 0 180 180" role="img" :aria-label="`在租${count(rented)}台，其他有效设备${count(active! - rented!)}台`"><circle cx="90" cy="90" r="69" fill="none" stroke="#ecece5" stroke-width="17"/><circle v-for="segment in donut.segments" :key="segment.label" cx="90" cy="90" r="69" fill="none" :stroke="segment.color" stroke-width="17" pathLength="100" :stroke-dasharray="`${segment.share * 100} ${100 - segment.share * 100}`" :stroke-dashoffset="-segment.offset * 100" transform="rotate(-90 90 90)"/><text x="90" y="87" text-anchor="middle" class="donut-value">{{ rate }}</text><text x="90" y="110" text-anchor="middle" class="donut-label">{{ active === 0 ? '暂无有效设备' : '在租率' }}</text></svg></div><div class="donut-legend"><div v-for="segment in donut.segments" :key="segment.label"><span><i :style="{ background: segment.color }"></i>{{ segment.label }}</span><b>{{ count(segment.value) }} <small>台</small></b></div></div></template>
        <div v-else class="chart-state">{{ failed ? '设备数据加载失败' : loading ? '正在读取设备数据…' : '暂无可见设备数据' }}</div>
        <p class="source-note">有效设备不含已转让及报废；其他有效设备不等同于空闲设备。</p>
      </article>

      <article class="chart-panel wide"><header><div><h2>需关注事项</h2><p>当前账号可见提醒 · {{ workbench ? workbench.redPoints.length : '—' }} 类</p></div><a href="#workbench-alerts" class="detail-link">查看提醒 ↗</a></header>
        <div v-if="workbench?.redPoints.length" class="horizontal-chart risk-chart" role="list" aria-label="各类提醒数量"><RouterLink v-for="risk in workbench.redPoints" :key="risk.key" :to="risk.link" class="risk-row" role="listitem"><div class="risk-caption"><span>{{ risk.label }}</span><small :class="{ danger: risk.level === '红灯' || risk.level === '超限' }">{{ risk.level }}</small></div><div class="bar-track"><span :style="{ width: `${risk.count / riskMax * 100}%`, background: risk.level === '预警' ? '#be985a' : '#b26649' }"></span></div><b>{{ count(risk.count) }}</b></RouterLink></div>
        <div v-else class="chart-state">{{ failed ? '提醒数据加载失败' : loading ? '正在读取提醒…' : '当前账号暂无提醒记录' }}</div>
        <p class="source-note">各类别可能涉及同一事项，不合计为风险总数；点击图表可进入对应业务。</p>
      </article>

      <article class="chart-panel"><header><div><h2>仓库出租与归还</h2><p>实物仓库 · {{ count(inventory?.itemCount) }} 种登记物品</p></div><RouterLink to="/inventory" class="detail-link">明细 ↗</RouterLink></header>
        <template v-if="charts.inventory.status === 'ready' && inventory"><div class="rental-highlight"><strong :class="{ negative: inventory.overdueRentalCount > 0 }">{{ count(inventory.overdueRentalCount) }}</strong><span>单逾期未归还</span></div><div class="rental-stacked" :aria-label="`活跃出租单${inventory.activeRentalCount}单，其中逾期未归还${inventory.overdueRentalCount}单`"><span v-for="item in inventoryBreakdown" :key="item.label" :style="{ width: `${inventory.activeRentalCount > 0 ? Math.min(100, item.count / inventory.activeRentalCount * 100) : 0}%`, background: item.color }"></span></div><div class="donut-legend"><div v-for="item in inventoryBreakdown" :key="item.label"><span><i :style="{ background: item.color }"></i>{{ item.label }}</span><b>{{ count(item.count) }} <small>单</small></b></div></div><p v-if="inventory.activeRentalCount === 0" class="zero-note">当前没有已预订或出租中的单据。</p></template>
        <div v-else class="chart-state">{{ stateText(charts.inventory) }}</div>
        <p class="source-note">{{ charts.inventory.source || '活跃单含已预订及出租中，逾期以已出库未归还且超过预计归还日判断。' }}</p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.operations-dashboard { --dash-ink:#24362c; --dash-muted:#667268; --dash-line:#e1e5dc; color:var(--dash-ink); margin:26px 0; }
.operations-dashboard * { box-sizing:border-box; }
.operations-dashboard ::selection { background:#e7d5b3; color:#24362c; }
.operations-dashboard a { color:inherit; text-underline-offset:4px; }
.operations-dashboard :is(a,button):focus-visible { outline:2px solid #846232; outline-offset:4px; }
h1,h2,p,dl { margin:0; }
.dashboard-heading { display:flex; justify-content:space-between; align-items:center; gap:20px; flex-wrap:wrap; }
.dashboard-heading h1 { font-size:26px; line-height:1.4; font-weight:650; color:var(--brand-obsidian); }
.dashboard-heading p { color:var(--dash-muted); font-size:13px; margin-top:7px; line-height:1.7; }
.dashboard-controls { display:flex; gap:12px; align-items:center; }
.dashboard-controls button { font:inherit; font-size:12px; cursor:pointer; }
.period-control { display:flex; padding:3px; background:#efeee7; border-radius:6px; }
.period-control button { border:0; background:transparent; color:#626b60; border-radius:4px; padding:8px 12px; }
.period-control button.chosen { color:#fff; background:#344b3d; }
.refresh-button { border:1px solid #c6cbbb; background:#fff; color:#394d3e; border-radius:5px; padding:10px 14px; }
.refresh-button:hover { background:#f4f4ed; }
button:disabled { opacity:.6; cursor:wait; }
.data-status { display:flex; gap:12px; align-items:center; margin:14px 0 22px; color:var(--dash-muted); font-size:11px; line-height:1.8; }
.data-status button { border:0; background:none; text-decoration:underline; color:#946347; cursor:pointer; padding:3px; }
.summary-strip { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); background:#f3f4ed; border-radius:10px; padding:23px 0; margin-bottom:22px; }
.summary-strip.operations-only { grid-template-columns:repeat(4,minmax(0,1fr)); }
.summary-strip > div { padding:0 24px; border-right:1px solid #dce0d3; min-width:0; }
.summary-strip > div:last-child { border:0; }
.summary-strip dt { font-size:12px; color:#52604f; }
.summary-strip dd { font-size:29px; font-weight:650; margin:9px 0; font-variant-numeric:tabular-nums; overflow-wrap:anywhere; line-height:1.25; }
.summary-strip dd small { font-size:12px; margin-left:6px; font-weight:400; }
.summary-strip dd.money-value { font-size:25px; }
.summary-strip > div > span { font-size:11px; color:#63705e; line-height:1.6; }
.chart-grid { display:grid; grid-template-columns:minmax(0,1.75fr) minmax(280px,1fr); gap:20px; }
.chart-panel { border:1px solid var(--dash-line); border-radius:10px; padding:22px 24px 18px; background:#fff; min-width:0; display:flex; flex-direction:column; }
.chart-panel > header { display:flex; justify-content:space-between; align-items:flex-start; gap:14px; }
.chart-panel h2 { font-size:16px; font-weight:650; line-height:1.5; }
.chart-panel header p { color:var(--dash-muted); font-size:11px; margin-top:5px; line-height:1.6; }
.detail-link { font-size:11px; color:#5d6e5e !important; text-decoration:none; white-space:nowrap; padding-top:3px; }
.detail-link:hover { text-decoration:underline; }
.source-note { margin-top:auto; padding-top:15px; font-size:11px; line-height:1.75; color:#737b6e; }
.chart-state { display:flex; flex:1; align-items:center; justify-content:center; min-height:220px; font-size:13px; color:#727c71; background:#fafbf8; margin-top:20px; border-radius:6px; padding:24px; text-align:center; }
.horizontal-chart { margin-top:20px; }
.horizontal-row { margin-bottom:17px; }
.bar-caption { display:flex; justify-content:space-between; align-items:center; margin-bottom:7px; font-size:12px; }
.bar-caption b { font-size:13px; font-variant-numeric:tabular-nums; }
.bar-caption small { font-weight:400; font-size:11px; }
.bar-track { height:8px; background:#f0f1eb; border-radius:2px; overflow:hidden; }
.bar-track > span { display:block; height:100%; border-radius:2px; }
.forecast-result { display:flex; gap:12px; align-items:baseline; flex-wrap:wrap; border-top:1px solid var(--dash-line); padding-top:12px; margin-top:12px; font-size:11px; color:var(--dash-muted); }
.forecast-result b { color:#328279; font-size:19px; font-variant-numeric:tabular-nums; }
.negative { color:#ac5c41 !important; }
.donut-layout { display:flex; justify-content:center; margin:16px 0 12px; }
.donut-layout svg { width:180px; height:180px; }
.donut-value { fill:#24362c; font-size:25px; font-weight:650; font-variant-numeric:tabular-nums; }
.donut-label { fill:#6c786d; font-size:12px; }
.donut-legend { display:flex; flex-direction:column; gap:13px; font-size:12px; }
.donut-legend > div { display:flex; align-items:center; justify-content:space-between; gap:14px; }
.donut-legend span { display:flex; align-items:center; gap:8px; }
.donut-legend i { display:inline-block; width:9px; height:9px; border-radius:2px; }
.donut-legend b { font-variant-numeric:tabular-nums; }
.donut-legend small { font-weight:400; font-size:11px; }
.risk-row { display:grid; grid-template-columns:minmax(130px,1fr) minmax(60px,1.1fr) 30px; align-items:center; gap:14px; padding:14px 0; text-decoration:none; border-bottom:1px solid #eef0e9; }
.risk-row:hover { background:#f8f9f4; }
.risk-caption { display:flex; gap:8px; flex-wrap:wrap; align-items:center; font-size:12px; }
.risk-caption small { font-size:10px; color:#8e6e37; }
.risk-caption .danger { color:#a8543b; }
.risk-row > b { text-align:right; font-variant-numeric:tabular-nums; font-size:16px; }
.rental-highlight { display:flex; align-items:baseline; gap:10px; margin:30px 0 24px; }
.rental-highlight strong { font-size:40px; line-height:1; font-weight:650; font-variant-numeric:tabular-nums; }
.rental-highlight span { color:var(--dash-muted); font-size:12px; }
.rental-stacked { height:13px; border-radius:3px; background:#f0f1eb; overflow:hidden; display:flex; margin-bottom:22px; }
.rental-stacked span { height:100%; display:block; }
.zero-note { font-size:11px; color:var(--dash-muted); margin-top:15px; }
@media(max-width:1250px) { .summary-strip > div { padding:0 14px; } .summary-strip dd { font-size:25px; } .summary-strip dd.money-value { font-size:21px; } .chart-grid { grid-template-columns:minmax(0,1.4fr) minmax(260px,1fr); gap:16px; } .chart-panel { padding:20px 18px 16px; } }
@media(max-width:1050px) { .chart-grid { grid-template-columns:minmax(0,1fr); } .summary-strip.operations-only { grid-template-columns:repeat(2,minmax(0,1fr)); } .summary-strip.operations-only > div:nth-child(odd) { border-right:1px solid #dce0d3; } .summary-strip.operations-only > div:nth-child(even) { border:0; } .customer-chart { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:8px 24px; } .summary-strip { grid-template-columns:repeat(3,minmax(0,1fr)); gap:24px 0; } .summary-strip > div:nth-child(3) { border:0; } .donut-layout { margin-top:20px; } }
@media(max-width:580px) { .operations-dashboard { margin-top:20px; } .dashboard-heading h1 { font-size:23px; } .dashboard-heading p { font-size:12px; } .summary-strip { grid-template-columns:repeat(2,minmax(0,1fr)); padding:20px 0; } .summary-strip > div:nth-child(3) { border-right:1px solid #dce0d3; } .summary-strip > div:nth-child(even) { border:0; } .summary-strip > div:last-child { grid-column:1/-1; } .summary-strip.operations-only > div:last-child { grid-column:auto; } .chart-panel { padding:18px 16px; } .customer-chart { grid-template-columns:repeat(2,minmax(0,1fr)); } .risk-row { grid-template-columns:minmax(110px,1fr) minmax(50px,1fr) 24px; gap:8px; } .data-status { align-items:flex-start; } }
</style>
