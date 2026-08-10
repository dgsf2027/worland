<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchCashflow, fetchCoverageGap, fetchReturnAttribution,
  runDistribution, reverseDistribution, fetchDistributions, fetchDistributionDetail,
  type Cashflow, type CoverageGapReport, type ReturnAttribution,
  type DistributionItem, type DistributionDetail,
} from '@/api/distribution'

function money(v?: number | null) {
  if (v === null || v === undefined) return '—'
  const a = Math.abs(v)
  return a >= 10000 ? (v / 10000).toFixed(2) + '万' : '¥' + v.toLocaleString()
}
function pct(v?: number | null) {
  if (v === null || v === undefined) return '—'
  return (v * 100).toFixed(2) + '%'
}
const roleTag: Record<string, string> = { GP: 'warning', LP: '' }

// ============ 现金流驾驶舱 ============
const cf = ref<Cashflow | null>(null)
const cfMonths = ref(12)
async function loadCashflow() { cf.value = await fetchCashflow(cfMonths.value) }
const maxFlow = computed(() => {
  if (!cf.value) return 1
  let m = 1
  for (const f of cf.value.forecast) m = Math.max(m, Math.abs(f.inflow), Math.abs(f.outflow))
  return m
})

// ============ 账期兑付缺口预警(P0-H) ============
const gap = ref<CoverageGapReport | null>(null)
const tMinus = ref(7)
async function loadGap() { gap.value = await fetchCoverageGap(tMinus.value) }

// ============ 回报四源 ============
const attr = ref<ReturnAttribution | null>(null)
const custType = ref('其他')
async function loadAttr() { attr.value = await fetchReturnAttribution({ customerType: custType.value }) }
const srcColor: Record<string, string> = {
  procurementSpread: '#409eff', timeValue: '#67c23a', valuePricing: '#e6a23c', residual: '#909399',
}

// ============ 结账分配 ============
const dists = ref<DistributionItem[]>([])
async function loadDists() { dists.value = await fetchDistributions({}) }
const runForm = reactive<{ period: string; profitBefore?: number; returnRate?: number; force: boolean }>(
  { period: '', profitBefore: undefined, returnRate: undefined, force: false })
async function doRun() {
  if (!/^\d{4}-\d{2}$/.test(runForm.period)) { ElMessage.warning('请填分配期 YYYY-MM'); return }
  const body: Record<string, any> = { period: runForm.period, force: runForm.force }
  if (runForm.profitBefore != null) body.profitBefore = runForm.profitBefore
  if (runForm.returnRate != null) body.returnRate = runForm.returnRate
  const d = await runDistribution(body)
  ElMessage.success(`分配 ${d.distribution.distributionNo} · 可分配 ${money(d.distribution.distributable)} · 现金 ${money(d.distribution.cash50)}`)
  detail.value = d; detailVisible.value = true
  loadDists(); loadCashflow(); loadGap()
}
async function doReverse(row: DistributionItem) {
  const { value } = await ElMessageBox.prompt(
    `冲销分配 ${row.distributionNo}(置 reversed + 负额镜像 · reverses_id 幂等键 · 期释放可重算)· 原因`,
    '分配冲销', { inputType: 'textarea', inputValue: '结账口径修正' })
  const impact = await reverseDistribution(row.id, { reason: value })
  await ElMessageBox.alert(impact.items.map((i) => '· ' + i).join('<br/>'),
    `冲销影响(${impact.reversalNo} · 期 ${impact.period})`, { dangerouslyUseHTMLString: true })
  loadDists(); loadCashflow(); loadGap()
}
const detailVisible = ref(false)
const detail = ref<DistributionDetail | null>(null)
async function openDetail(id: number) { detail.value = await fetchDistributionDetail(id); detailVisible.value = true }

onMounted(() => { loadCashflow(); loadGap(); loadAttr(); loadDists() })
</script>

<template>
  <div class="cf-page">
    <!-- ============ 账期兑付缺口预警(P0-H·置顶红点) ============ -->
    <el-card shadow="never" class="gap-bar" v-if="gap"
      :class="{ red: gap.hasRedAlert }">
      <div class="gap-head">
        <span class="gap-title">{{ gap.hasRedAlert ? '🔴' : '🟢' }} 账期兑付缺口预警(P0-H)</span>
        <el-tag :type="gap.hasRedAlert ? 'danger' : 'success'" size="small" effect="dark">
          {{ gap.hasRedAlert ? `${gap.redCount} 笔兑付缺口` : '兑付安全' }}
        </el-tag>
        <span class="gap-note">可动用留存 <b>{{ money(gap.usableReserve) }}</b>(累计留存−20万下限)</span>
        <el-input-number v-model="tMinus" :min="1" :max="90" size="small" style="width:120px" @change="loadGap" />
        <span class="gap-note">T-N 天</span>
      </div>
      <el-table :data="gap.items" size="small" border v-if="gap.items.length">
        <el-table-column label="预警" width="60"><template #default="{ row }">
          <span v-if="row.red">🔴</span><span v-else class="muted">🟢</span>
        </template></el-table-column>
        <el-table-column prop="stage" label="阶段" width="90" />
        <el-table-column prop="dueDate" label="到期日" width="110" />
        <el-table-column label="剩余天" width="80"><template #default="{ row }">{{ row.daysToDue }} 天</template></el-table-column>
        <el-table-column label="应付到期" width="110" align="right"><template #default="{ row }">{{ money(row.payableAmount) }}</template></el-table-column>
        <el-table-column label="累计回款" width="110" align="right"><template #default="{ row }">{{ money(row.cumInflow) }}</template></el-table-column>
        <el-table-column label="累计应付" width="110" align="right"><template #default="{ row }">{{ money(row.cumOutflow) }}</template></el-table-column>
        <el-table-column label="预计现金" width="120" align="right"><template #default="{ row }">
          <b :class="{ neg: row.projectedCash < 0 }">{{ money(row.projectedCash) }}</b>
        </template></el-table-column>
        <el-table-column label="缺口/裁决/补款" min-width="240"><template #default="{ row }">
          <div v-if="row.red" class="gap-red">
            <div>缺口 <b class="neg">{{ money(row.gap) }}</b> · 裁决人:{{ row.arbiter }}</div>
            <div class="fs">补款来源:{{ (row.fundingSources || []).join(' / ') }}</div>
          </div>
          <span v-else class="muted">充足</span>
        </template></el-table-column>
      </el-table>
      <el-empty v-else description="暂无待付应付" :image-size="48" />
    </el-card>

    <!-- ============ 现金流总览 ============ -->
    <div class="section-title">💧 现金流驾驶舱
      <el-input-number v-model="cfMonths" :min="3" :max="36" size="small" style="width:110px" @change="loadCashflow" />
      <span class="muted">未来 N 月</span>
    </div>
    <el-row :gutter="12" v-if="cf">
      <el-col :span="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-t">应收(未收·rent_schedule)</div>
          <div class="kpi-v">{{ money(cf.receivable.total) }}</div>
          <div class="kpi-sub">1年内 {{ money(cf.receivable.within1Year) }} · 1年以上 {{ money(cf.receivable.beyond1Year) }} · {{ cf.receivable.count }} 笔</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-t">应付(待付·payable)</div>
          <div class="kpi-v">{{ money(cf.payable.total) }}</div>
          <div class="kpi-sub">1年内 {{ money(cf.payable.within1Year) }} · 1年以上 {{ money(cf.payable.beyond1Year) }} · {{ cf.payable.count }} 笔</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-t">净头寸(应收−应付)</div>
          <div class="kpi-v" :class="{ neg: cf.netPosition < 0 }">{{ money(cf.netPosition) }}</div>
          <div class="kpi-sub">粗口径 · 精确以兑付缺口现金头寸为准</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="kpi lev">
          <div class="kpi-t">三层杠杆资金占用</div>
          <div class="lev-rows">
            <span>①自有 <b>{{ money(cf.leverage.ownCapital) }}</b></span>
            <span>②供应商账期 <b>{{ money(cf.leverage.supplierCredit) }}</b></span>
            <span>③融资 <b>{{ money(cf.leverage.financing) }}</b></span>
          </div>
          <div class="kpi-sub">合计 {{ money(cf.leverage.total) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 净现金流预测曲线 -->
    <el-card shadow="never" class="forecast" v-if="cf">
      <div class="sub-title">📈 净现金流预测曲线(流入绿 / 流出橙 / 累计线)</div>
      <div class="bars">
        <div v-for="f in cf.forecast" :key="f.period" class="bar-col">
          <div class="bar-wrap">
            <div class="bar in" :style="{ height: (f.inflow / maxFlow * 60) + 'px' }" :title="'流入 ' + money(f.inflow)"></div>
            <div class="bar out" :style="{ height: (f.outflow / maxFlow * 60) + 'px' }" :title="'流出 ' + money(f.outflow)"></div>
          </div>
          <div class="bar-cum" :class="{ neg: f.cumulative < 0 }">{{ money(f.cumulative) }}</div>
          <div class="bar-x">{{ f.period.slice(2) }}</div>
        </div>
      </div>
    </el-card>

    <!-- ============ 结账分配 ============ -->
    <div class="section-title">💰 结账分配(每月5号:净利→管理费阶梯→可分配→50现金/50滚存→留存校验)</div>
    <div class="run-bar">
      <el-input v-model="runForm.period" placeholder="分配期 YYYY-MM" size="small" style="width:150px" />
      <el-input-number v-model="runForm.profitBefore" :controls="false" placeholder="提取前净利(元·可空=经营账汇总)" size="small" style="width:220px" />
      <el-input-number v-model="runForm.returnRate" :controls="false" :precision="4" :step="0.01" placeholder="回报率(可空=净利/出资)" size="small" style="width:200px" />
      <el-checkbox v-model="runForm.force" size="small">force(冲销旧的重算)</el-checkbox>
      <el-button type="primary" size="small" @click="doRun">运行结账分配</el-button>
    </div>
    <el-table :data="dists" size="small" border>
      <el-table-column prop="distributionNo" label="分配单号" width="150" />
      <el-table-column prop="period" label="期" width="80" />
      <el-table-column label="提取前净利" width="110" align="right"><template #default="{ row }">{{ money(row.profitBefore) }}</template></el-table-column>
      <el-table-column label="回报率" width="80" align="right"><template #default="{ row }">{{ pct(row.returnRate) }}</template></el-table-column>
      <el-table-column label="管理费" width="130" align="right"><template #default="{ row }">{{ money(row.mgmtFee) }}<span class="muted">({{ pct(row.mgmtFeeRate) }})</span></template></el-table-column>
      <el-table-column label="可分配" width="100" align="right"><template #default="{ row }">{{ money(row.distributable) }}</template></el-table-column>
      <el-table-column label="现金/滚存" width="150" align="right"><template #default="{ row }">{{ money(row.cash50) }} / {{ money(row.roll50) }}</template></el-table-column>
      <el-table-column label="留存" width="130" align="right"><template #default="{ row }">
        {{ money(row.reserveAfter) }}
        <el-tag :type="row.reserveSufficient ? 'success' : 'danger'" size="small" effect="plain">{{ row.reserveSufficient ? '达标' : '不足' }}</el-tag>
      </template></el-table-column>
      <el-table-column label="状态" width="80"><template #default="{ row }">
        <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">{{ row.status === 'active' ? '生效' : '已冲销' }}</el-tag>
      </template></el-table-column>
      <el-table-column label="操作" width="120" fixed="right"><template #default="{ row }">
        <el-button link type="primary" size="small" @click="openDetail(row.id)">详情</el-button>
        <el-button v-if="row.status === 'active' && !row.isReversal" link type="warning" size="small" @click="doReverse(row)">冲销</el-button>
      </template></el-table-column>
    </el-table>
    <p class="tip">
      提取前净利(经营账收入−成本)→ 管理费阶梯(§13.1 按回报率区间:25%→10%)→ 可分配 → 50%现金(每月5号)/50%滚存 →
      留存 ≥ max(20万, 未来3月供应商净应付),不足则压减现金抬滚存。period 幂等:同期重跑需 force(先冲销旧单);阶梯/比例/留存下限全走 rule_config。
    </p>

    <!-- ============ 回报四源 ============ -->
    <div class="section-title">🧭 回报四源(总税后 IRR = 集采差价 + 资金时间价值 + 价值定价 + 残值回收)
      <el-select v-model="custType" size="small" style="width:130px" @change="loadAttr">
        <el-option label="其他客户(30%)" value="其他" />
        <el-option label="云山快仓(25%)" value="云山快仓" />
      </el-select>
    </div>
    <el-card shadow="never" v-if="attr">
      <div class="attr-head">
        总税后 IRR <b>{{ pct(attr.totalIrr) }}</b> ·
        四源之和 <b>{{ pct(attr.sumCheck) }}</b>
        <el-tag :type="attr.reconciled ? 'success' : 'danger'" size="small">{{ attr.reconciled ? '✓ 勾稽平' : '✗ 不平' }}</el-tag>
      </div>
      <div class="attr-bar">
        <div v-for="s in attr.sources" :key="s.key" class="attr-seg"
          :style="{ width: (s.irrContribution / attr.totalIrr * 100) + '%', background: srcColor[s.key] }"
          :title="s.label + ' ' + pct(s.irrContribution)">
          <span class="seg-l">{{ s.label }}</span>
          <span class="seg-v">{{ pct(s.irrContribution) }}</span>
        </div>
      </div>
      <div class="attr-list">
        <div v-for="s in attr.sources" :key="s.key" class="attr-row">
          <span class="dot" :style="{ background: srcColor[s.key] }"></span>
          <b>{{ s.label }}</b> {{ pct(s.irrContribution) }}(权重 {{ pct(s.weight) }})
          <span class="src">← {{ s.source }}</span>
        </div>
      </div>
      <p class="tip">四源=利润来源(可勾稽·四者之和=总IRR);区别于「三层杠杆」=资金结构(自有/供应商账期/融资)。权重走 rule_config 可按真实业务校准。</p>
    </el-card>

    <!-- ============ 分配详情(计算链路 + 每人份额) ============ -->
    <el-dialog v-model="detailVisible" title="结账分配详情" width="760px">
      <template v-if="detail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="分配单号">{{ detail.distribution.distributionNo }}</el-descriptions-item>
          <el-descriptions-item label="期">{{ detail.distribution.period }}</el-descriptions-item>
          <el-descriptions-item label="分配日">{{ detail.distribution.bizDate }}</el-descriptions-item>
          <el-descriptions-item label="提取前净利">{{ money(detail.distribution.profitBefore) }}</el-descriptions-item>
          <el-descriptions-item label="回报率">{{ pct(detail.distribution.returnRate) }}</el-descriptions-item>
          <el-descriptions-item label="管理费">{{ money(detail.distribution.mgmtFee) }}({{ pct(detail.distribution.mgmtFeeRate) }})</el-descriptions-item>
          <el-descriptions-item label="可分配">{{ money(detail.distribution.distributable) }}</el-descriptions-item>
          <el-descriptions-item label="现金/滚存">{{ money(detail.distribution.cash50) }} / {{ money(detail.distribution.roll50) }}</el-descriptions-item>
          <el-descriptions-item label="留存下限">{{ money(detail.distribution.reserveFloor) }}</el-descriptions-item>
        </el-descriptions>

        <div class="steps">
          <div class="sub-title">🧮 计算链路</div>
          <div v-for="(s, i) in detail.steps" :key="i" class="step">{{ s }}</div>
        </div>

        <div class="sub-title" style="margin-top:12px">👥 每人份额(按出资比例 · GP 另得管理费)</div>
        <el-table :data="detail.shares" size="small" border>
          <el-table-column prop="name" label="出资人" width="120"><template #default="{ row }">
            {{ row.name }}<el-tag v-if="row.self" type="primary" size="small" effect="plain" style="margin-left:4px">我</el-tag>
          </template></el-table-column>
          <el-table-column label="角色" width="70"><template #default="{ row }"><el-tag :type="roleTag[row.role]" size="small">{{ row.role }}</el-tag></template></el-table-column>
          <el-table-column label="出资额" width="100" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
          <el-table-column label="比例" width="70" align="right"><template #default="{ row }">{{ pct(row.ratio) }}</template></el-table-column>
          <el-table-column label="现金份额" width="100" align="right"><template #default="{ row }">{{ money(row.cashShare) }}</template></el-table-column>
          <el-table-column label="滚存份额" width="100" align="right"><template #default="{ row }">{{ money(row.rollShare) }}</template></el-table-column>
          <el-table-column label="管理费" width="90" align="right"><template #default="{ row }">{{ row.mgmtFee ? money(row.mgmtFee) : '—' }}</template></el-table-column>
          <el-table-column label="本期总收益" width="110" align="right"><template #default="{ row }"><b>{{ money(row.totalGain) }}</b></template></el-table-column>
        </el-table>
        <p class="tip">P0-E 角色可见:LP 仅见自己那份(user_id 匹配);老板/财务见全量。每人份额 = 分配快照 × 出资比例即时算(单一真相源·防漂移)。</p>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cf-page { padding: 4px; }
.section-title { font-weight: 700; font-size: 15px; margin: 16px 0 10px; display: flex; align-items: center; gap: 10px; }
.sub-title { font-weight: 600; font-size: 13px; margin-bottom: 8px; }
.muted { color: #bbb; font-size: 12px; }
.neg { color: #f56c6c; }
/* 兑付缺口 */
.gap-bar { margin-bottom: 12px; }
.gap-bar.red { border-color: #f56c6c; }
.gap-bar :deep(.el-card__body) { padding: 12px 16px; }
.gap-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
.gap-title { font-weight: 700; font-size: 15px; }
.gap-note { font-size: 12px; color: #999; }
.gap-note b { color: #303133; }
.gap-red { font-size: 12px; line-height: 1.5; }
.gap-red .fs { color: #999; }
/* KPI */
.kpi :deep(.el-card__body) { padding: 12px 14px; }
.kpi-t { font-size: 12px; color: #999; }
.kpi-v { font-size: 22px; font-weight: 700; margin: 6px 0; }
.kpi-sub { font-size: 11px; color: #aaa; }
.lev-rows { display: flex; flex-direction: column; gap: 2px; font-size: 13px; margin: 4px 0; }
.lev-rows b { color: #303133; }
/* forecast */
.forecast { margin-top: 12px; }
.forecast :deep(.el-card__body) { padding: 12px 16px; }
.bars { display: flex; gap: 6px; align-items: flex-end; overflow-x: auto; padding-top: 10px; }
.bar-col { display: flex; flex-direction: column; align-items: center; min-width: 40px; }
.bar-wrap { display: flex; gap: 2px; align-items: flex-end; height: 64px; }
.bar { width: 10px; border-radius: 2px 2px 0 0; }
.bar.in { background: #67c23a; }
.bar.out { background: #e6a23c; }
.bar-cum { font-size: 10px; margin-top: 4px; color: #606266; }
.bar-x { font-size: 10px; color: #999; }
/* run */
.run-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.tip { font-size: 12px; color: #999; margin-top: 8px; line-height: 1.6; }
/* attr */
.attr-head { font-size: 13px; margin-bottom: 10px; display: flex; align-items: center; gap: 8px; }
.attr-head b { color: #303133; font-size: 15px; }
.attr-bar { display: flex; height: 34px; border-radius: 4px; overflow: hidden; }
.attr-seg { display: flex; flex-direction: column; justify-content: center; align-items: center; color: #fff; font-size: 11px; min-width: 40px; }
.seg-v { font-weight: 700; }
.attr-list { margin-top: 10px; }
.attr-row { font-size: 13px; padding: 3px 0; display: flex; align-items: center; gap: 6px; }
.attr-row .dot { width: 10px; height: 10px; border-radius: 2px; display: inline-block; }
.attr-row .src { color: #aaa; font-size: 11px; }
/* steps */
.steps { margin-top: 12px; background: #f7f9fc; border-radius: 6px; padding: 10px 14px; }
.step { font-size: 13px; line-height: 1.9; color: #555; }
</style>
