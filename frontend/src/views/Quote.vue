<script setup lang="ts">
import { computed, reactive } from 'vue'
import { calculateLease, validateLeaseInput, INTEREST_MODES, PAYMENT_FREQUENCIES, type LeaseInput } from '@/utils/leaseCalculator'

const defaults = () => ({
  contractPrice: undefined as number | undefined, purchasePrice: undefined as number | undefined,
  depositPercent: 10, residualPercent: 10, annualRatePercent: 25, years: 5,
  frequency: 12 as LeaseInput['frequency'], mode: 'a' as LeaseInput['mode'], refundDeposit: true,
})
const form = reactive(defaults())
const input = computed<LeaseInput>(() => ({ ...form, contractPrice: form.contractPrice ?? NaN, purchasePrice: form.purchasePrice ?? NaN }))
const error = computed(() => validateLeaseInput(input.value))
const result = computed(() => error.value ? null : calculateLease(input.value))
const activeMode = computed(() => INTEREST_MODES.find(m => m.value === form.mode)!)
const periodUnit = computed(() => PAYMENT_FREQUENCIES.find(f => f.value === form.frequency)!.unit)
const money = (v: number) => '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const percent = (v: number | null) => v === null ? '—' : (v * 100).toFixed(2) + '%'
const fields = [
  { key: 'contractPrice', label: '合同总价（元）', max: 1e12, precision: 2 },
  { key: 'purchasePrice', label: '实际采购价（元）', max: 1e12, precision: 2 },
  { key: 'depositPercent', label: '押金比例（%）', max: 100, precision: 2 },
  { key: 'residualPercent', label: '残值比例（%）', max: 100, precision: 2 },
  { key: 'annualRatePercent', label: '名义年化利率（%）', max: 1000, precision: 4 },
  { key: 'years', label: '租期（年）', max: 1200, precision: 4 },
] as const
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
</script>

<template>
  <div class="lease-calculator">
    <el-card class="input-panel">
      <template #header><div class="panel-header"><strong>设备租赁收益率测算</strong><el-button text @click="reset">重置</el-button></div></template>
      <el-form :model="form" label-position="top">
        <div class="input-grid">
          <el-form-item v-for="field in fields" :key="field.key" :label="field.label">
            <el-input-number v-model="form[field.key]" :aria-label="field.label" :min="0" :max="field.max" :precision="field.precision" :controls="false" placeholder="请填写" />
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
      <p class="muted">修改参数后即时重算。结果仅用于报价测算，不生成合同、付款或发票。</p>
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
.lease-calculator { display:grid; grid-template-columns:minmax(300px,370px) minmax(0,1fr); gap:20px; align-items:start; }
.input-panel,.results-panel { min-width:0; }
.panel-header { display:flex; justify-content:space-between; align-items:center; gap:8px; }
.input-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 16px; }
.input-grid :deep(.el-input-number) { width:100%; }
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
