<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchPurchases, fetchPurchaseDetail, createPurchaseOrder, receivePurchase, returnPurchase,
  type PurchaseListItem, type PurchaseDetail,
} from '@/api/purchase'

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

// ---- 下单 ----
const orderDlg = ref(false)
const oForm = reactive<Record<string, any>>({ no: '', contractId: undefined, supplierId: undefined, items: [] })
function openOrder() {
  Object.assign(oForm, { no: '', contractId: undefined, supplierId: undefined, items: [{ serialNo: '', category: '货架', model: '', marketPrice: undefined, purchasePrice: undefined }] })
  orderDlg.value = true
}
function addItem() { oForm.items.push({ serialNo: '', category: '货架', model: '', marketPrice: undefined, purchasePrice: undefined }) }
function removeItem(i: number) { oForm.items.splice(i, 1) }
async function submitOrder() {
  if (!oForm.no || !oForm.contractId) { ElMessage.warning('单号与合同ID必填（先签约后采购）'); return }
  if (!oForm.items.length || oForm.items.some((it: any) => !it.serialNo || !it.category)) { ElMessage.warning('每件需序列号+品类'); return }
  try {
    await createPurchaseOrder({ ...oForm })
    ElMessage.success('采购下单成功（已生成首付应付）')
    orderDlg.value = false
    loadList()
  } catch { /* 无合同拒绝已提示 */ }
}
async function doReceive(row: PurchaseListItem) {
  await ElMessageBox.confirm(`入库将逐件生成设备并生成验收/尾款应付，确认？`, '采购入库', { type: 'warning' })
  await receivePurchase(row.id)
  ElMessage.success('已入库（逐件生成设备+应付凭证）')
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

onMounted(loadList)
</script>

<template>
  <div>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
      title="先签约后采购：下单必绑存续合同（无合同拒建单）→ 入库逐件生成设备 + 首付/验收/尾款应付（=负债，进现金流驾驶舱）→ 退货红冲。成本对 GP/LP 打码 🔒。" />
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

          <h4>明细（逐件·入库生成设备）</h4>
          <el-table :data="detail.items" size="small" border>
            <el-table-column prop="serialNo" label="序列号" width="120" />
            <el-table-column prop="category" label="品类" width="90" />
            <el-table-column prop="model" label="型号" min-width="120" />
            <el-table-column label="市场价" width="110"><template #default="{ row }">{{ money(row.marketPrice) }}</template></el-table-column>
            <el-table-column label="集采价" width="110"><template #default="{ row }">{{ money(row.purchasePrice) }}</template></el-table-column>
            <el-table-column label="设备" width="130">
              <template #default="{ row }"><span v-if="row.assetId">#{{ row.assetId }} · {{ row.assetStatus }}</span><span v-else>未入库</span></template>
            </el-table-column>
          </el-table>

          <h4>应付计划（首付/验收/尾款 = 负债）</h4>
          <el-table :data="detail.payables" size="small" border>
            <el-table-column prop="stage" label="阶段" width="100" />
            <el-table-column prop="dueDate" label="到期日" width="120" />
            <el-table-column label="金额" width="130"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }"><el-tag size="small" :type="payTag[row.status] || 'info'">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="160" />
          </el-table>
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- 下单弹窗 -->
    <el-dialog v-model="orderDlg" title="采购下单（先签约后采购）" width="720px">
      <el-form :inline="true">
        <el-form-item label="采购单号"><el-input v-model="oForm.no" placeholder="CG-2026-xxx" /></el-form-item>
        <el-form-item label="合同ID"><el-input-number v-model="oForm.contractId" :min="1" /></el-form-item>
        <el-form-item label="供应商ID"><el-input-number v-model="oForm.supplierId" :min="1" /></el-form-item>
      </el-form>
      <el-table :data="oForm.items" size="small" border>
        <el-table-column label="序列号"><template #default="{ row }"><el-input v-model="row.serialNo" size="small" /></template></el-table-column>
        <el-table-column label="品类" width="110"><template #default="{ row }"><el-input v-model="row.category" size="small" /></template></el-table-column>
        <el-table-column label="型号"><template #default="{ row }"><el-input v-model="row.model" size="small" /></template></el-table-column>
        <el-table-column label="市场价" width="120"><template #default="{ row }"><el-input-number v-model="row.marketPrice" size="small" :controls="false" style="width:100%" /></template></el-table-column>
        <el-table-column label="集采价" width="120"><template #default="{ row }"><el-input-number v-model="row.purchasePrice" size="small" :controls="false" style="width:100%" /></template></el-table-column>
        <el-table-column label="操作" width="60"><template #default="{ $index }"><el-button link type="danger" size="small" @click="removeItem($index)">删</el-button></template></el-table-column>
      </el-table>
      <el-button size="small" style="margin-top:8px" @click="addItem">＋ 加一件</el-button>
      <template #footer><el-button @click="orderDlg = false">取消</el-button><el-button type="primary" @click="submitOrder">下单</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { margin-bottom: 12px; display: flex; gap: 8px; align-items: center; }
h4 { margin: 16px 0 8px; }
</style>
