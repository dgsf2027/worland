<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchPurchases, fetchPurchaseDetail, createPurchaseOrder, receivePurchase, returnPurchase,
  type PurchaseListItem, type PurchaseDetail,
} from '@/api/purchase'
import { toTermInputs, checkTermRows, fetchAssets, type TermRow, type AssetListItem } from '@/api/asset'
import { fetchContracts, type ContractListItem } from '@/api/contract'
import PaymentTermsEditor from '@/components/PaymentTermsEditor.vue'

const route = useRoute()
const router = useRouter()

const activeTab = ref('list')
const list = ref<PurchaseListItem[]>([])
const filters = reactive<{ status: string; keyword: string }>({ status: '', keyword: '' })
const statusTag: Record<string, string> = { 已入库: 'success', 已下单: 'warning', 已红冲: 'danger' }
const payTag: Record<string, string> = { 已付: 'success', 待付: 'warning', 红冲: 'danger' }

async function loadList() {
  const params: Record<string, any> = { page: 1, size: 100 }
  if (filters.status) params.status = filters.status
  if (filters.keyword) params.keyword = filters.keyword
  const res = await fetchPurchases(params)
  list.value = res.records
}

// ---- 详情 ----
const detail = ref<PurchaseDetail | null>(null)
async function openDetail(id: number) {
  detail.value = await fetchPurchaseDetail(id)
  activeTab.value = 'detail'
}

// ---- 下单:选合同 → 勾该合同下的台账设备(供应商/付款条件/预计付款金额自动带出) ----
const orderDlg = ref(false)
const oForm = reactive<Record<string, any>>({ no: '', contractId: undefined, remark: '' })
const contracts = ref<ContractListItem[]>([])
const contractsLoading = ref(false)
const candidateAssets = ref<AssetListItem[]>([])
const assetsLoading = ref(false)
const pickedAssets = ref<AssetListItem[]>([])

async function loadContracts() {
  contractsLoading.value = true
  try {
    contracts.value = (await fetchContracts({ page: 1, size: 200 })).records.filter((c) => c.status !== '已作废')
  } finally {
    contractsLoading.value = false
  }
}
async function loadCandidateAssets(contractId?: number) {
  candidateAssets.value = []
  pickedAssets.value = []
  if (!contractId) return
  assetsLoading.value = true
  try {
    candidateAssets.value = (await fetchAssets({ contractId, page: 1, size: 500 })).records
  } finally {
    assetsLoading.value = false
  }
}
const defaultTermRows = (): TermRow[] => [
  { stageName: '首付', ratioPct: 30, triggerPoint: '下单', dueDays: 0 },
  { stageName: '验收', ratioPct: 60, triggerPoint: '入库', dueDays: 0 },
  { stageName: '尾款', ratioPct: 10, triggerPoint: '入库', dueDays: 90 },
]
const orderTermRows = ref<TermRow[]>(defaultTermRows())
/** 预计付款金额合计 = 勾选设备的合同价合计 */
const orderTotal = computed(() => pickedAssets.value.reduce((s, a) => s + Number(a.purchasePrice || 0), 0))
const unavailableTip = (row: AssetListItem) => (row.purchasePrice == null ? '该设备没有合同价，请先在合同清单里填单价' : '')
async function openOrder() {
  Object.assign(oForm, { no: '', contractId: undefined, remark: '' })
  candidateAssets.value = []
  pickedAssets.value = []
  orderTermRows.value = defaultTermRows()
  orderDlg.value = true
  if (!contracts.value.length) await loadContracts()
}
async function submitOrder() {
  if (!oForm.no || !oForm.contractId) { ElMessage.warning('采购单号与合同必填（先签约后采购）'); return }
  if (!pickedAssets.value.length) { ElMessage.warning('请在下方勾选要采购的设备'); return }
  const noPrice = pickedAssets.value.filter((a) => a.purchasePrice == null)
  if (noPrice.length) { ElMessage.warning(`有 ${noPrice.length} 台设备没有合同价，请先在合同清单里填单价`); return }
  const termErr = checkTermRows(orderTermRows.value)
  if (termErr) { ElMessage.warning('付款条件：' + termErr); return }
  try {
    await createPurchaseOrder({
      no: oForm.no,
      contractId: oForm.contractId,
      remark: oForm.remark,
      items: pickedAssets.value.map((a) => ({ assetId: a.id })),
      paymentTerms: toTermInputs(orderTermRows.value),
    })
    ElMessage.success('采购下单成功（已按付款条件逐台生成「下单」阶段应付）')
    orderDlg.value = false
    loadList()
  } catch { /* 无合同拒绝已提示 */ }
}
async function doReceive(row: PurchaseListItem) {
  await ElMessageBox.confirm(`入库将给单上的设备登记入库，并按付款条件逐台生成「入库」阶段应付，确认？`, '采购入库', { type: 'warning' })
  await receivePurchase(row.id)
  ElMessage.success('已入库（设备回写入库留痕 + 应付凭证）')
  loadList()
}
async function doReturn(row: PurchaseListItem) {
  try {
    const r = await ElMessageBox.prompt('退货红冲原因（敏感·供应链/老板）', '退货红冲', { inputPlaceholder: '如到货不符' })
    await returnPurchase(row.id, { reason: r.value })
    ElMessage.success('已退货红冲')
    loadList()
  } catch { /* cancelled or 403 */ }
}
function money(v?: number) { return v == null ? '🔒' : '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }

function goAsset(id?: number) {
  if (id) router.push({ path: '/asset', query: { id: String(id) } })
}
/** 跳收租 · 收租单，按合同过滤 */
function goRent(contractId?: number, contractNo?: string) {
  if (!contractId) return
  router.push({ path: '/rent', query: { contractId: String(contractId), contractNo: contractNo || '' } })
}

// ---- 收租对照：本单货款 vs 这份合同收回来的租金 ----
const cov = computed(() => detail.value?.rentCoverage || null)
/** 覆盖率百分比（后端只给经营角色，GP/LP 为空） */
const coveragePct = computed(() => {
  const r = cov.value?.coverageRatio
  return r == null ? null : Math.round(r * 1000) / 10
})
/** 进度条颜色：有逾期红、已覆盖绿、其余灰蓝 */
const coverageColor = computed(() => {
  if (cov.value?.overdueCount) return '#f56c6c'
  return (coveragePct.value ?? 0) >= 100 ? '#67c23a' : '#409eff'
})
const coverageNote = computed(() => {
  const c = cov.value
  if (!c) return ''
  if (detail.value?.status === '已红冲') return '本单已退货红冲，货款已整单冲销，覆盖率不适用'
  if (c.overdueCount) return `该合同有 ${c.overdueCount} 张收租单逾期，共 ${money(c.overdueAmount)}，先催收再排付款`
  if (coveragePct.value == null) return '租金覆盖率对当前角色不可见（含成本口径）'
  if (coveragePct.value >= 100) return '已收租金已覆盖本单货款'
  return `已收租金覆盖本单货款的 ${coveragePct.value}%，剩余靠后续租期回收`
})

onMounted(() => {
  loadList()
  // 从设备详情「采购单」跳转过来(?id=):直接打开采购详情
  const id = Number(route.query.id)
  if (id) openDetail(id)
})
</script>

<template>
  <div>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
      title="先签约后采购：下单必绑存续合同（无合同拒建单）→ 按合同付款条件逐台生成应付（下单阶段下单即生成、入库阶段入库生成；=负债，进现金流驾驶舱）→ 退货红冲。成本对 GP/LP 打码 🔒。" />
    <el-tabs v-model="activeTab">
      <el-tab-pane label="采购单列表" name="list">
        <div class="bar">
          <el-select v-model="filters.status" placeholder="状态" clearable style="width:120px" @change="loadList">
            <el-option label="已下单" value="已下单" /><el-option label="已入库" value="已入库" /><el-option label="已红冲" value="已红冲" />
          </el-select>
          <el-input v-model="filters.keyword" placeholder="采购单号" style="width:160px" @keyup.enter="loadList" clearable />
          <el-button @click="loadList">查询</el-button>
          <el-button type="primary" @click="openOrder">＋ 采购下单</el-button>
        </div>
        <el-table :data="list" size="small" border>
          <el-table-column prop="no" label="采购单号" width="150" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }"><el-tag size="small" :type="statusTag[row.status] || 'info'">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="contractNo" label="合同" width="130" />
          <el-table-column prop="customerName" label="客户" width="120" />
          <el-table-column prop="supplierName" label="供应商" width="120" />
          <el-table-column label="采购总额" width="120"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
          <el-table-column prop="itemCount" label="件数" width="70" />
          <el-table-column label="待付应付" width="120"><template #default="{ row }">{{ money(row.payableOutstanding) }}</template></el-table-column>
          <el-table-column label="租金回款（本合同）" width="170">
            <template #default="{ row }">
              <span v-if="row.rentCollected != null">已收 {{ money(row.rentCollected) }}</span>
              <span v-else class="muted">—</span>
              <div v-if="row.rentOverdueCount" class="over">逾期 {{ money(row.rentOverdueAmount) }}（{{ row.rentOverdueCount }} 张）</div>
            </template>
          </el-table-column>
          <el-table-column prop="orderDate" label="下单日" width="110" />
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link size="small" @click="openDetail(row.id)">详情</el-button>
              <el-button v-if="row.status === '已下单'" link size="small" type="primary" @click="doReceive(row)">入库</el-button>
              <el-button v-if="row.status !== '已红冲'" link size="small" type="danger" @click="doReturn(row)">退货红冲</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="采购详情" name="detail" :disabled="!detail">
        <template v-if="detail">
          <el-descriptions :column="3" border>
            <el-descriptions-item label="采购单号">{{ detail.no }}</el-descriptions-item>
            <el-descriptions-item label="状态"><el-tag size="small" :type="statusTag[detail.status] || 'info'">{{ detail.status }}</el-tag></el-descriptions-item>
            <el-descriptions-item label="采购总额">{{ money(detail.totalAmount) }}</el-descriptions-item>
            <el-descriptions-item label="合同">{{ detail.contractNo }}</el-descriptions-item>
            <el-descriptions-item label="客户">{{ detail.customerName }}</el-descriptions-item>
            <el-descriptions-item label="供应商">{{ detail.supplierName }}</el-descriptions-item>
            <el-descriptions-item label="待付应付">{{ money(detail.payableOutstanding) }}</el-descriptions-item>
            <el-descriptions-item label="下单日">{{ detail.orderDate }}</el-descriptions-item>
            <el-descriptions-item label="入库日">{{ detail.receiveDate || '—' }}</el-descriptions-item>
          </el-descriptions>

          <template v-if="cov">
            <h4>收租对照（本单货款 ←→ 这份合同收回来的租金）</h4>
            <el-card shadow="never" class="cov">
              <div class="cov-head">
                <span>合同 <b>{{ cov.contractNo || '—' }}</b> · {{ cov.customerName || '—' }} · 在册收租单 {{ cov.billCount ?? 0 }} 张</span>
                <el-button link type="primary" @click="goRent(cov.contractId, cov.contractNo)">查看收租单 →</el-button>
              </div>
              <el-row :gutter="12" class="cov-row">
                <el-col :span="8">
                  <div class="cov-box">
                    <div class="cov-t">本单货款</div>
                    <div class="cov-v">{{ money(cov.purchaseTotal) }}</div>
                    <div class="muted">已付 {{ money(cov.paidAmount) }} · 待付 {{ money(cov.unpaidAmount) }}</div>
                  </div>
                </el-col>
                <el-col :span="8">
                  <div class="cov-box">
                    <div class="cov-t">该合同租金</div>
                    <div class="cov-v ok">已收 {{ money(cov.collectedAmount) }}</div>
                    <div class="muted">
                      待收 {{ money(cov.pendingAmount) }}
                      <span v-if="cov.overdueCount" class="over">· 逾期 {{ money(cov.overdueAmount) }}（{{ cov.overdueCount }} 张）</span>
                    </div>
                  </div>
                </el-col>
                <el-col :span="8">
                  <div class="cov-box">
                    <div class="cov-t">下一期到期</div>
                    <div class="cov-v">{{ cov.nextDueDate || '—' }}</div>
                    <div class="muted">
                      <span v-if="cov.nextDueAmount != null">应收 {{ money(cov.nextDueAmount) }}</span>
                      <span v-else>该合同已无未收租金</span>
                    </div>
                  </div>
                </el-col>
              </el-row>
              <div v-if="coveragePct != null" class="cov-bar">
                <span class="cov-t">租金覆盖率</span>
                <el-progress :percentage="Math.min(coveragePct, 100)" :color="coverageColor" :stroke-width="14"
                  :format="() => coveragePct + '%'" style="flex:1" />
              </div>
              <el-alert v-if="cov.openCaseCount" type="error" :closable="false" show-icon style="margin-top:8px"
                :title="`该合同有 ${cov.openCaseCount} 个逾期案开启中，当前处置步骤：${cov.openCaseStep}`" />
              <div class="muted" style="margin-top:6px">{{ coverageNote }}</div>
            </el-card>
          </template>

          <h4>明细（逐件·入库生成设备）</h4>
          <el-table :data="detail.items" size="small" border>
            <el-table-column label="设备（租赁台账）" min-width="180">
              <template #default="{ row }">
                <a v-if="row.assetId" class="lnk" @click="goAsset(row.assetId)">{{ row.assetLabel || ('#' + row.assetId) }}</a>
                <span v-else>{{ row.assetLabel || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="supplierName" label="供应商" min-width="130"><template #default="{ row }">{{ row.supplierName || '—' }}</template></el-table-column>
            <el-table-column label="付款条件" min-width="240"><template #default="{ row }">{{ row.paymentTerms || '—' }}</template></el-table-column>
            <el-table-column label="预计付款金额" width="130"><template #default="{ row }">{{ money(row.expectedAmount) }}</template></el-table-column>
            <el-table-column label="已生成应付" width="130"><template #default="{ row }">{{ money(row.payableAmount) }}<div v-if="row.payableOutstanding" class="muted">待付 {{ money(row.payableOutstanding) }}</div></template></el-table-column>
            <el-table-column label="台账状态" width="110">
              <template #default="{ row }"><span v-if="row.assetId">#{{ row.assetId }} · {{ row.assetStatus }}</span><span v-else>未入库</span></template>
            </el-table-column>
          </el-table>

          <h4>应付计划（按设备付款条件逐台生成 = 负债）</h4>
          <el-table :data="detail.payables" size="small" border>
            <el-table-column label="设备（租赁台账）" min-width="170">
              <template #default="{ row }">
                <a v-if="row.assetId" class="lnk" @click="goAsset(row.assetId)">{{ row.assetLabel || row.serialNo || ('#' + row.assetId) }}</a>
                <span v-else class="muted">整单</span>
              </template>
            </el-table-column>
            <el-table-column prop="supplierName" label="供应商" min-width="120"><template #default="{ row }">{{ row.supplierName || '—' }}</template></el-table-column>
            <el-table-column prop="stage" label="付款阶段" width="100" />
            <el-table-column prop="dueDate" label="到期日" width="120" />
            <el-table-column label="预计付款金额" width="130"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }"><el-tag size="small" :type="payTag[row.status] || 'info'">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="160" />
          </el-table>
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- 下单弹窗 -->
    <el-dialog v-model="orderDlg" title="采购下单（先签约后采购）" width="800px">
      <el-form :inline="true">
        <el-form-item label="采购单号"><el-input v-model="oForm.no" placeholder="CG-2026-xxx" /></el-form-item>
        <el-form-item label="合同">
          <el-select v-model="oForm.contractId" filterable clearable style="width:280px" :loading="contractsLoading"
            placeholder="选合同（先签约后采购）" @change="loadCandidateAssets">
            <el-option v-for="c in contracts" :key="c.id" :label="`${c.no} · ${c.customerName || ''} · ${c.status}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="oForm.remark" style="width:200px" /></el-form-item>
      </el-form>
      <h4>勾选设备（该合同下的设备租赁台账；供应商、付款条件、预计付款金额自动带出）</h4>
      <el-table :data="candidateAssets" v-loading="assetsLoading" size="small" border max-height="300"
        @selection-change="(v: AssetListItem[]) => (pickedAssets = v)">
        <el-table-column type="selection" width="40" :selectable="(row: AssetListItem) => row.purchasePrice != null" />
        <el-table-column label="设备" min-width="170">
          <template #default="{ row }">
            {{ row.category }}{{ row.model ? ' · ' + row.model : '' }}
            <div class="muted">{{ row.serialNo }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="120"><template #default="{ row }">{{ row.supplierName || '—' }}</template></el-table-column>
        <el-table-column label="预计付款金额" width="130">
          <template #default="{ row }">
            {{ money(row.purchasePrice) }}
            <div v-if="unavailableTip(row)" class="muted">{{ unavailableTip(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="台账状态" width="100" />
      </el-table>
      <div v-if="oForm.contractId && !candidateAssets.length && !assetsLoading" class="muted">
        该合同下还没有设备：请先在「合同 · 签约与租金计划」的合同清单里按数量一键生成设备。
      </div>
      <h4>合同付款条件（整单默认，逐台按各自合同价 × 比例生成应付）</h4>
      <PaymentTermsEditor v-model="orderTermRows" :base-price="orderTotal || null" />
      <div class="muted">
        已勾 {{ pickedAssets.length }} 台，预计付款合计 {{ money(orderTotal) }}。
        设备详情里单独设过付款条件的，按设备自己的条件走；其余用这里的整单条件。
      </div>
      <template #footer><el-button @click="orderDlg = false">取消</el-button><el-button type="primary" @click="submitOrder">下单</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { margin-bottom: 12px; display: flex; gap: 8px; align-items: center; }
h4 { margin: 16px 0 8px; }
.lnk { color: #2e6da4; cursor: pointer; font-weight: 600; }
.lnk:hover { text-decoration: underline; }
.muted { color: #909399; font-size: 12px; }
.over { color: #f56c6c; font-size: 12px; }
.cov { background: #fafcff; }
.cov-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-size: 13px; }
.cov-row { margin-bottom: 4px; }
.cov-box { padding: 8px 10px; background: #fff; border: 1px solid #ebeef5; border-radius: 4px; }
.cov-t { color: #909399; font-size: 12px; }
.cov-v { font-size: 18px; font-weight: 600; margin: 2px 0; }
.cov-v.ok { color: #67c23a; }
.cov-bar { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
</style>
