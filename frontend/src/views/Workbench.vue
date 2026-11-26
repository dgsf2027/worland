<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import BrandLockup from '@/components/BrandLockup.vue'
import OperationsDashboard from '@/components/OperationsDashboard.vue'
import ProjectPanoramaDrawer from '@/components/ProjectPanoramaDrawer.vue'
import { useDashboardData } from '@/api/dashboard'
import {
  cleanStaleTasks, fetchCashWaterline, fetchDataHealth, fetchWorkbench, saveCashBalance,
  type CashWaterline, type DataHealth, type Workbench,
} from '@/api/workbench'
import { fetchWorkbenchProjectPanoramas, type ProjectPanoramaOverview } from '@/api/contract'
import { sessionRole } from '@/utils/session'

const router = useRouter()
const SLOGAN = '淬炼于火 · 锋利如曜'

const wb = ref<Workbench | null>(null)
const panoramas = ref<ProjectPanoramaOverview | null>(null)
const waterline = ref<CashWaterline | null>(null)
const panoramaDrawer = ref(false)
const panoramaContractId = ref<number | null>(null)
const loading = ref(false)
const chartsLoading = ref(false)
const waterlineLoading = ref(false)
const failed = ref(false)
const updatedAt = ref('')
const months = ref(6)
const dashboard = useDashboardData()
const charts = dashboard.charts

const canManageFinance = computed(() => ['老板', '财务'].includes(sessionRole.value || localStorage.getItem('rent_user_role') || ''))

const now = () => new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })

function openPanorama(contractId: number) {
  panoramaContractId.value = contractId
  panoramaDrawer.value = true
}

/** 并行加载工作台、图表、项目全景与资金水位线，任一源失败互不拖垮。 */
async function load() {
  if (loading.value) return
  loading.value = true
  chartsLoading.value = true
  waterlineLoading.value = true
  failed.value = false
  const [result, , panoRes, wlRes] = await Promise.allSettled([
    fetchWorkbench(),
    dashboard.load(months.value),
    fetchWorkbenchProjectPanoramas(),
    fetchCashWaterline(months.value),
  ])
  if (result.status === 'fulfilled') {
    wb.value = result.value
    updatedAt.value = now()
  } else {
    wb.value = null
    failed.value = true
  }
  if (panoRes.status === 'fulfilled') panoramas.value = panoRes.value
  if (wlRes.status === 'fulfilled') waterline.value = wlRes.value
  loading.value = false
  chartsLoading.value = false
  waterlineLoading.value = false
}

async function changeMonths(value: number) {
  if (chartsLoading.value) return
  months.value = value
  chartsLoading.value = true
  waterlineLoading.value = true
  const [, wlRes] = await Promise.allSettled([
    dashboard.load(value),
    fetchCashWaterline(value),
  ])
  if (wlRes.status === 'fulfilled') waterline.value = wlRes.value
  chartsLoading.value = false
  waterlineLoading.value = false
}

async function onSimulateBalance(balance: number | null) {
  waterlineLoading.value = true
  try {
    waterline.value = await fetchCashWaterline(months.value, balance)
    ElMessage.success('已按试算余额重算资金水位线')
  } finally {
    waterlineLoading.value = false
  }
}

async function onSaveBalance(balance: number) {
  waterlineLoading.value = true
  try {
    await saveCashBalance(balance, '驾驶舱更新银行可用余额')
    waterline.value = await fetchCashWaterline(months.value)
    ElMessage.success('银行可用余额已保存，水位线已更新')
  } finally {
    waterlineLoading.value = false
  }
}

// ---- 数据体检 & 孤儿系统任务一键清理 ----
const healthDlg = ref(false)
const healthLoading = ref(false)
const healthCleaning = ref(false)
const healthData = ref<DataHealth | null>(null)

async function openHealth() {
  healthDlg.value = true
  healthLoading.value = true
  try {
    healthData.value = await fetchDataHealth()
  } finally {
    healthLoading.value = false
  }
}

async function doCleanStaleTasks() {
  if (!healthData.value?.staleTaskCount) return
  try {
    await ElMessageBox.confirm(
      `将把 ${healthData.value.staleTaskCount} 条来源单据已关闭/结清/删除的历史系统任务置为「已作废」，彻底消除假待办与假红灯。确认清理？`,
      '一键清理孤儿系统任务',
      { type: 'warning', confirmButtonText: '确认清理' },
    )
  } catch {
    return
  }
  healthCleaning.value = true
  try {
    const res = await cleanStaleTasks()
    ElMessage.success(`已清理 ${res.cleanedCount} 条历史无效系统任务`)
    healthData.value = await fetchDataHealth()
    load()
  } finally {
    healthCleaning.value = false
  }
}

function goHealthLink(link: string) {
  healthDlg.value = false
  if (link) router.push(link)
}

onMounted(load)
defineExpose({ load })
</script>

<template>
  <div class="wb">
    <!-- 紧凑品牌条（不占用过多首屏高度） -->
    <div class="brand-strip">
      <BrandLockup variant="inline" :size="15" :en="false" />
      <span class="bs-slogan">{{ SLOGAN }}</span>
      <span class="bs-mission">愿景：科技赋能云仓 · 使命：为云仓提效降本</span>
    </div>

    <OperationsDashboard
      :workbench="wb"
      :panoramas="panoramas"
      :waterline="waterline"
      :charts="charts"
      :loading="loading"
      :charts-loading="chartsLoading"
      :waterline-loading="waterlineLoading"
      :failed="failed"
      :updated-at="updatedAt"
      :months="months"
      :can-manage-finance="canManageFinance"
      @change-months="changeMonths"
      @refresh="load"
      @open-panorama="openPanorama"
      @simulate-balance="onSimulateBalance"
      @save-balance="onSaveBalance"
      @open-health="openHealth"
    />

    <ProjectPanoramaDrawer v-model="panoramaDrawer" :contract-id="panoramaContractId" />

    <!-- 数据体检弹窗 -->
    <el-dialog v-model="healthDlg" title="🩺 经营数据体检与清理" width="660px">
      <div v-loading="healthLoading">
        <template v-if="healthData">
          <el-alert
            :type="healthData.healthy ? 'success' : 'warning'"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
            :title="healthData.healthy ? '体检通过：未发现残留孤儿任务、真实逾期或未绑定主合同的采购单据。' : '发现以下待核查/可清理项，请按建议处理：'"
          />
          <el-table :data="healthData.items" size="small" border>
            <el-table-column prop="label" label="检查项" min-width="170" />
            <el-table-column label="数量" width="80" align="center">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="row.level === 'critical' ? 'danger' : (row.level === 'warn' ? 'warning' : 'success')"
                >
                  {{ row.count }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="suggestion" label="说明与建议" min-width="240" />
            <el-table-column label="前往" width="75" align="center">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="goHealthLink(row.link)">查看</el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
      <template #footer>
        <el-button @click="healthDlg = false">关闭</el-button>
        <el-button
          v-if="healthData && healthData.staleTaskCount > 0"
          type="warning"
          :loading="healthCleaning"
          @click="doCleanStaleTasks"
        >
          一键清理 {{ healthData.staleTaskCount }} 条孤儿系统任务
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.wb { max-width: 1480px; margin: 0 auto; padding: 0 4px 20px; }
.brand-strip {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 7px 16px;
  margin-bottom: 12px;
  border-radius: 10px;
  background: var(--brand-dark-gradient);
  border: 1px solid var(--brand-gold-line);
}
.bs-slogan {
  color: var(--brand-gold-dim);
  font-size: 11.5px;
  letter-spacing: 0.18em;
  padding-left: 14px;
  border-left: 1px solid var(--brand-gold-line);
}
.bs-mission {
  margin-left: auto;
  color: var(--brand-gold-bright);
  font-size: 12px;
  letter-spacing: 0.08em;
}
@media (max-width: 760px) {
  .bs-slogan, .bs-mission { display: none; }
}
</style>
