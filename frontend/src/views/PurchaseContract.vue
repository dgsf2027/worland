<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchSupplierPool } from '@/api/supplier'
import {
  addPcInvoice, createPurchaseContract, fetchPurchaseContract, fetchPurchaseContracts,
  payPurchaseContract, voidPcInvoice, voidPcPayment,
  type PcContract, type PcDetail,
} from '@/api/purchaseContract'
import ProofUploader from '@/components/payproof/ProofUploader.vue'
import ProofViewer from '@/components/payproof/ProofViewer.vue'

/** 采购合同:合同(含付款节点)→ 付款(银行流水+回单,可跨节点)→ 进项发票,三者互相对账。 */
const list = ref<PcContract[]>([])
const loading = ref(false)
const suppliers = ref<{ id: number; name: string }[]>([])

function money(v?: number) {
  return v == null ? '-' : '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
const statusType: Record<string, string> = { 执行中: 'warning', 已结清: 'success', 作废: 'info' }

async function load() {
  loading.value = true
  try { list.value = await fetchPurchaseContracts() } finally { loading.value = false }
}
onMounted(async () => {
  await load()
  suppliers.value = (await fetchSupplierPool({ page: 1, size: 500 })).records.map((x) => ({ id: x.id, name: x.name }))
})

// ---- 新建合同 ----
const createDlg = ref(false)
const creating = ref(false)
const form = reactive({
  contractNo: '', supplierId: undefined as number | undefined, totalAmount: undefined as number | undefined,
  taxPct: 13, signedDate: '', remark: '',
  terms: [{ stageName: '首付', amount: undefined as number | undefined, conditionText: '' }],
})
const termSum = computed(() => form.terms.reduce((s, t) => s + (t.amount || 0), 0))
function openCreate() {
  Object.assign(form, { contractNo: '', supplierId: undefined, totalAmount: undefined, taxPct: 13, signedDate: '', remark: '',
    terms: [{ stageName: '首付', amount: undefined, conditionText: '' }] })
  createDlg.value = true
}
async function submitCreate() {
  creating.value = true
  try {
    await createPurchaseContract({
      contractNo: form.contractNo, supplierId: form.supplierId, totalAmount: form.totalAmount,
      taxRate: form.taxPct / 100, signedDate: form.signedDate || undefined, remark: form.remark || undefined,
      terms: form.terms.map((t) => ({ stageName: t.stageName, amount: t.amount, conditionText: t.conditionText || undefined })),
    })
    ElMessage.success('采购合同已创建')
    createDlg.value = false
    await load()
  } finally { creating.value = false }
}

// ---- 详情 ----
const drawer = ref(false)
const detail = ref<PcDetail>()
async function openDetail(id: number) {
  detail.value = await fetchPurchaseContract(id)
  drawer.value = true
}
async function reloadDetail() {
  if (detail.value) detail.value = await fetchPurchaseContract(detail.value.contract.id)
  await load()
}
function termName(id: number) { return detail.value?.terms.find((t) => t.id === id)?.stageName ?? '' }

// ---- 登记付款 ----
const payDlg = ref(false)
const paying = ref(false)
const payForm = reactive({ amount: undefined as number | undefined, paidDate: '', bankSerial: '', remark: '', proofFileIds: [] as number[] })
function openPay() {
  Object.assign(payForm, { amount: undefined, paidDate: new Date().toISOString().slice(0, 10), bankSerial: '', remark: '', proofFileIds: [] })
  payDlg.value = true
}
async function submitPay() {
  paying.value = true
  try {
    await payPurchaseContract(detail.value!.contract.id, {
      amount: payForm.amount, paidDate: payForm.paidDate, bankSerial: payForm.bankSerial || undefined,
      remark: payForm.remark || undefined, proofFileIds: payForm.proofFileIds,
    })
    ElMessage.success('付款已登记并生成凭证')
    payDlg.value = false
    await reloadDetail()
  } finally { paying.value = false }
}
async function undoPay(id: number) {
  await ElMessageBox.confirm('撤销该笔付款？会红冲对应付款凭证，核销的节点额度随之释放。', '撤销付款', { type: 'warning' })
  await voidPcPayment(id, '页面撤销')
  ElMessage.success('已撤销')
  await reloadDetail()
}
const proofView = reactive({ show: false, id: 0, title: '' })
function viewProof(id: number) { Object.assign(proofView, { show: true, id, title: `付款回单 #${id}` }) }

// ---- 登记收票 ----
const invDlg = ref(false)
const invSaving = ref(false)
const invForm = reactive({ invoiceNo: '', invoiceType: '专票', issueDate: '', sellerName: '', amountExTax: undefined as number | undefined, taxAmount: undefined as number | undefined, remark: '' })
const invTotal = computed(() => Math.round(((invForm.amountExTax || 0) + (invForm.taxAmount || 0)) * 100) / 100)
function openInvoice() {
  Object.assign(invForm, { invoiceNo: '', invoiceType: '专票', issueDate: '', sellerName: detail.value?.contract.supplierName || '', amountExTax: undefined, taxAmount: undefined, remark: '' })
  invDlg.value = true
}
async function submitInvoice() {
  invSaving.value = true
  try {
    await addPcInvoice(detail.value!.contract.id, {
      invoiceNo: invForm.invoiceNo, invoiceType: invForm.invoiceType, issueDate: invForm.issueDate,
      sellerName: invForm.sellerName || undefined, amountExTax: invForm.amountExTax, taxAmount: invForm.taxAmount,
      totalAmount: invTotal.value, remark: invForm.remark || undefined,
    })
    ElMessage.success('进项发票已登记')
    invDlg.value = false
    await reloadDetail()
  } finally { invSaving.value = false }
}
async function undoInvoice(id: number) {
  await ElMessageBox.confirm('作废该发票？', '作废发票', { type: 'warning' })
  await voidPcInvoice(id, '页面作废')
  ElMessage.success('已作废')
  await reloadDetail()
}
</script>

<template>
  <div class="page">
    <div class="head">
      <h3>采购合同 · 付款 · 进项发票</h3>
      <el-button type="primary" @click="openCreate">＋ 新建采购合同</el-button>
    </div>
    <el-alert type="info" :closable="false" show-icon class="tip"
      title="合同、付款、发票三者对账：付款与收票都不能超过合同含税总价；一笔银行付款可同时核销多个付款节点；付款必须带回单并自动生成付款凭证。" />
    <el-table :data="list" v-loading="loading" stripe border size="small" @row-click="(r: PcContract) => openDetail(r.id)">
      <el-table-column prop="contractNo" label="合同号" min-width="170" />
      <el-table-column prop="supplierName" label="供应商" min-width="150" />
      <el-table-column label="含税总价" align="right" width="130"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
      <el-table-column label="已付" align="right" width="130"><template #default="{ row }">{{ money(row.summary.paid) }}</template></el-table-column>
      <el-table-column label="已收票" align="right" width="130"><template #default="{ row }">{{ money(row.summary.invoiced) }}</template></el-table-column>
      <el-table-column label="未付" align="right" width="130"><template #default="{ row }">{{ money(row.summary.unpaid) }}</template></el-table-column>
      <el-table-column label="已付未收票" align="right" width="130">
        <template #default="{ row }"><span :class="{ warn: row.summary.paidNotInvoiced > 0 }">{{ money(row.summary.paidNotInvoiced) }}</span></template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }"><el-tag :type="(statusType[row.status] as any) || 'info'" size="small">{{ row.status }}</el-tag></template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawer" size="860px" :title="detail ? `${detail.contract.contractNo} · ${detail.contract.supplierName || ''}` : ''">
      <template v-if="detail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="含税总价">{{ money(detail.contract.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="税率">{{ (detail.contract.taxRate * 100).toFixed(0) }}%</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.contract.status }}</el-descriptions-item>
          <el-descriptions-item label="已付">{{ money(detail.contract.summary.paid) }}</el-descriptions-item>
          <el-descriptions-item label="已收票">{{ money(detail.contract.summary.invoiced) }}</el-descriptions-item>
          <el-descriptions-item label="已付未收票">{{ money(detail.contract.summary.paidNotInvoiced) }}</el-descriptions-item>
        </el-descriptions>

        <h4>付款节点</h4>
        <el-table :data="detail.terms" size="small" border>
          <el-table-column prop="stageName" label="节点" width="120" />
          <el-table-column label="应付" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
          <el-table-column label="已核销" align="right"><template #default="{ row }">{{ money(row.paid) }}</template></el-table-column>
          <el-table-column label="未付" align="right"><template #default="{ row }">{{ money(row.unpaid) }}</template></el-table-column>
          <el-table-column prop="conditionText" label="触发条件" />
        </el-table>

        <div class="sec"><h4>付款记录</h4><el-button size="small" type="primary" @click="openPay">登记付款</el-button></div>
        <el-table :data="detail.payments" size="small" border>
          <el-table-column prop="paidDate" label="日期" width="110" />
          <el-table-column label="金额" align="right" width="130"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
          <el-table-column prop="bankSerial" label="银行流水号" min-width="130" />
          <el-table-column label="核销节点" min-width="160">
            <template #default="{ row }">{{ row.allocs.map((a: any) => `${termName(a.termId)} ${money(a.amount)}`).join('；') }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="70" />
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button link size="small" @click="viewProof(row.id)">回单({{ row.proofCount }})</el-button>
              <el-button v-if="row.status === '有效'" link type="danger" size="small" @click="undoPay(row.id)">撤销</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="sec"><h4>进项发票</h4><el-button size="small" type="primary" @click="openInvoice">登记收票</el-button></div>
        <el-table :data="detail.invoices" size="small" border>
          <el-table-column prop="invoiceNo" label="发票号码" min-width="190" />
          <el-table-column prop="invoiceType" label="类型" width="70" />
          <el-table-column prop="issueDate" label="开票日期" width="110" />
          <el-table-column label="不含税" align="right" width="120"><template #default="{ row }">{{ money(row.amountExTax) }}</template></el-table-column>
          <el-table-column label="税额" align="right" width="110"><template #default="{ row }">{{ money(row.taxAmount) }}</template></el-table-column>
          <el-table-column label="价税合计" align="right" width="120"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
          <el-table-column prop="status" label="状态" width="70" />
          <el-table-column label="操作" width="70">
            <template #default="{ row }"><el-button v-if="row.status === '有效'" link type="danger" size="small" @click="undoInvoice(row.id)">作废</el-button></template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <ProofViewer v-model="proofView.show" biz-type="supplier_payment" :biz-id="proofView.id" :title="proofView.title" :can-supplement="false" />

    <el-dialog v-model="createDlg" title="新建采购合同" width="640px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="合同号" required><el-input v-model="form.contractNo" /></el-form-item>
        <el-form-item label="供应商" required>
          <el-select v-model="form.supplierId" filterable style="width: 100%">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="含税总价" required><el-input-number v-model="form.totalAmount" :min="0" :precision="2" :controls="false" style="width: 200px" /></el-form-item>
        <el-form-item label="税率(%)"><el-input-number v-model="form.taxPct" :min="0" :max="100" :controls="false" /></el-form-item>
        <el-form-item label="签订日期"><el-date-picker v-model="form.signedDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="付款节点" required>
          <div class="terms">
            <div v-for="(t, i) in form.terms" :key="i" class="term-row">
              <el-input v-model="t.stageName" placeholder="节点名" style="width: 110px" />
              <el-input-number v-model="t.amount" :min="0" :precision="2" :controls="false" placeholder="金额" style="width: 150px" />
              <el-input v-model="t.conditionText" placeholder="触发条件(可选)" style="width: 170px" />
              <el-button link type="danger" :disabled="form.terms.length === 1" @click="form.terms.splice(i, 1)">删除</el-button>
            </div>
            <el-button size="small" @click="form.terms.push({ stageName: '', amount: undefined, conditionText: '' })">＋ 加节点</el-button>
            <span class="sum" :class="{ bad: form.totalAmount != null && Math.abs(termSum - form.totalAmount) > 0.01 }">节点合计 {{ money(termSum) }}</span>
          </div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDlg = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="payDlg" title="登记付款" width="560px">
      <el-form :model="payForm" label-width="100px">
        <el-form-item label="付款金额" required><el-input-number v-model="payForm.amount" :min="0" :precision="2" :controls="false" style="width: 200px" /></el-form-item>
        <el-form-item label="付款日期" required><el-date-picker v-model="payForm.paidDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="银行流水号"><el-input v-model="payForm.bankSerial" placeholder="回单上的交易流水号，不可重复" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="payForm.remark" /></el-form-item>
        <el-form-item label="付款回单" required><ProofUploader v-model="payForm.proofFileIds" /></el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon title="按节点顺序自动核销：先冲满第一个未付清的节点，余额冲下一个。" />
      <template #footer>
        <el-button @click="payDlg = false">取消</el-button>
        <el-button type="primary" :loading="paying" :disabled="!payForm.proofFileIds.length || !payForm.amount" @click="submitPay">确认登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="invDlg" title="登记进项发票" width="560px">
      <el-form :model="invForm" label-width="100px">
        <el-form-item label="发票号码" required><el-input v-model="invForm.invoiceNo" /></el-form-item>
        <el-form-item label="类型"><el-radio-group v-model="invForm.invoiceType"><el-radio value="专票">专票</el-radio><el-radio value="普通">普通</el-radio></el-radio-group></el-form-item>
        <el-form-item label="开票日期" required><el-date-picker v-model="invForm.issueDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="销方"><el-input v-model="invForm.sellerName" /></el-form-item>
        <el-form-item label="不含税金额" required><el-input-number v-model="invForm.amountExTax" :min="0" :precision="2" :controls="false" style="width: 200px" /></el-form-item>
        <el-form-item label="税额" required><el-input-number v-model="invForm.taxAmount" :min="0" :precision="2" :controls="false" style="width: 200px" /></el-form-item>
        <el-form-item label="价税合计">{{ money(invTotal) }}</el-form-item>
        <el-form-item label="备注"><el-input v-model="invForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="invDlg = false">取消</el-button>
        <el-button type="primary" :loading="invSaving" :disabled="!invForm.invoiceNo || !invForm.issueDate || !invTotal" @click="submitInvoice">确认登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 16px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.head h3 { margin: 0; }
.tip { margin-bottom: 12px; }
.sec { display: flex; justify-content: space-between; align-items: center; margin-top: 16px; }
h4 { margin: 16px 0 8px; }
.warn { color: #e6a23c; font-weight: 600; }
.terms { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.term-row { display: flex; gap: 6px; align-items: center; }
.sum { font-size: 12px; color: #909399; }
.sum.bad { color: #f56c6c; }
:deep(.el-table__row) { cursor: pointer; }
</style>
