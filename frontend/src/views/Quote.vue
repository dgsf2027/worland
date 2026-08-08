<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { calcQuote, type QuoteRequest, type QuoteResponse } from '@/api/quote'

const form = reactive<QuoteRequest>({
  marketPrice: 200000,
  category: '播种墙',
  customerType: '云山快仓',
  monthlyLaborValue: 11200,
  firstPayRatio: 0.3,
})

const result = ref<QuoteResponse | null>(null)
const loading = ref(false)

const pct = (v?: number) => (v == null ? '—' : (v * 100).toFixed(1) + '%')
const money = (v?: number) => (v == null ? '—' : '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2 }))

async function onCalc() {
  loading.value = true
  try {
    result.value = await calcQuote(form)
  } catch (e: any) {
    ElMessage.error(e?.message || '测算失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-row :gutter="16">
    <el-col :span="9">
      <el-card header="测算输入">
        <el-form :model="form" label-width="120px">
          <el-form-item label="设备市场价(元)">
            <el-input-number v-model="form.marketPrice" :min="1000" :step="10000" style="width: 100%" />
          </el-form-item>
          <el-form-item label="品类">
            <el-select v-model="form.category" style="width: 100%">
              <el-option label="播种墙(3年/转10%)" value="播种墙" />
              <el-option label="货架(5年/转30%)" value="货架" />
            </el-select>
          </el-form-item>
          <el-form-item label="客户类型">
            <el-select v-model="form.customerType" style="width: 100%">
              <el-option label="云山快仓(目标25%)" value="云山快仓" />
              <el-option label="其他客户(30-35%)" value="其他" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标IRR覆盖">
            <el-input-number v-model="form.targetIrr" :min="0.1" :max="0.6" :step="0.05" :precision="2" style="width: 100%" placeholder="不填按客户类型默认" />
          </el-form-item>
          <el-form-item label="月替代人工价值">
            <el-input-number v-model="form.monthlyLaborValue" :min="0" :step="500" style="width: 100%" />
          </el-form-item>
          <el-form-item label="首付比例">
            <el-input-number v-model="form.firstPayRatio" :min="0" :max="1" :step="0.1" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loading" @click="onCalc">测算报价</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </el-col>

    <el-col :span="15">
      <el-card header="测算结果" v-if="result">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="精确月租(IRR)">
            <b style="font-size:16px;color:#409EFF">{{ money(result.monthlyRent) }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="速算月租(系数)">{{ money(result.monthlyRentQuick) }}（{{ pct(result.speedCoeff) }}）</el-descriptions-item>
          <el-descriptions-item label="期末转让价">{{ money(result.transferPrice) }}（{{ pct(result.transferRate) }}）</el-descriptions-item>
          <el-descriptions-item label="客户总付">{{ money(result.customerTotalPay) }}</el-descriptions-item>
          <el-descriptions-item label="税后净利">{{ money(result.afterTaxNetProfit) }}</el-descriptions-item>
          <el-descriptions-item label="税后IRR">{{ pct(result.afterTaxIrr) }}</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">三层回报</el-divider>
        <el-row :gutter="12">
          <el-col :span="8"><el-statistic title="① 本金" :value="result.layer1Return * 100" suffix="%" :precision="1" /></el-col>
          <el-col :span="8"><el-statistic title="② 供应商杠杆" :value="result.layer2Return * 100" suffix="%" :precision="1" /></el-col>
          <el-col :span="8"><el-statistic title="③ 融资杠杆" :value="result.layer3Return * 100" suffix="%" :precision="1" /></el-col>
        </el-row>

        <el-divider content-position="left">价值定价校验</el-divider>
        <el-space direction="vertical" alignment="start">
          <el-tag :type="result.paybackPass ? 'success' : 'danger'">
            ① 回本期 {{ result.paybackMonths }} 月 {{ result.paybackPass ? '≤18 达标' : '>18 吸引力不足' }}
          </el-tag>
          <el-tag :type="result.benefitPass ? 'success' : 'danger'">
            ② 承租方月净收益 {{ money(result.monthlyNetBenefit) }} {{ result.benefitPass ? '>0 达标' : '≤0 不达标' }}
          </el-tag>
          <el-tag :type="result.valuePricingPass ? 'success' : 'info'" effect="dark">
            {{ result.valuePricingPass ? '两校验全过 · 可解锁生成合同' : '未达标 · 锁定生成合同' }}
          </el-tag>
        </el-space>

        <template v-if="result.occupiedCapital != null">
          <el-divider content-position="left">单台首付杠杆</el-divider>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="首付">{{ money(result.firstPay) }}</el-descriptions-item>
            <el-descriptions-item label="押金">{{ money(result.deposit) }}</el-descriptions-item>
            <el-descriptions-item label="实际资金占用">{{ money(result.occupiedCapital) }}</el-descriptions-item>
          </el-descriptions>
          <el-text size="small" type="info">{{ result.leverageNote }}</el-text>
        </template>

        <el-alert style="margin-top:12px" type="info" :closable="false" :title="result.note" />
      </el-card>
      <el-empty v-else description="填写左侧参数,点击「测算报价」" />
    </el-col>
  </el-row>
</template>
