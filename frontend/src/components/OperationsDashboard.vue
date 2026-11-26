<script setup lang="ts">
/**
 * 工作台 · 经营驾驶舱（四层老板决策视图）。
 * 层级设计：
 * 1) 顶部精简标题栏（范围 / 时间切换 / 数据体检 / 刷新）
 * 2) 第一层：4 张老板核心真账卡片（采购投入 / 实收租金 / 实际净现金流 / 预计全期真实毛利；LP 仅见安全回报）
 * 3) 第二层：真实业务风险提示条（直查真实逾期单/待开销项票/30天应付/质保到期，0 项自动收起为绿灯）
 * 4) 第三层：项目经营全景表（slot，一行一项目，点击打开利润抽屉）
 * 5) 第四层：资金水位线预测（录入/保存银行余额，预判见底月份与缺口） + 收付/收租双图
 * 6) 第五层：次级运营标签页（客户跟进漏斗 / 设备与库房概况 / 我的待办），不抢首屏空间
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import MonthlyColumnChart, { type ColumnRow, type ColumnSeries } from './MonthlyColumnChart.vue'
import type { Workbench, CashWaterline } from '@/api/workbench'
import type { DashboardData, Resource } from '@/api/dashboard'
import type { ProjectPanoramaOverview } from '@/api/contract'
import { compactYuan, isNumber, yuan } from '@/utils/dashboardCharts'

const router = useRouter()

const props = defineProps<{
  workbench: Workbench | null
  panoramas: ProjectPanoramaOverview | null
  waterline: CashWaterline | null
  charts: DashboardData
  loading: boolean
  chartsLoading: boolean
  waterlineLoading?: boolean
  failed: boolean
  updatedAt: string
  months: number
  canManageFinance?: boolean
}>()
const emit = defineEmits<{
  (e: 'change-months', value: number): void
  (e: 'refresh'): void
  (e: 'open-panorama', contractId: number): void
  (e: 'simulate-balance', balance: number | null): void
  (e: 'save-balance', balance: number): void
  (e: 'open-health'): void
}>()

const COLORS = { in: '#138a78', inPlan: '#7cc4b5', out: '#c08a2e' }
const kpi = computed(() => props.workbench?.kpi)
const count = (value: unknown) => (isNumber(value) ? value.toLocaleString('zh-CN') : '—')
const amount = (value: unknown) => (isNumber(value) && Math.abs(value) >= 1e5 ? compactYuan(value) : yuan(value))
const fullMoney = (value: unknown) => (isNumber(value) ? yuan(value, 2) : '—')

// ---------- 第一层：4 张老板核心真账卡 ----------
type Tone = 'critical' | 'warning' | 'good'
interface CoreCard {
  key: string
  label: string
  value: string
  title?: string
  sub: string
  tone?: Tone
}
const costVisible = computed(() => Boolean(props.panoramas?.costVisible))

const coreCards = computed<CoreCard[]>(() => {
  const p = props.panoramas
  const k = kpi.value
  if (p && p.costVisible) {
    const net = p.totalActualNetCashflow ?? 0
    const pendingRent = Math.max(0, (p.totalPlannedCustomer ?? 0) - (p.totalReceivedRent ?? 0))
    const rateText = isNumber(k?.weightedReturn) ? ` · 加权年化 ${(k!.weightedReturn! * 100).toFixed(1)}%` : ''
    return [
      {
        key: 'purchase',
        label: '累计采购投入',
        value: amount(p.totalPurchase ?? 0),
        title: fullMoney(p.totalPurchase),
        sub: `已付 ${amount(p.totalPurchasePaid ?? 0)} · 待付 ${amount(p.totalPurchaseUnpaid ?? 0)}`,
      },
      {
        key: 'received',
        label: '累计实收租金',
        value: amount(p.totalReceivedRent ?? 0),
        title: fullMoney(p.totalReceivedRent),
        sub: `剩余应收 ${amount(pendingRent)} · 总应收 ${amount(p.totalPlannedCustomer ?? 0)}`,
        tone: (p.totalReceivedRent ?? 0) > 0 ? 'good' : undefined,
      },
      {
        key: 'netcash',
        label: '当前实际净现金流',
        value: amount(net),
        title: fullMoney(net),
        sub: '实收租金 + 在手押金 − 已付货款（实时现金账）',
        tone: net >= 0 ? 'good' : 'warning',
      },
      {
        key: 'profit',
        label: '预计全期真实毛利',
        value: amount(p.totalExpectedGrossProfit ?? 0),
        title: fullMoney(p.totalExpectedGrossProfit),
        sub: `估税后净利 ${amount(p.totalEstimatedAfterTaxProfit ?? 0)}${rateText}`,
        tone: (p.totalExpectedGrossProfit ?? 0) > 0 ? 'good' : undefined,
      },
    ]
  }
  // LP / 受限视角：仅展示非成本敏感指标
  const out: CoreCard[] = []
  if (isNumber(k?.weightedReturn)) {
    out.push({
      key: 'irr',
      label: '项目加权年化回报',
      value: `${(k!.weightedReturn! * 100).toFixed(1)}%`,
      sub: '按当前生效租赁合同本金加权',
      tone: 'good',
    })
  }
  if (p) {
    out.push({
      key: 'received-lp',
      label: '累计实收租金',
      value: amount(p.totalReceivedRent ?? 0),
      title: fullMoney(p.totalReceivedRent),
      sub: `合同总应收 ${amount(p.totalPlannedCustomer ?? 0)}（共 ${p.projectCount} 个项目）`,
    })
  }
  if (isNumber(k?.monthDistribution)) {
    out.push({
      key: 'dist',
      label: '本月可分配',
      value: amount(k!.monthDistribution),
      title: fullMoney(k!.monthDistribution),
      sub: k?.period ? `${k.period} 分配方案` : '当月分配方案',
    })
  }
  return out
})

// ---------- 第二层：真实业务风险条 ----------
const toneOf = (level: string): Tone => (level === '红灯' || level === '超限' ? 'critical' : level === '正常' ? 'good' : 'warning')
const redPoints = computed(() => props.workbench?.redPoints || [])

// ---------- 第四层：资金水位线交互 ----------
const balanceInput = ref<number | undefined>(undefined)
watch(
  () => props.waterline?.openingCashBalance,
  (v) => {
    if (v != null && balanceInput.value === undefined) {
      balanceInput.value = v
    }
  },
  { immediate: true },
)
function onSimulateBalance() {
  emit('simulate-balance', balanceInput.value ?? 0)
}
function onSaveBalance() {
  emit('save-balance', balanceInput.value ?? 0)
}

// ---------- 图表 ----------
const cash = computed(() => (props.charts.cashflow.status === 'ready' ? props.charts.cashflow.data : null))
const forecastIn = computed(() => cash.value?.forecast.reduce((s, p) => s + p.inflow, 0))
const forecastOut = computed(() => cash.value?.forecast.reduce((s, p) => s + p.outflow, 0))
const forecastNet = computed(() => cash.value?.forecast.reduce((s, p) => s + p.net, 0))
const inventory = computed(() => (props.charts.inventory.status === 'ready' ? props.charts.inventory.data : null))
const assetValid = computed(() => {
  const r = kpi.value?.rentedCount
  const a = kpi.value?.activeAssetCount
  return isNumber(r) && isNumber(a) && r >= 0 && a >= r
})
const rentedShare = computed(() => (assetValid.value && kpi.value!.activeAssetCount! > 0 ? kpi.value!.rentedCount! / kpi.value!.activeAssetCount! : 0))

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

// ---------- 第五层：次级运营标签页 ----------
const secondaryTab = ref<'customers' | 'assets' | 'tasks'>('customers')
const stages = computed(() => (props.charts.customerStages.status === 'ready' ? props.charts.customerStages.data || [] : []))
const stageTotal = computed(() => stages.value.reduce((s, x) => s + x.count, 0))
const stageMax = computed(() => Math.max(1, ...stages.value.map(s => s.count)))

const tasks = computed(() => props.workbench?.myTasks || [])
const prioTone: Record<string, Tone | 'low'> = { 高: 'critical', 中: 'warning', 低: 'low' }
const shortDate = (d?: string) => (d && /^\d{4}-\d{2}-\d{2}/.test(d) ? `${Number(d.slice(5, 7))}月${Number(d.slice(8, 10))}日` : '')

const statusLine = computed(() => {
  if (props.loading) return '正在更新…'
  if (props.failed) return '工作台数据加载失败'
  return props.updatedAt ? `更新于 ${props.updatedAt}` : '等待业务数据'
})
function goLink(link: string) {
  if (link) router.push(link)
}
</script>

<template>
  <section class="ov" :aria-busy="loading || chartsLoading" aria-labelledby="ov-title">
    <!-- 顶部精简标题栏 -->
    <header class="ov-head">
      <div class="ov-title">
        <h1 id="ov-title">经营驾驶舱</h1>
        <p>
          <span>{{ workbench?.scopeNote || '数据按账号权限实时汇总' }}</span>
          <span class="dot-sep" aria-hidden="true">·</span>
          <span role="status">{{ statusLine }}</span>
          <button v-if="failed" type="button" class="text-btn" @click="emit('refresh')">重试</button>
        </p>
      </div>
      <div class="ov-ctrls">
        <button v-if="canManageFinance" type="button" class="health-btn" @click="emit('open-health')">
          🩺 数据体检
        </button>
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

    <!-- 第一层：老板核心 4 账卡 -->
    <dl v-if="coreCards.length" class="kpis" :class="{ stale: loading }" :style="{ '--n': coreCards.length }">
      <div v-for="c in coreCards" :key="c.key" class="kpi">
        <dt>{{ c.label }}</dt>
        <dd :title="c.title" :class="c.tone">{{ c.value }}</dd>
        <p>{{ c.sub }}</p>
      </div>
    </dl>
    <div v-else-if="loading" class="kpis placeholder" :style="{ '--n': 4 }" aria-hidden="true">
      <div v-for="n in 4" :key="n" class="kpi"></div>
    </div>

    <!-- 第二层：真实待处理风险条（全部来自实时单据，0 项自动收起为绿灯条） -->
    <nav class="risk-strip" aria-label="实时风险提醒">
      <template v-if="redPoints.length">
        <button
          v-for="r in redPoints"
          :key="r.key"
          type="button"
          class="risk-pill"
          :class="toneOf(r.level)"
          @click="goLink(r.link)"
        >
          <span class="rp-dot"></span>
          <span class="rp-label">{{ r.label }}</span>
          <b class="rp-cnt">{{ count(r.count) }}</b>
          <span v-if="r.subtitle" class="rp-sub">{{ r.subtitle }}</span>
        </button>
      </template>
      <div v-else-if="workbench" class="risk-ok">
        ✓ 当前无逾期收租单、超期未开销项票或近期应付缺口，经营状态健康
      </div>
      <RouterLink v-if="(workbench?.myTaskCount ?? 0) > 0" to="/task" class="risk-pill todo">
        <span class="rp-label">我的待办</span>
        <b class="rp-cnt">{{ count(workbench?.myTaskCount) }}</b>
      </RouterLink>
    </nav>

    <!-- 第三层：项目经营全景表（黄金视觉区） -->
    <article v-if="panoramas && panoramas.items.length" class="card pano-card">
      <header class="card-head">
        <div>
          <h2>项目真实利润全景（共 {{ panoramas.projectCount }} 个在管项目）</h2>
          <p>一笔一账：上游采购投入 ↔ 下游租金回款 ↔ 税后净利 ↔ 回本进度，点击任意项目查看逐月现金流轨迹</p>
        </div>
        <div class="tools">
          <RouterLink to="/contract" class="text-btn">合同管理 →</RouterLink>
        </div>
      </header>
      <el-table
        :data="panoramas.items"
        size="small"
        border
        stripe
        style="margin-top: 12px; cursor: pointer"
        @row-click="(r: any) => emit('open-panorama', r.contractId)"
      >
        <el-table-column label="项目（租赁合同 / 客户）" min-width="210" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="proj-no">{{ row.contractNo }}</div>
            <div class="proj-cust">{{ row.customerName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column v-if="costVisible" label="采购投入（已付 / 未付）" align="right" width="165">
          <template #default="{ row }">
            <div class="num-bold">{{ fullMoney(row.purchaseTotal) }}</div>
            <div class="proj-cust">已付 {{ amount(row.purchasePaid) }} · 未付 {{ amount(row.purchaseUnpaid) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="实收 / 合同总应收" align="right" width="160">
          <template #default="{ row }">
            <div class="num-bold good-txt">{{ fullMoney(row.receivedRent) }}</div>
            <div class="proj-cust">/ {{ amount(row.plannedCustomerTotal) }}</div>
          </template>
        </el-table-column>
        <el-table-column v-if="costVisible" label="当前净现金流" align="right" width="130">
          <template #default="{ row }">
            <b :class="(row.actualNetCashflow ?? 0) >= 0 ? 'good-txt' : 'warn-txt'">
              {{ fullMoney(row.actualNetCashflow) }}
            </b>
          </template>
        </el-table-column>
        <el-table-column v-if="costVisible" label="预计毛利 / 税后净利" align="right" width="160">
          <template #default="{ row }">
            <div class="num-bold good-txt">{{ fullMoney(row.expectedGrossProfit) }}</div>
            <div class="proj-cust">估税后 {{ amount(row.estimatedAfterTaxProfit) }}</div>
          </template>
        </el-table-column>
        <el-table-column v-if="costVisible" label="回本进度" width="145">
          <template #default="{ row }">
            <el-progress :percentage="Math.round((row.paybackProgress || 0) * 100)" :stroke-width="9" />
            <div class="proj-cust">预计第 {{ row.paybackPeriodNo || '—' }} 期回本</div>
          </template>
        </el-table-column>
        <el-table-column v-if="costVisible" label="最大垫资峰值" align="right" width="125">
          <template #default="{ row }">{{ fullMoney(row.peakCapitalOutlay) }}</template>
        </el-table-column>
        <el-table-column label="" width="82" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="emit('open-panorama', row.contractId)">全景 →</el-button>
          </template>
        </el-table-column>
      </el-table>
    </article>

    <!-- 第四层 A：资金水位线与缺口预警（Phase 3 核心） -->
    <article v-if="waterline && waterline.costVisible" class="card waterline-card" :class="{ stale: waterlineLoading }">
      <header class="card-head wl-head">
        <div>
          <h2>资金水位线与缺口预判（未来 {{ waterline.months }} 个月）</h2>
          <p>以当前银行可用余额为起点，叠加未来每月租金流入与采购合同/采购单应付流出，提前标出资金见底月份</p>
        </div>
        <div class="wl-ctrls">
          <span class="wl-lbl">银行可用余额(元)</span>
          <el-input-number
            v-model="balanceInput"
            size="small"
            :min="0"
            :precision="2"
            :step="10000"
            :controls="false"
            style="width: 135px"
            placeholder="输入可用现金"
          />
          <el-button size="small" @click="onSimulateBalance">试算</el-button>
          <el-button v-if="canManageFinance" size="small" type="primary" @click="onSaveBalance">保存余额</el-button>
        </div>
      </header>

      <div class="wl-summary">
        <el-alert
          v-if="(waterline.maxShortfall ?? 0) > 0"
          type="error"
          :closable="false"
          show-icon
          :title="`资金缺口预警：预计在 ${waterline.firstShortfallMonth} 余额见底，最大缺口 ${fullMoney(waterline.maxShortfall)}（最低水位 ${fullMoney(waterline.minBalance)} @ ${waterline.minBalanceMonth}），请提前筹措流动资金或协商账期。`"
        />
        <el-alert
          v-else
          type="success"
          :closable="false"
          show-icon
          :title="`未来 ${waterline.months} 个月资金水位安全：最低月末余额 ${fullMoney(waterline.minBalance)}（${waterline.minBalanceMonth || '—'}），当前银行余额可覆盖全部计划应付。`"
        />
        <div class="wl-pills">
          <span>近 30 天到期应付：<b>{{ waterline.duePayable30dCount }} 笔 / {{ fullMoney(waterline.duePayable30dAmount) }}</b></span>
          <span v-if="(waterline.overduePayable ?? 0) > 0" class="danger-txt">已逾期未付：<b>{{ fullMoney(waterline.overduePayable) }}</b>（已从首月扣减）</span>
          <span v-if="(waterline.overdueReceivable ?? 0) > 0" class="warn-txt">历史逾期未收租金：<b>{{ fullMoney(waterline.overdueReceivable) }}</b>（追回后可增厚水位）</span>
          <span>{{ waterline.months }} 个月净流：<b :class="(waterline.totalNetFlow ?? 0) >= 0 ? 'good-txt' : 'danger-txt'">{{ fullMoney(waterline.totalNetFlow) }}</b></span>
        </div>
      </div>

      <el-table :data="waterline.points" size="small" border style="margin-top: 10px">
        <el-table-column prop="month" label="月份" width="95" />
        <el-table-column label="计划租金流入" align="right" width="135">
          <template #default="{ row }"><span class="good-txt">{{ fullMoney(row.plannedInflow) }}</span></template>
        </el-table-column>
        <el-table-column label="计划采购应付" align="right" width="135">
          <template #default="{ row }"><span :class="{ 'warn-txt': row.plannedOutflow > 0 }">{{ fullMoney(row.plannedOutflow) }}</span></template>
        </el-table-column>
        <el-table-column label="当月净现金流" align="right" width="135">
          <template #default="{ row }">
            <span :class="row.netFlow >= 0 ? 'good-txt' : 'danger-txt'">{{ fullMoney(row.netFlow) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="月末预计可用余额" align="right" min-width="160">
          <template #default="{ row }">
            <b :class="row.endingBalance < 0 ? 'danger-txt' : 'good-txt'">{{ fullMoney(row.endingBalance) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="水位状态" width="150" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.shortfall > 0" type="danger" size="small">缺口 {{ amount(row.shortfall) }}</el-tag>
            <el-tag v-else type="success" size="small" effect="plain">资金安全</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </article>

    <!-- 第四层 B：收付计划 & 收租走势双图 -->
    <div v-if="showRent || showCash" class="charts" :class="{ single: !(showRent && showCash) }">
      <article v-if="showCash" class="card" :class="{ stale: chartsLoading && cashRows.length }">
        <header class="card-head">
          <div>
            <h2>未来收付计划</h2>
            <p>本月起 {{ months }} 个月<template v-if="cash?.asOf"> · 截至 {{ cash.asOf }}</template></p>
          </div>
          <div class="tools">
            <el-tooltip :content="charts.cashflow.source || '依据待收与待付的到期计划。'" placement="top" :show-after="150" popper-class="ov-tip">
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

      <article v-if="showRent" class="card" :class="{ stale: chartsLoading && rentRows.length }">
        <header class="card-head">
          <div>
            <h2>历史收租走势</h2>
            <p>近 {{ months }} 个月 · 按月汇总正常收租原单</p>
          </div>
          <div class="tools">
            <el-tooltip :content="charts.rentTrend.source || '按真实收租单归集。'" placement="top" :show-after="150" popper-class="ov-tip">
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
    </div>

    <!-- 第五层：次级运营信息（标签页折叠，不干扰老板首屏决策） -->
    <article class="card secondary-card">
      <el-tabs v-model="secondaryTab" class="sec-tabs">
        <el-tab-pane v-if="showCustomers" :label="`客户跟进阶段 (${stageTotal})`" name="customers">
          <div class="sec-pane-head">
            <span class="proj-cust">客户 CRM 当前存量阶段分布</span>
            <RouterLink to="/customer" class="text-btn">进客户管理 →</RouterLink>
          </div>
          <ul v-if="stages.length" class="stages" aria-label="各阶段客户数">
            <li v-for="s in stages" :key="s.phase" :class="{ lost: s.phase === '流失' }">
              <span class="st-name">{{ s.phase }}</span>
              <span class="st-track"><span :style="{ width: s.count > 0 ? `max(${s.count / stageMax * 100}%, 3px)` : '0' }"></span></span>
              <b>{{ count(s.count) }}</b>
            </li>
          </ul>
          <div v-else class="state small">{{ stateText(charts.customerStages) }}</div>
        </el-tab-pane>

        <el-tab-pane v-if="assetValid || inventory" label="设备在租与库房短租" name="assets">
          <div class="asset-grid">
            <div v-if="assetValid" class="asset-box">
              <div class="ab-title">租赁设备在租率（长约设备）</div>
              <div class="ab-val">{{ (rentedShare * 100).toFixed(1) }}%</div>
              <div class="meter"><span :style="{ width: `${Math.min(100, rentedShare * 100)}%` }"></span></div>
              <div class="ab-sub">
                <span>{{ count(kpi?.rentedCount) }} / {{ count(kpi?.activeAssetCount) }} 台有效设备在租</span>
                <RouterLink to="/asset" class="text-btn">设备台账 →</RouterLink>
              </div>
            </div>
            <div v-if="inventory" class="asset-box">
              <div class="ab-title">库房短租出租单（独立于融资租赁）</div>
              <div class="ab-val">{{ count(inventory.activeRentalCount) }} 单</div>
              <div class="ab-sub">
                <span :class="{ 'warn-txt': inventory.overdueRentalCount > 0 }">逾期未归还 {{ count(inventory.overdueRentalCount) }} 单 · 档案 {{ count(inventory.itemCount) }} 项</span>
                <RouterLink to="/inventory" class="text-btn">库房物资 →</RouterLink>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="`我的待办 (${workbench?.myTaskCount ?? 0})`" name="tasks">
          <div class="sec-pane-head">
            <span class="proj-cust">当前账号待处理任务</span>
            <RouterLink to="/task" class="text-btn">进任务中心 →</RouterLink>
          </div>
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
          <div v-else class="state small">暂无待办任务</div>
        </el-tab-pane>
      </el-tabs>
    </article>
  </section>
</template>

<style scoped>
.ov {
  --viz-surface: #ffffff;
  --viz-ink: #1d2129;
  --viz-secondary: #5b616e;
  --viz-muted: #8b919c;
  --line: #e7e9ed;
  --critical: #c8423a; --critical-text: #b03a32; --critical-bg: #fdf3f2;
  --warning: #d4910f; --warning-text: #8f5d00; --warning-bg: #fdf8ee;
  --good: #2f8f5b; --good-text: #23744a; --good-bg: #f1f8f4;
  color: var(--viz-ink);
  container-type: inline-size;
}
.ov * { box-sizing: border-box; }
:where(.ov) :where(h1, h2, p, dl, dd, ul) { margin: 0; padding: 0; }
.ov a { color: inherit; text-decoration: none; }

/* 标题栏 */
.ov-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; flex-wrap: wrap; margin-bottom: 12px; }
.ov-title h1 { font-size: 19px; font-weight: 600; line-height: 1.3; }
.ov-title p { margin-top: 3px; font-size: 12px; color: var(--viz-secondary); display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.dot-sep { color: var(--viz-muted); }
.ov-ctrls { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.health-btn { font: inherit; font-size: 12.5px; color: #374151; background: #fff; border: 1px solid var(--line); border-radius: 8px; padding: 6px 11px; cursor: pointer; }
.health-btn:hover { background: #f9fafb; border-color: #cbd5e1; }
.seg { display: inline-flex; padding: 3px; border-radius: 8px; background: #f1f2f4; }
.seg button { border: 0; background: transparent; font: inherit; font-size: 12.5px; color: var(--viz-secondary); padding: 5px 11px; border-radius: 6px; cursor: pointer; }
.seg button.on { background: #fff; color: var(--viz-ink); font-weight: 600; box-shadow: 0 1px 2px rgba(15, 18, 24, .08); }
.refresh { display: inline-flex; align-items: center; gap: 5px; font: inherit; font-size: 12.5px; color: var(--viz-ink); background: #fff; border: 1px solid var(--line); border-radius: 8px; padding: 6px 11px; cursor: pointer; }
.refresh:hover { border-color: #cfd3da; background: #fafafb; }
.spin { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.text-btn { font: inherit; font-size: 12.5px; color: #2e6da4; background: none; border: 0; padding: 4px 6px; border-radius: 6px; cursor: pointer; }
.text-btn:hover { background: #f0f6fc; }
.icon-btn { display: inline-grid; place-items: center; width: 26px; height: 26px; color: var(--viz-muted); background: none; border: 0; border-radius: 6px; cursor: help; }

/* 第一层：4 张老板核心真账卡 */
.kpis { display: grid; grid-template-columns: repeat(var(--n), minmax(0, 1fr)); gap: 12px; margin-bottom: 12px; }
.kpi { background: #fff; border: 1px solid var(--line); border-radius: 11px; padding: 14px 16px; min-width: 0; display: flex; flex-direction: column; }
.kpi dt { font-size: 12.5px; color: var(--viz-secondary); }
.kpi dd { margin-top: 6px; font-size: 25px; font-weight: 700; line-height: 1.15; letter-spacing: -.01em; }
.kpi dd.good { color: var(--good-text); }
.kpi dd.warning { color: var(--warning-text); }
.kpi dd.critical { color: var(--critical-text); }
.kpi p { margin-top: 8px; font-size: 12px; color: var(--viz-muted); line-height: 1.45; }
.placeholder .kpi { min-height: 98px; background: #f6f7f8; border-color: transparent; }

/* 第二层：实时风险条 */
.risk-strip { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
.risk-pill { display: inline-flex; align-items: center; gap: 7px; padding: 7px 12px; border-radius: 9px; border: 1px solid var(--line); background: #fff; font: inherit; font-size: 12.5px; cursor: pointer; transition: transform .1s, background .15s; }
.risk-pill:hover { transform: translateY(-1px); }
.risk-pill.critical { background: var(--critical-bg); border-color: #f3c4c0; color: var(--critical-text); }
.risk-pill.warning { background: var(--warning-bg); border-color: #f2d9a3; color: var(--warning-text); }
.risk-pill.todo { background: #f5f7fa; color: var(--viz-ink); margin-left: auto; }
.rp-dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.rp-cnt { font-size: 15px; font-weight: 700; }
.rp-sub { font-size: 11.5px; opacity: 0.85; }
.risk-ok { flex: 1; padding: 8px 14px; border-radius: 9px; background: var(--good-bg); border: 1px solid #cce8d7; color: var(--good-text); font-size: 12.5px; font-weight: 500; }

/* 卡片通用 */
.card { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 15px 18px; min-width: 0; margin-bottom: 14px; }
.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.card-head h2 { font-size: 15px; font-weight: 600; line-height: 1.4; }
.card-head p { margin-top: 2px; font-size: 12px; color: var(--viz-muted); }
.tools { display: flex; align-items: center; gap: 4px; }
.stale { opacity: .6; }

/* 项目表 */
.proj-no { font-weight: 600; color: var(--viz-ink); }
.proj-cust { font-size: 11.5px; color: var(--viz-muted); }
.num-bold { font-weight: 600; }
.good-txt { color: var(--good-text); }
.warn-txt { color: var(--warning-text); }
.danger-txt { color: var(--critical-text); }

/* 资金水位线 */
.wl-ctrls { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.wl-lbl { font-size: 12.5px; color: var(--viz-secondary); }
.wl-summary { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.wl-pills { display: flex; gap: 18px; flex-wrap: wrap; font-size: 12.5px; color: var(--viz-secondary); padding: 2px 2px 0; }

/* 双图 */
.charts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-bottom: 14px; }
.charts .card { margin-bottom: 0; }
.charts.single { grid-template-columns: minmax(0, 1fr); }
.legend { display: flex; flex-wrap: wrap; gap: 6px 18px; margin: 10px 0 6px; font-size: 12.5px; color: var(--viz-secondary); }
.legend > span { display: inline-flex; align-items: center; gap: 6px; }
.legend b { color: var(--viz-ink); font-weight: 600; font-size: 13.5px; }
.sw { width: 10px; height: 10px; border-radius: 3px; display: inline-block; }
.sw.dot { border-radius: 50%; background: var(--viz-ink); width: 9px; height: 9px; }
.state { min-height: 180px; display: flex; align-items: center; justify-content: center; gap: 8px; margin-top: 10px; border-radius: 8px; background: #f8f9fa; color: var(--viz-muted); font-size: 13px; }
.state.small { min-height: 90px; }

/* 次级运营标签页 */
.secondary-card { padding-top: 8px; }
.sec-pane-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.stages { list-style: none; display: flex; flex-direction: column; gap: 4px; }
.stages li { display: grid; grid-template-columns: 44px minmax(0, 1fr) 36px; align-items: center; gap: 12px; height: 30px; font-size: 13px; }
.stages li.lost { margin-top: 6px; padding-top: 8px; border-top: 1px dashed #e3e5e9; }
.st-name { color: var(--viz-secondary); }
.st-track { height: 9px; border-radius: 0 4px 4px 0; position: relative; }
.st-track::before { content: ''; position: absolute; inset: 4px 0 auto 0; height: 2px; background: #f1f2f4; }
.st-track span { position: relative; display: block; height: 100%; border-radius: 0 4px 4px 0; background: #5b6472; }
.lost .st-track span { background: #c3c8d0; }
.stages b { text-align: right; font-weight: 600; }

.asset-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.asset-box { border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; background: #fafbfc; }
.ab-title { font-size: 12.5px; color: var(--viz-secondary); }
.ab-val { font-size: 22px; font-weight: 700; margin: 6px 0; }
.ab-sub { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--viz-muted); margin-top: 6px; }
.meter { height: 5px; border-radius: 3px; background: #e3f1ee; overflow: hidden; }
.meter span { display: block; height: 100%; background: #138a78; }

.tasks { list-style: none; }
.tasks li { display: grid; grid-template-columns: 26px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 9px 0; border-bottom: 1px solid #f0f1f3; }
.tasks li:last-child { border-bottom: 0; }
.prio { display: inline-grid; place-items: center; width: 24px; height: 24px; border-radius: 6px; font-size: 12px; font-weight: 600; }
.prio.critical { color: var(--critical-text); background: var(--critical-bg); }
.prio.warning { color: var(--warning-text); background: var(--warning-bg); }
.prio.low { color: var(--viz-secondary); background: #f3f4f6; }
.t-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.t-title { font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.t-meta { font-size: 12px; color: var(--viz-muted); }
.t-meta .overdue { color: var(--critical-text); }
.t-status { font-size: 12px; color: var(--viz-secondary); background: #f3f4f6; padding: 3px 8px; border-radius: 6px; }

@container (max-width: 960px) {
  .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 1100px) {
  .charts { grid-template-columns: minmax(0, 1fr); }
  .asset-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>

<style>
.ov-tip.el-popper { max-width: 340px; font-size: 12px; line-height: 1.7; }
</style>
