<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { calculateLease, validateLeaseInput, INTEREST_MODES, PAYMENT_FREQUENCIES, type LeaseInput } from '@/utils/leaseCalculator'

const router = useRouter()

const defaults = () => ({
  contractPrice: undefined as number | undefined,
  purchasePrice: undefined as number | undefined,
  depositPercent: 10,
  residualMode: 'percent' as 'percent' | 'amount',
  residualPercent: 10,
  residualAmount: 1,
  annualRatePercent: 25,
  years: 5,
  frequency: 12 as LeaseInput['frequency'],
  mode: 'a' as LeaseInput['mode'],
  refundDeposit: true,
  startDate: '',
  firstPayDate: '',
  payDayOfMonth: 15 as number | undefined,
})
const form = reactive(defaults())
const input = computed<LeaseInput>(() => ({
  ...form,
  contractPrice: form.contractPrice ?? NaN,
  purchasePrice: form.purchasePrice ?? NaN,
}))
const error = computed(() => validateLeaseInput(input.value))
const result = computed(() => error.value ? null : calculateLease(input.value))
const activeMode = computed(() => INTEREST_MODES.find(m => m.value === form.mode)!)
const periodUnit = computed(() => PAYMENT_FREQUENCIES.find(f => f.value === form.frequency)!.unit)
const hasDueDate = computed(() => Boolean(result.value?.rows[0]?.dueDate))
const money = (v: number) => '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const percent = (v: number | null) => v === null ? '—' : (v * 100).toFixed(2) + '%'

const columns = [
  { key: 'openingBalance', label: '期初本金' }, { key: 'rent', label: '租金' },
  { key: 'interest', label: '利息' }, { key: 'principal', label: '归还本金' },
  { key: 'closingBalance', label: '期末本金' }, { key: 'residual', label: '留购价收入' },
  { key: 'depositRefund', label: '退还押金' }, { key: 'cashFlow', label: '净现金流' },
  { key: 'cumulativeCashFlow', label: '累计现金流' },
]
const metrics = computed(() => result.value ? [
  { label: '每期租金', value: money(result.value.rent), note: `每${periodUnit.value}支付，共 ${result.value.periods} 期` },
  { label: '累计租金', value: money(result.value.totalRent), note: '不含押金和期末留购价' },
  { label: '全期净收益', value: money(result.value.profit), note: '全部现金流合计，未扣税费及运营成本' },
  { label: '自有资金占用', value: money(result.value.equity), note: '实际采购价 − 起租押金' },
  { label: '真实年化 · 有效', value: percent(result.value.effectiveAnnualIrr), note: '(1 + 每期 IRR)^每年期数 − 1' },
  { label: '真实年化 · 名义', value: percent(result.value.nominalAnnualIrr), note: '每期 IRR × 每年期数' },
  { label: '总回报率', value: percent(result.value.returnOnEquity), note: '全期净收益 ÷ 自有资金占用' },
  { label: '承租方实际承担年化', value: percent(result.value.customerAnnualIrr), note: '以合同总价作为融资额，按有效年化计算' },
] : [])

function reset() { Object.assign(form, defaults()) }

function applyYunshanPreset() {
  Object.assign(form, {
    contractPrice: 280000,
    purchasePrice: 280000,
    depositPercent: 0,
    residualMode: 'amount',
    residualPercent: 0,
    residualAmount: 1,
    annualRatePercent: 20,
    years: 5,
    frequency: 12,
    mode: 'b',
    refundDeposit: true,
    startDate: '2026-10-01',
    firstPayDate: '2026-11-15',
    payDayOfMonth: 15,
  })
}

function createContractFromQuote() {
  if (!result.value) return
  if (form.frequency !== 12) {
    ElMessage.warning('当前合同租金计划按月期生成，建议切换为「月付」后再一键创建合同')
  }
  const r = result.value
  const draft = {
    nature: '设备融资租赁',
    equipmentTotal: form.contractPrice,
    finalDealPrice: form.contractPrice,
    termMonths: r.periods,
    monthRent: r.rent,
    deposit: Math.round(r.deposit * 100) / 100,
    endTransferPrice: Math.round(r.residual * 100) / 100,
    leaseAnnualRate: form.annualRatePercent,
    interestMode: form.mode,
    targetIrr: r.effectiveAnnualIrr ? Math.round(r.effectiveAnnualIrr * 10000) / 10000 : undefined,
    startDate: form.startDate || undefined,
    firstPayDate: form.firstPayDate || undefined,
    payDayOfMonth: form.payDayOfMonth || undefined,
    scheduleItems: r.rows.map(row => ({
      periodNo: row.period,
      dueDate: row.dueDate || undefined,
      amount: row.rent,
      openingPrincipal: row.openingBalance,
      interestAmount: row.interest,
      principalAmount: row.principal,
      closingPrincipal: row.closingBalance,
    })),
  }
  sessionStorage.setItem('worland_quote_draft', JSON.stringify(draft))
  ElMessage.success('已带入测算方案（含本息摊还计划），请补充客户与设备后签约')
  router.push({ path: '/contract', query: { fromQuote: '1' } })
}
</script>

<template>
  <div class="lease-calculator">
    <el-card class="input-panel">
      <template #header>
        <div class="panel-header">
          <strong>设备租赁收益率测算</strong>
          <div>
            <el-button text type="primary" size="small" @click="applyYunshanPreset">等额本息1元留购模板</el-button>
            <el-button text @click="reset">重置</el-button>
          </div>
        </div>
      </template>
      <el-form :model="form" label-position="top">
        <div class="input-grid">
          <el-form-item label="合同总价（元）">
            <el-input-number v-model="form.contractPrice" aria-label="合同总价（元）" :min="0" :max="1e12" :precision="2" :controls="false" placeholder="请填写" />
          </el-form-item>
          <el-form-item label="实际采购价（元）">
            <el-input-number v-model="form.purchasePrice" aria-label="实际采购价（元）" :min="0" :max="1e12" :precision="2" :controls="false" placeholder="请填写" />
          </el-form-item>
          <el-form-item label="押金比例（%）">
            <el-input-number v-model="form.depositPercent" aria-label="押金比例（%）" :min="0" :max="100" :precision="2" :controls="false" placeholder="0 表示免押金" />
          </el-form-item>
          <el-form-item label="名义年化利率（%）">
            <el-input-number v-model="form.annualRatePercent" aria-label="名义年化利率（%）" :min="0" :max="1000" :precision="4" :controls="false" placeholder="如 20" />
          </el-form-item>
          <el-form-item label="租期（年）">
            <el-input-number v-model="form.years" aria-label="租期（年）" :min="0" :max="1200" :precision="4" :controls="false" placeholder="如 5" />
          </el-form-item>
          <el-form-item label="每月固定付款日">
            <el-input-number v-model="form.payDayOfMonth" aria-label="每月固定付款日" :min="1" :max="31" :precision="0" :controls="false" placeholder="如 15" />
          </el-form-item>
        </div>

        <el-form-item label="期末留购价方式">
          <div class="residual-row">
            <el-radio-group v-model="form.residualMode" size="small">
              <el-radio-button value="percent">按残值比例 (%)</el-radio-button>
              <el-radio-button value="amount">固定金额 (元)</el-radio-button>
            </el-radio-group>
            <el-input-number
              v-if="form.residualMode === 'percent'"
              v-model="form.residualPercent"
              aria-label="残值比例（%）"
              :min="0" :max="100" :precision="2" :controls="false"
              style="width: 120px"
            />
            <el-input-number
              v-else
              v-model="form.residualAmount"
              aria-label="固定留购价（元）"
              :min="0" :max="1e12" :precision="2" :controls="false"
              placeholder="如 1.00"
              style="width: 120px"
            />
          </div>
        </el-form-item>

        <div class="input-grid">
          <el-form-item label="起租日（可选）">
            <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" placeholder="选择起租日" style="width: 100%" />
          </el-form-item>
          <el-form-item label="首期付款日（可选）">
            <el-date-picker v-model="form.firstPayDate" type="date" value-format="YYYY-MM-DD" placeholder="如 2026-11-15" style="width: 100%" />
          </el-form-item>
        </div>

        <el-form-item label="付款频率">
          <el-radio-group v-model="form.frequency">
            <el-radio-button v-for="f in PAYMENT_FREQUENCIES" :key="f.value" :value="f.value">{{ f.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="计息口径">
          <el-radio-group v-model="form.mode" class="interest-modes">
            <el-radio v-for="m in INTEREST_MODES" :key="m.value" :value="m.value">{{ m.label }}</el-radio>
          </el-radio-group>
          <p class="muted mode-note">{{ activeMode.description }}</p>
        </el-form-item>
        <el-form-item label="押金到期处理">
          <el-radio-group v-model="form.refundDeposit">
            <el-radio :value="true">退还 / 抵末期</el-radio><el-radio :value="false">不退还</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <div style="margin-top: 8px; display: flex; flex-direction: column; gap: 8px">
        <el-button type="primary" :disabled="!result" style="width: 100%" @click="createContractFromQuote">
          以此方案创建租赁合同（带入 {{ result ? result.periods : 0 }} 期本息计划）
        </el-button>
        <p class="muted" style="margin: 0">修改参数后即时重算。点击上方按钮可将方案一键带入合同签约弹窗。</p>
      </div>
    </el-card>

    <div class="results-panel" aria-live="polite">
      <el-card v-if="!result"><el-empty :description="error" /></el-card>
      <template v-else>
        <div class="metric-grid">
          <el-card v-for="metric in metrics" :key="metric.label" shadow="never">
            <div class="muted">{{ metric.label }}</div><div class="metric-value">{{ metric.value }}</div>
            <div class="muted metric-note">{{ metric.note }}</div>
          </el-card>
        </div>
        <el-card>
          <p class="formula"><strong>{{ activeMode.label }}</strong>：{{ activeMode.description }}</p>
          <p class="formula">计息本金 {{ money(result.base) }}；起租押金 {{ money(result.deposit) }}；期末留购价 {{ money(result.residual) }}。</p>
          <p class="formula">每期租金公式值 {{ result.exactRent.toFixed(4) }} 元，按分取整为 {{ money(result.rent) }}，末期调整本金与利息尾差。</p>
          <p class="muted">本面板按输入金额计算现金流，不另计税费、维保、资金成本或管理费。</p>
          <el-alert v-if="result.multipleSignChanges" title="现金流多次变号，IRR 可能存在多个解；优先显示搜索区间内找到的非负根，请结合净收益核对。" type="warning" :closable="false" />
          <el-alert v-if="result.equity <= 0 || result.periodIrr === null" title="自有资金占用非正或未找到有效 IRR 时，相应收益率显示“—”，请结合逐期现金流判断。" type="info" :closable="false" />
        </el-card>
        <el-card header="逐期现金流与摊还明细">
          <p class="formula">起租日：采购支出 {{ money(result.input.purchasePrice) }}，收取押金 {{ money(result.deposit) }}，净现金流 {{ money(result.cashFlows[0]) }}。</p>
          <el-table :data="result.rows" border stripe max-height="480" style="width:100%">
            <el-table-column prop="period" label="期数" width="65" fixed />
            <el-table-column v-if="hasDueDate" prop="dueDate" label="付款日期" width="115" fixed />
            <el-table-column v-for="col in columns" :key="col.key" :label="col.label" min-width="130" align="right">
              <template #default="{ row }">{{ money(row[col.key]) }}</template>
            </el-table-column>
          </el-table>
          <p class="muted">累计租金 {{ money(result.totalRent) }} = 本金 {{ money(result.totalPrincipal) }} + 利息 {{ money(result.totalInterest) }}。留购价和退还押金在末期现金流中单独计入。</p>
        </el-card>
      </template>
    </div>
  </div>
</template>

<style scoped>
.lease-calculator { display:grid; grid-template-columns:minmax(320px,400px) minmax(0,1fr); gap:20px; align-items:start; }
.input-panel,.results-panel { min-width:0; }
.panel-header { display:flex; justify-content:space-between; align-items:center; gap:8px; }
.input-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 16px; }
.input-grid :deep(.el-input-number) { width:100%; }
.residual-row { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.interest-modes { display:flex; flex-direction:column; align-items:flex-start; }
.interest-modes :deep(.el-radio) { margin-right:0; }
.mode-note { line-height:1.6; margin:8px 0 0; }
.results-panel { display:grid; gap:16px; }
.metric-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:12px; }
.metric-value { font-size:22px; font-weight:600; margin:12px 0; overflow-wrap:anywhere; }
.metric-note { font-size:12px; line-height:1.6; }
.muted { color:var(--el-text-color-secondary); font-size:13px; }
.formula { margin:0 0 12px; line-height:1.8; }
@media(max-width:1400px) { .metric-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media(max-width:1000px) { .lease-calculator { grid-template-columns:minmax(0,1fr); } }
@media(max-width:420px) { .metric-value { font-size:18px; } .input-grid { gap:0 10px; } }
</style>
