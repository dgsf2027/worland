<script setup lang="ts">
import { ref, watch } from 'vue'
import { fetchProjectPanorama, type ProjectPanorama } from '@/api/contract'

const props = defineProps<{
  modelValue: boolean
  contractId: number | null
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const loading = ref(false)
const data = ref<ProjectPanorama | null>(null)

function money(v?: number | null) {
  if (v === null || v === undefined) return '🔒'
  return '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function pct(v?: number | null) {
  if (v === null || v === undefined) return '—'
  return (v * 100).toFixed(2) + '%'
}

async function load() {
  if (!props.modelValue || !props.contractId) return
  loading.value = true
  try {
    data.value = await fetchProjectPanorama(props.contractId)
  } finally {
    loading.value = false
  }
}

watch(() => [props.modelValue, props.contractId], load, { immediate: true })
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    size="72%"
    :title="data ? `项目真实利润全景 · ${data.contractNo}（${data.customerName || ''}）` : '项目真实利润全景'"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div v-loading="loading" class="pano">
      <template v-if="data">
        <el-alert
          v-if="!data.costVisible"
          type="info"
          :closable="false"
          show-icon
          title="当前为投资人视角：采购投入、毛利/税后净利及资金峰值已脱敏 🔒，仅展示租赁回款侧指标。"
          style="margin-bottom: 12px"
        />

        <!-- 老板核心 4 卡 -->
        <el-row v-if="data.costVisible" :gutter="12" class="kpi-row">
          <el-col :span="6">
            <el-card shadow="never" class="kpi-card">
              <div class="k-title">预期全周期毛利 / 税后净利</div>
              <div class="k-val ok">{{ money(data.expectedGrossProfit) }}</div>
              <div class="k-sub">
                估税后净利 <b>{{ money(data.estimatedAfterTaxProfit) }}</b>
                （成本回报率 {{ pct(data.expectedGrossMargin) }}）
              </div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card shadow="never" class="kpi-card">
              <div class="k-title">当前实际净现金流</div>
              <div class="k-val" :class="(data.actualNetCashflow ?? 0) >= 0 ? 'ok' : 'warn'">
                {{ money(data.actualNetCashflow) }}
              </div>
              <div class="k-sub">
                实收租金 {{ money(data.receivedRent) }} − 已付货款 {{ money(data.purchasePaid) }}
              </div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card shadow="never" class="kpi-card">
              <div class="k-title">回本进度 / 预计回本期</div>
              <div class="k-val">{{ pct(data.paybackProgress) }}</div>
              <div class="k-sub">
                预计第 <b>{{ data.paybackPeriodNo || '—' }}</b> 期收回全部采购投入
                （总 {{ data.totalPeriods }} 期）
              </div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card shadow="never" class="kpi-card">
              <div class="k-title">最大垫资峰值</div>
              <div class="k-val danger">{{ money(data.peakCapitalOutlay) }}</div>
              <div class="k-sub">
                峰值月份 <b>{{ data.peakOutlayMonth || '—' }}</b>（含月结错期缓冲）
              </div>
            </el-card>
          </el-col>
        </el-row>

        <!-- 左右对照：采购投入侧 vs 租赁产出侧 -->
        <el-row :gutter="12" style="margin-top: 12px">
          <el-col :span="12">
            <el-card shadow="never" header="采购投入侧（上游供应商）">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="采购总投入">{{ money(data.purchaseTotal) }}</el-descriptions-item>
                <el-descriptions-item label="已付货款">{{ money(data.purchasePaid) }}</el-descriptions-item>
                <el-descriptions-item label="待付尾款">
                  <span :class="{ warn: (data.purchaseUnpaid ?? 0) > 0 }">{{ money(data.purchaseUnpaid) }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="已收进项票">{{ money(data.inputInvoiceTotal) }}</el-descriptions-item>
                <el-descriptions-item label="下一笔应付" :span="2">
                  <span v-if="data.nextPayableDate">
                    <b>{{ data.nextPayableDate }}</b> · {{ money(data.nextPayableAmount) }}
                    <span class="muted">（{{ data.nextPayableStage }}）</span>
                  </span>
                  <span v-else class="muted">暂无待付应付</span>
                </el-descriptions-item>
                <el-descriptions-item label="质保到期日" :span="2">
                  <span v-if="data.warrantyEndDate">
                    {{ data.warrantyEndDate }}
                    <el-tag
                      size="small"
                      :type="(data.warrantyDaysLeft ?? 0) < 90 ? 'warning' : 'success'"
                      style="margin-left: 6px"
                    >
                      {{ (data.warrantyDaysLeft ?? 0) >= 0 ? `剩余 ${data.warrantyDaysLeft} 天` : '已过保' }}
                    </el-tag>
                  </span>
                  <span v-else class="muted">未登记验收/质保</span>
                </el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-col>

          <el-col :span="12">
            <el-card shadow="never" header="租赁产出侧（下游客户回款）">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="合同全期应收">{{ money(data.plannedCustomerTotal) }}</el-descriptions-item>
                <el-descriptions-item label="累计实收租金"><b class="ok">{{ money(data.receivedRent) }}</b></el-descriptions-item>
                <el-descriptions-item label="剩余未收租金">{{ money(data.receivablePending) }}</el-descriptions-item>
                <el-descriptions-item label="剩余本金余额">{{ money(data.remainingPrincipal) }}</el-descriptions-item>
                <el-descriptions-item label="已结清期数">{{ data.settledPeriods }} / {{ data.totalPeriods }} 期</el-descriptions-item>
                <el-descriptions-item label="当前逾期金额">
                  <span :class="{ danger: (data.overdueAmount ?? 0) > 0 }">{{ money(data.overdueAmount) }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="销项开票进度" :span="2">
                  已开票 {{ money(data.invoicedRentAmount) }}
                  <el-tag v-if="data.pendingInvoiceCount" size="small" type="warning" style="margin-left: 6px">
                    待开 {{ data.pendingInvoiceCount }} 张
                  </el-tag>
                  <el-tag v-if="data.overdueInvoiceCount" size="small" type="danger" style="margin-left: 4px">
                    超期 {{ data.overdueInvoiceCount }} 张
                  </el-tag>
                </el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-col>
        </el-row>

        <!-- 税费估算明细 -->
        <el-card v-if="data.costVisible" shadow="never" style="margin-top: 12px">
          <div class="tax-bar">
            <span>销项估算增值税（{{ data.invoiceTaxRate != null ? (data.invoiceTaxRate * 100).toFixed(1) + '%' : '3%' }}）：<b>{{ money(data.estimatedOutputVat) }}</b></span>
            <span>进项增值税参考：<b>{{ money(data.estimatedInputVat) }}</b></span>
            <span>估算净税负：<b>{{ money(data.estimatedTaxCost) }}</b></span>
            <span>期末名义留购价：<b>{{ money(data.endTransferPrice) }}</b></span>
            <span>在手押金：<b>{{ money(data.depositHeld) }}</b></span>
          </div>
        </el-card>

        <!-- 关联采购合同/采购单 -->
        <div v-if="data.costVisible && data.linkedPurchases.length" style="margin-top: 14px">
          <div class="sec-title">关联采购合同 / 采购单</div>
          <el-table :data="data.linkedPurchases" size="small" border>
            <el-table-column prop="kind" label="单据类型" width="90" />
            <el-table-column prop="no" label="编号" min-width="150" />
            <el-table-column prop="supplierName" label="供应商" min-width="140" />
            <el-table-column label="合同/采购总额" align="right" width="130">
              <template #default="{ row }">{{ money(row.totalAmount) }}</template>
            </el-table-column>
            <el-table-column label="已付" align="right" width="120">
              <template #default="{ row }">{{ money(row.paidAmount) }}</template>
            </el-table-column>
            <el-table-column label="未付" align="right" width="120">
              <template #default="{ row }">{{ money(row.unpaidAmount) }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="90" />
            <el-table-column prop="warrantyEndDate" label="质保到期" width="110" />
          </el-table>
        </div>

        <!-- 逐月现金流对照表 -->
        <div v-if="data.costVisible && data.monthlyCashflows.length" style="margin-top: 14px">
          <div class="sec-title">逐月现金流与垫资轨迹（租金流入 vs 采购付款流出）</div>
          <el-table :data="data.monthlyCashflows" size="small" border max-height="320">
            <el-table-column prop="month" label="月份" width="100" />
            <el-table-column label="租金流入" align="right" width="130">
              <template #default="{ row }">
                <span :class="{ ok: row.rentInflow > 0 }">{{ money(row.rentInflow) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="采购流出" align="right" width="130">
              <template #default="{ row }">
                <span :class="{ danger: row.purchaseOutflow > 0 }">{{ money(row.purchaseOutflow) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="当月净流" align="right" width="130">
              <template #default="{ row }">
                <span :class="row.netFlow >= 0 ? 'ok' : 'danger'">{{ money(row.netFlow) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="累计净现金流" align="right" min-width="140">
              <template #default="{ row }">
                <b :class="row.cumulativeNet >= 0 ? 'ok' : 'warn'">{{ money(row.cumulativeNet) }}</b>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.pano { padding: 4px; }
.kpi-card :deep(.el-card__body) { padding: 12px 14px; }
.k-title { font-size: 12px; color: #909399; }
.k-val { font-size: 20px; font-weight: 700; margin: 6px 0; }
.k-sub { font-size: 12px; color: #606266; line-height: 1.5; }
.ok { color: #2f9e44; }
.warn { color: #e6a23c; }
.danger { color: #f56c6c; }
.muted { color: #909399; font-size: 12px; }
.tax-bar { display: flex; gap: 18px; flex-wrap: wrap; font-size: 12.5px; color: #606266; }
.sec-title { font-weight: 600; font-size: 13.5px; margin-bottom: 8px; border-left: 3px solid #409eff; padding-left: 8px; }
</style>
