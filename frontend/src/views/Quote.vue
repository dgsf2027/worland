<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  calcQuote, QUOTE_CATEGORIES, QUOTE_CUSTOMER_TYPES,
  type QuoteRequest, type QuoteResponse,
} from '@/api/quote'

/**
 * 报价测算器(曜石 货架/播种墙测算表简版口径):
 * 填市场价与采购成本 → 月租可手填,也可留空由目标税后 IRR 反解 → 三个视角(租赁公司/客户/实际成本)。
 * 比率在页面按百分数填写,提交时换成 0-1 小数;不填的参数用系统默认(rule_config)。
 * 基准价、框架折扣、采购价等商业数据不预置,每次自行输入。
 */
type Num = number | undefined

const form = reactive({
  category: '播种墙',
  customerType: '其他·标准',
  marketPrice: undefined as Num,
  costMode: 'direct' as 'direct' | 'discount',
  purchasePrice: undefined as Num,
  discountBase: undefined as Num,
  discountPct: undefined as Num,
  freightInstall: undefined as Num,
  termMonths: undefined as Num,
  prepayPct: undefined as Num,
  transferPct: undefined as Num,
  targetPct: undefined as Num,
  monthlyRent: undefined as Num,
  maintenancePerYear: undefined as Num,
  warrantyMonths: undefined as Num,
  customerGeneral: true,
  replaceHeadcount: undefined as Num,
  laborCostMonthly: undefined as Num,
  slots: undefined as Num,
  capitalPct: undefined as Num,
  mgmtPct: undefined as Num,
})

const result = ref<QuoteResponse | null>(null)
const loading = ref(false)
const isWall = computed(() => form.category === '播种墙')

const canCalc = computed(() => {
  if (!form.marketPrice) return false
  return form.costMode === 'direct' ? !!form.purchasePrice : !!form.discountBase && form.discountPct != null
})

const ratio = (v: Num) => (v == null ? undefined : Number((v / 100).toFixed(6)))

function buildRequest(): QuoteRequest {
  const req: QuoteRequest = {
    category: form.category,
    customerType: form.customerType,
    marketPrice: form.marketPrice as number,
    termMonths: form.termMonths,
    prepayRatio: ratio(form.prepayPct),
    transferRate: ratio(form.transferPct),
    targetIrr: ratio(form.targetPct),
    monthlyRent: form.monthlyRent,
    maintenancePerYear: form.maintenancePerYear,
    warrantyMonths: form.warrantyMonths,
    capitalCostRate: ratio(form.capitalPct),
    mgmtFeeRate: ratio(form.mgmtPct),
    customerGeneralTaxpayer: form.customerGeneral,
    laborCostMonthly: form.laborCostMonthly,
  }
  if (form.costMode === 'direct') {
    req.purchasePrice = form.purchasePrice
  } else {
    req.discountBase = form.discountBase
    req.discountRate = ratio(form.discountPct)
    req.freightInstall = form.freightInstall
  }
  if (isWall.value) req.replaceHeadcount = form.replaceHeadcount
  else req.slots = form.slots
  return req
}

const pct = (v?: number | null, d = 2) => (v == null ? '—' : (v * 100).toFixed(d) + '%')
const money = (v?: number | null) =>
  v == null ? '—' : '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const yuan = (v?: number | null) =>
  v == null ? '—' : '¥' + v.toLocaleString('zh-CN', { maximumFractionDigits: 0 })

async function onCalc() {
  loading.value = true
  try {
    result.value = await calcQuote(buildRequest())
  } catch (e: any) {
    ElMessage.error(e?.message || '测算失败')
  } finally {
    loading.value = false
  }
}

function onReset() {
  Object.assign(form, {
    marketPrice: undefined, purchasePrice: undefined, discountBase: undefined, discountPct: undefined,
    freightInstall: undefined, termMonths: undefined, prepayPct: undefined, transferPct: undefined,
    targetPct: undefined, monthlyRent: undefined, maintenancePerYear: undefined, warrantyMonths: undefined,
    replaceHeadcount: undefined, laborCostMonthly: undefined, slots: undefined, capitalPct: undefined,
    mgmtPct: undefined, customerGeneral: true,
  })
  result.value = null
}

// 切换品类:价格、租期、预付/转让、维保、替代人数/货位数都是按品类填的,旧值带到新品类会悄悄算错,所以清空重填
watch(() => form.category, onReset)
</script>

<template>
  <el-row :gutter="16">
    <el-col :span="9">
      <el-card header="测算输入">
        <el-form :model="form" label-width="112px" size="default">
          <el-form-item label="品类">
            <el-radio-group v-model="form.category">
              <el-radio-button v-for="c in QUOTE_CATEGORIES" :key="c" :value="c">{{ c }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="客户类型">
            <el-select v-model="form.customerType" style="width: 100%">
              <el-option v-for="t in QUOTE_CUSTOMER_TYPES" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
          <el-form-item label="市场价(元)">
            <el-input-number v-model="form.marketPrice" :min="0" :step="10000" :controls="false" style="width: 100%"
              placeholder="客户自购价,含运安" />
          </el-form-item>

          <el-form-item label="采购成本">
            <el-radio-group v-model="form.costMode" size="small">
              <el-radio-button value="direct">直接填</el-radio-button>
              <el-radio-button value="discount">按折扣算</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <template v-if="form.costMode === 'direct'">
            <el-form-item label="采购成本(元)">
              <el-input-number v-model="form.purchasePrice" :min="0" :step="10000" :controls="false" style="width: 100%"
                placeholder="含税含运安" />
            </el-form-item>
          </template>
          <template v-else>
            <el-form-item label="折扣基数(元)">
              <el-input-number v-model="form.discountBase" :min="0" :controls="false" style="width: 100%"
                placeholder="不含运安" />
            </el-form-item>
            <el-form-item label="折扣率(%)">
              <el-input-number v-model="form.discountPct" :min="0" :max="99.99" :precision="2" :controls="false"
                style="width: 100%" />
            </el-form-item>
            <el-form-item label="运费+安装(元)">
              <el-input-number v-model="form.freightInstall" :min="0" :controls="false" style="width: 100%"
                placeholder="不填按 0" />
            </el-form-item>
          </template>

          <el-form-item label="月租(元)">
            <el-input-number v-model="form.monthlyRent" :min="0" :controls="false" style="width: 100%"
              placeholder="不填 = 按目标 IRR 反解" />
          </el-form-item>

          <el-collapse>
            <el-collapse-item title="合同口径(不填用系统默认)" name="contract">
              <el-form-item label="租期(月)">
                <el-input-number v-model="form.termMonths" :min="1" :precision="0" :controls="false" style="width: 100%"
                  placeholder="播种墙 36 / 货架 60" />
              </el-form-item>
              <el-form-item label="预付比例(%)">
                <el-input-number v-model="form.prepayPct" :min="0" :precision="2" :controls="false" style="width: 100%"
                  placeholder="占市场价" />
              </el-form-item>
              <el-form-item label="转让比例(%)">
                <el-input-number v-model="form.transferPct" :min="0" :precision="2" :controls="false" style="width: 100%"
                  placeholder="占市场价" />
              </el-form-item>
              <el-form-item label="目标税后IRR(%)">
                <el-input-number v-model="form.targetPct" :min="0" :precision="2" :controls="false" style="width: 100%"
                  placeholder="不填按客户类型" />
              </el-form-item>
            </el-collapse-item>
            <el-collapse-item title="成本视角" name="cost">
              <el-form-item label="保外维保(元/年)">
                <el-input-number v-model="form.maintenancePerYear" :min="0" :controls="false" style="width: 100%"
                  placeholder="不填按 0" />
              </el-form-item>
              <el-form-item label="质保期(月)">
                <el-input-number v-model="form.warrantyMonths" :min="0" :precision="0" :controls="false"
                  style="width: 100%" placeholder="不填按 0" />
              </el-form-item>
              <el-form-item label="资金成本(%/年)">
                <el-input-number v-model="form.capitalPct" :min="0" :precision="2" :controls="false" style="width: 100%"
                  placeholder="不填用默认" />
              </el-form-item>
              <el-form-item label="管理费率(%)">
                <el-input-number v-model="form.mgmtPct" :min="0" :precision="2" :controls="false" style="width: 100%"
                  placeholder="不填用默认" />
              </el-form-item>
            </el-collapse-item>
            <el-collapse-item title="客户视角" name="customer">
              <el-form-item label="一般纳税人">
                <el-switch v-model="form.customerGeneral" />
                <span class="muted">决定客户自购、租赁能否抵进项</span>
              </el-form-item>
              <el-form-item v-if="isWall" label="替代人工(人)">
                <el-input-number v-model="form.replaceHeadcount" :min="0" :step="0.5" :precision="1"
                  :controls="false" style="width: 100%" placeholder="每台替代人数" />
              </el-form-item>
              <el-form-item v-if="isWall" label="人工月成本(元)">
                <el-input-number v-model="form.laborCostMonthly" :min="0" :controls="false" style="width: 100%"
                  placeholder="不填用默认" />
              </el-form-item>
              <el-form-item v-if="!isWall" label="货位数">
                <el-input-number v-model="form.slots" :min="1" :precision="0" :controls="false" style="width: 100%"
                  placeholder="算单位月租" />
              </el-form-item>
            </el-collapse-item>
          </el-collapse>

          <el-form-item style="margin-top: 16px">
            <el-button type="primary" :loading="loading" :disabled="!canCalc" @click="onCalc">测算报价</el-button>
            <el-button @click="onReset">清空</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </el-col>

    <el-col :span="15">
      <template v-if="result">
        <el-card class="summary" shadow="never">
          <div class="sum-row">
            <div>
              <div class="sum-l">月租（{{ result.rentSolved ? '按目标 IRR 反解，向上取整' : '手填' }}）</div>
              <div class="sum-v main">{{ yuan(result.monthlyRent) }}</div>
            </div>
            <div>
              <div class="sum-l">税后 IRR（目标 {{ pct(result.targetIrr, 1) }}）</div>
              <div class="sum-v">{{ pct(result.lessor.afterTaxIrr) }}
                <el-tag :type="result.lessor.meetsTarget ? 'success' : 'danger'" size="small">
                  {{ result.lessor.meetsTarget ? '达标' : '未达标' }}
                </el-tag>
              </div>
            </div>
            <div>
              <div class="sum-l">实际利润</div>
              <div class="sum-v">{{ yuan(result.cost.actualProfit) }}</div>
            </div>
            <div>
              <div class="sum-l">保本月租</div>
              <div class="sum-v">{{ yuan(result.cost.breakEvenRent) }}</div>
            </div>
          </div>
        </el-card>

        <el-tabs class="tabs" type="border-card">
          <!-- ===== 租赁公司视角 ===== -->
          <el-tab-pane label="租赁公司视角">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="采购成本(含税)">{{ money(result.purchasePrice) }}</el-descriptions-item>
              <el-descriptions-item label="客户自购价(市场价)">{{ money(result.marketPrice) }}</el-descriptions-item>
              <el-descriptions-item label="租期">{{ result.termMonths }} 个月</el-descriptions-item>
              <el-descriptions-item label="起租预付款">{{ money(result.prepayment) }}</el-descriptions-item>
              <el-descriptions-item label="期末转让价">
                {{ money(result.transferPrice) }}（{{ pct(result.transferRate, 1) }}）
              </el-descriptions-item>
              <el-descriptions-item label="收款总额(含税)">{{ money(result.lessor.totalCollected) }}</el-descriptions-item>
              <el-descriptions-item label="开票征收率">{{ pct(result.lessor.collectRate) }}</el-descriptions-item>
              <el-descriptions-item label="税费系数 k">{{ pct(result.lessor.taxFactor, 3) }}</el-descriptions-item>
              <el-descriptions-item label="增值税">{{ money(result.lessor.vat) }}</el-descriptions-item>
              <el-descriptions-item label="附加税">{{ money(result.lessor.surtax) }}</el-descriptions-item>
              <el-descriptions-item label="企业所得税">{{ money(result.lessor.incomeTax) }}</el-descriptions-item>
              <el-descriptions-item label="税后净利">
                <b>{{ money(result.lessor.netProfit) }}</b>
                <span class="muted">未扣保外维保、资金成本、管理费</span>
              </el-descriptions-item>
              <el-descriptions-item label="税前 IRR(年化)">{{ pct(result.lessor.preTaxIrr) }}</el-descriptions-item>
              <el-descriptions-item label="税后 IRR(年化)"><b>{{ pct(result.lessor.afterTaxIrr) }}</b></el-descriptions-item>
              <el-descriptions-item label="回本月数(近似)">
                {{ result.lessor.paybackMonths }} 个月<span class="muted">不含期末转让价</span>
              </el-descriptions-item>
            </el-descriptions>
          </el-tab-pane>

          <!-- ===== 客户视角 ===== -->
          <el-tab-pane label="客户视角（租 vs 买）">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="租赁总付(含税)">{{ money(result.customer.totalPay) }}</el-descriptions-item>
              <el-descriptions-item label="较自购多付">
                {{ money(result.customer.extraPay) }}（{{ pct(result.customer.extraRatio, 1) }}）
              </el-descriptions-item>
              <el-descriptions-item label="自购可抵进项">{{ money(result.customer.buyInputTax) }}</el-descriptions-item>
              <el-descriptions-item label="租赁可抵进项">{{ money(result.customer.leaseInputTax) }}</el-descriptions-item>
              <el-descriptions-item label="抵税后多付">
                {{ money(result.customer.afterTaxExtra) }}（{{ pct(result.customer.afterTaxExtraRatio, 1) }}）
              </el-descriptions-item>
              <el-descriptions-item label="隐含年化融资成本(税前)">
                {{ pct(result.customer.impliedFinanceCost) }}
              </el-descriptions-item>
              <el-descriptions-item label="自购价折合每月">{{ money(result.customer.buyPricePerMonth) }}</el-descriptions-item>
              <el-descriptions-item v-if="result.customer.unitRent != null" label="单位月租(元/货位·月)">
                {{ money(result.customer.unitRent) }}
              </el-descriptions-item>
            </el-descriptions>

            <template v-if="result.category === '播种墙' && result.customer.monthlySaving != null">
              <el-divider content-position="left">替代人工 · 价值定价校验</el-divider>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="替代人数">{{ result.customer.replaceHeadcount }} 人</el-descriptions-item>
                <el-descriptions-item label="月节省人工">{{ money(result.customer.monthlySaving) }}</el-descriptions-item>
                <el-descriptions-item label="客户月净收益">{{ money(result.customer.monthlyNetBenefit) }}</el-descriptions-item>
                <el-descriptions-item label="自购回本月数">
                  {{ result.customer.buyPaybackMonths ?? '—' }} 个月
                </el-descriptions-item>
              </el-descriptions>
              <el-space direction="vertical" alignment="start" class="checks">
                <el-tag :type="result.customer.paybackPass ? 'success' : 'danger'">
                  ① 自购回本期 {{ result.customer.paybackPass ? '≤18 个月，达标' : '>18 个月，吸引力不足' }}
                </el-tag>
                <el-tag :type="result.customer.benefitPass ? 'success' : 'danger'">
                  ② 客户月净收益 {{ result.customer.benefitPass ? '>0，达标' : '≤0，不达标' }}
                </el-tag>
                <el-tag :type="result.customer.valuePricingPass ? 'success' : 'info'" effect="dark">
                  {{ result.customer.valuePricingPass ? '两校验全过 · 可解锁生成合同' : '未达标 · 锁定生成合同' }}
                </el-tag>
              </el-space>
            </template>
            <el-alert class="verdict" type="info" :closable="false" show-icon :title="result.customer.verdict" />
          </el-tab-pane>

          <!-- ===== 实际成本视角 ===== -->
          <el-tab-pane label="实际成本视角">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="收入(扣销项税)">{{ money(result.cost.revenueExVat) }}</el-descriptions-item>
              <el-descriptions-item label="设备采购(含税)">{{ money(result.purchasePrice) }}</el-descriptions-item>
              <el-descriptions-item label="附加税 + 所得税">{{ money(result.cost.taxCost) }}</el-descriptions-item>
              <el-descriptions-item label="保外维保">{{ money(result.cost.maintenance) }}</el-descriptions-item>
              <el-descriptions-item label="资金占用成本">{{ money(result.cost.capitalCost) }}</el-descriptions-item>
              <el-descriptions-item label="管理费">{{ money(result.cost.mgmtFee) }}</el-descriptions-item>
              <el-descriptions-item label="实际总成本">{{ money(result.cost.totalCost) }}</el-descriptions-item>
              <el-descriptions-item label="实际月均成本">{{ money(result.cost.avgMonthlyCost) }}</el-descriptions-item>
              <el-descriptions-item label="实际利润">
                <b>{{ money(result.cost.actualProfit) }}</b>（利润率 {{ pct(result.cost.profitMargin, 1) }}）
              </el-descriptions-item>
              <el-descriptions-item label="资金峰值占用">{{ money(result.cost.peakCapital) }}</el-descriptions-item>
              <el-descriptions-item label="保本月租(近似)">{{ money(result.cost.breakEvenRent) }}</el-descriptions-item>
              <el-descriptions-item label="定价安全边际">{{ pct(result.cost.safetyMargin, 1) }}</el-descriptions-item>
            </el-descriptions>
            <p class="muted tip">
              实际利润 = 税后净利 − 保外维保 − 资金占用成本 − 管理费。资金占用成本 = 累计税后现金流为负的各月占用额之和 × 资金成本 ÷ 12;
              保本月租把当前月租下的资金成本代入,所得税与管理费按 0。
            </p>
          </el-tab-pane>
        </el-tabs>

        <el-alert v-for="(n, i) in result.notes" :key="i" class="note" type="info" :closable="false" :title="n" />
      </template>
      <el-empty v-else description="填写左侧参数，点击「测算报价」" />
    </el-col>
  </el-row>
</template>

<style scoped>
.muted { color: #909399; font-size: 12px; margin-left: 8px; }
.tip { margin: 12px 0 0; line-height: 1.7; }
.summary { margin-bottom: 12px; }
.sum-row { display: flex; gap: 40px; flex-wrap: wrap; align-items: flex-end; }
.sum-l { font-size: 12px; color: #909399; }
.sum-v { font-size: 20px; font-weight: 700; color: #303133; margin-top: 4px; }
.sum-v.main { font-size: 28px; color: #409eff; }
.tabs { margin-bottom: 12px; }
.checks { margin: 12px 0; }
.verdict { margin-top: 12px; }
.note { margin-top: 8px; }
</style>
