<script setup lang="ts">
import { onMounted, ref } from 'vue'
import BrandLockup from '@/components/BrandLockup.vue'
import OperationsDashboard from '@/components/OperationsDashboard.vue'
import ProjectPanoramaDrawer from '@/components/ProjectPanoramaDrawer.vue'
import { useDashboardData } from '@/api/dashboard'
import { fetchWorkbench, type Workbench } from '@/api/workbench'
import { fetchWorkbenchProjectPanoramas, type ProjectPanoramaOverview } from '@/api/contract'

/**
 * 企业文化文案（《标志标准规范 V1.0》里的 slogan + 愿景 / 使命）。
 * 要改只改这里一处，页面自动跟着变。
 */
const SLOGAN = '淬炼于火 · 锋利如曜'
const MISSION = [
  { k: '愿景', v: '科技赋能云仓' },
  { k: '使命', v: '为云仓提效降本' },
]

const wb = ref<Workbench | null>(null)
const panoramas = ref<ProjectPanoramaOverview | null>(null)
const panoramaDrawer = ref(false)
const panoramaContractId = ref<number | null>(null)
const loading = ref(false)
const chartsLoading = ref(false)
const failed = ref(false)
const updatedAt = ref('')
const months = ref(6)
const dashboard = useDashboardData()
const charts = dashboard.charts

const now = () => new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })

function money(v?: number | null) {
  if (v === null || v === undefined) return '🔒'
  return '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function openPanorama(contractId: number) {
  panoramaContractId.value = contractId
  panoramaDrawer.value = true
}

/** 工作台、图表与项目利润总览并行取数；任一失败不拖垮另一块。 */
async function load() {
  if (loading.value) return
  loading.value = true
  chartsLoading.value = true
  failed.value = false
  const [result, , panoRes] = await Promise.allSettled([
    fetchWorkbench(),
    dashboard.load(months.value),
    fetchWorkbenchProjectPanoramas(),
  ])
  if (result.status === 'fulfilled') {
    wb.value = result.value
    updatedAt.value = now()
  } else {
    wb.value = null
    failed.value = true
  }
  if (panoRes.status === 'fulfilled') {
    panoramas.value = panoRes.value
  }
  loading.value = false
  chartsLoading.value = false
}
/** 切换月份只重取图表 */
async function changeMonths(value: number) {
  if (chartsLoading.value) return
  months.value = value
  chartsLoading.value = true
  await dashboard.load(value)
  chartsLoading.value = false
}
onMounted(load)
defineExpose({ load })
</script>

<template>
  <div class="wb">
    <!-- 品牌条（曜石智能 · 标志标准规范 V1.0：深色版标志 + slogan + 愿景 / 使命） -->
    <div class="brand-strip">
      <div class="bs-left">
        <BrandLockup variant="inline" :size="18" :en="false" />
        <span class="bs-slogan">{{ SLOGAN }}</span>
      </div>
      <div class="bs-mission">
        <span v-for="m in MISSION" :key="m.k" class="bs-m"><span class="bs-m-k">{{ m.k }}</span>{{ m.v }}</span>
      </div>
    </div>

    <OperationsDashboard :workbench="wb" :charts="charts" :loading="loading" :charts-loading="chartsLoading" :failed="failed"
      :updated-at="updatedAt" :months="months" @change-months="changeMonths" @refresh="load" />

    <!-- 老板核心视图：项目真实利润总览（采购投入 ↔ 租赁回款 ↔ 税后净利 ↔ 回本进度） -->
    <el-card v-if="panoramas && panoramas.items.length" shadow="never" class="profit-card">
      <template #header>
        <div class="profit-head">
          <strong>📊 项目真实利润总览（共 {{ panoramas.projectCount }} 个项目）</strong>
          <div v-if="panoramas.costVisible" class="profit-totals">
            <span>总采购投入 <b>{{ money(panoramas.totalPurchase) }}</b></span>
            <span>已付货款 <b>{{ money(panoramas.totalPurchasePaid) }}</b></span>
            <span>累计实收租金 <b class="ok">{{ money(panoramas.totalReceivedRent) }}</b></span>
            <span>当前实际净现金流 <b :class="(panoramas.totalActualNetCashflow ?? 0) >= 0 ? 'ok' : 'warn'">{{ money(panoramas.totalActualNetCashflow) }}</b></span>
            <span>预计全期毛利 <b class="ok">{{ money(panoramas.totalExpectedGrossProfit) }}</b></span>
            <span>估税后净利 <b class="ok">{{ money(panoramas.totalEstimatedAfterTaxProfit) }}</b></span>
          </div>
        </div>
      </template>
      <el-table :data="panoramas.items" size="small" border stripe @row-click="(r: any) => openPanorama(r.contractId)" style="cursor: pointer">
        <el-table-column prop="contractNo" label="合同编号" min-width="155" show-overflow-tooltip />
        <el-table-column prop="customerName" label="承租客户" min-width="135" show-overflow-tooltip />
        <el-table-column label="采购投入" align="right" width="120"><template #default="{ row }">{{ money(row.purchaseTotal) }}</template></el-table-column>
        <el-table-column label="已付/未付" align="right" width="145">
          <template #default="{ row }">
            <template v-if="panoramas.costVisible">
              <div>已付 {{ money(row.purchasePaid) }}</div>
              <div class="muted">未付 {{ money(row.purchaseUnpaid) }}</div>
            </template>
            <span v-else>🔒</span>
          </template>
        </el-table-column>
        <el-table-column label="实收/应收租金" align="right" width="145">
          <template #default="{ row }">
            <div class="ok">{{ money(row.receivedRent) }}</div>
            <div class="muted">/ {{ money(row.plannedCustomerTotal) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="当前净现金流" align="right" width="125">
          <template #default="{ row }">
            <b :class="(row.actualNetCashflow ?? 0) >= 0 ? 'ok' : 'warn'">{{ money(row.actualNetCashflow) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="预计毛利 / 税后净利" align="right" width="150">
          <template #default="{ row }">
            <template v-if="panoramas.costVisible">
              <div class="ok">{{ money(row.expectedGrossProfit) }}</div>
              <div class="muted">税后 {{ money(row.estimatedAfterTaxProfit) }}</div>
            </template>
            <span v-else>🔒</span>
          </template>
        </el-table-column>
        <el-table-column label="回本进度" width="140">
          <template #default="{ row }">
            <template v-if="panoramas.costVisible">
              <el-progress :percentage="Math.round((row.paybackProgress || 0) * 100)" :stroke-width="10" />
              <div class="muted">预计第 {{ row.paybackPeriodNo || '—' }} 期回本</div>
            </template>
            <span v-else>🔒</span>
          </template>
        </el-table-column>
        <el-table-column label="最大垫资峰值" align="right" width="120">
          <template #default="{ row }">{{ money(row.peakCapitalOutlay) }}</template>
        </el-table-column>
        <el-table-column label="详情" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="openPanorama(row.contractId)">全景 →</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <ProjectPanoramaDrawer v-model="panoramaDrawer" :contract-id="panoramaContractId" />
  </div>
</template>

<style scoped>
.wb { max-width: 1480px; margin: 0 auto; padding: 2px 4px 24px; }
.profit-card { margin-top: 18px; border-radius: 10px; }
.profit-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.profit-totals { display: flex; gap: 16px; flex-wrap: wrap; font-size: 13px; color: #606266; }
.ok { color: #2f9e44; }
.warn { color: #e6a23c; }
.muted { color: #909399; font-size: 11.5px; }

/* 品牌条：曜石黑底 + 金字，与标志标准规范的深色版一致；压成一行，不抢数据的位置 */
.brand-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px 28px;
  flex-wrap: wrap;
  padding: 12px 20px;
  margin-bottom: 18px;
  border-radius: 12px;
  background: var(--brand-dark-gradient);
  border: 1px solid var(--brand-gold-line);
}
.bs-left { display: flex; align-items: center; gap: 18px; min-width: 0; }
.bs-slogan {
  color: var(--brand-gold-dim);
  font-size: 12px;
  letter-spacing: 0.22em;
  padding-left: 18px;
  border-left: 1px solid var(--brand-gold-line);
  white-space: nowrap;
}
.bs-mission { display: flex; align-items: center; gap: 22px; }
.bs-m { display: inline-flex; align-items: baseline; gap: 10px; color: var(--brand-gold-bright); font-size: 14px; font-weight: 600; letter-spacing: 0.12em; white-space: nowrap; }
.bs-m + .bs-m { padding-left: 22px; border-left: 1px solid var(--brand-gold-line); }
.bs-m-k { color: var(--brand-gold); font-size: 11.5px; font-weight: 400; letter-spacing: 0.24em; }
@media (max-width: 760px) {
  .brand-strip { padding: 10px 14px; margin-bottom: 14px; }
  .bs-slogan, .bs-mission { display: none; }
}
</style>
