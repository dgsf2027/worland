<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchSupplierPool } from '@/api/supplier'
import { fetchPurchases, type PurchaseListItem } from '@/api/purchase'
import { fetchContracts, type ContractListItem } from '@/api/contract'
import {
  addPcInvoice, bindPcRentContract, createPurchaseContract, fetchPurchaseContract, fetchPurchaseContracts,
  fetchRecognizeStatus, linkPcPurchases, payPurchaseContractBatch, recordPcAcceptance, unlinkPcPurchase,
  voidPcInvoice, voidPcPayment,
  type PcContract, type PcDetail, type RecognizedContract, type RecognizedInvoice,
} from '@/api/purchaseContract'
import ProofUploader from '@/components/payproof/ProofUploader.vue'
import ProofViewer from '@/components/payproof/ProofViewer.vue'
import SnapRecognize from '@/components/payproof/SnapRecognize.vue'
import ReceiptPaymentEditor from '@/components/payproof/ReceiptPaymentEditor.vue'
import { receiptPaymentBody, receiptPaymentError, type ReceiptPaymentDraft } from '@/utils/receiptPayments'

/** 采购合同:合同(含付款节点)→ 付款(银行流水+回单,可跨节点)→ 进项发票,三者互相对账。 */
const list = ref<PcContract[]>([])
const loading = ref(false)
const suppliers = ref<{ id: number; name: string }[]>([])
const rentContracts = ref<ContractListItem[]>([])
/** 图片识别服务是否已配置(null=查询中)；未配置时弹窗顶部提前提示，不必等上传后才失败 */
const recognizeOk = ref<boolean | null>(null)

function money(v?: number) {
  return v == null ? '-' : '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
const statusType: Record<string, string> = { 执行中: 'warning', 已结清: 'success', 作废: 'info' }

async function load() {
  loading.value = true
  try { list.value = await fetchPurchaseContracts() } finally { loading.value = false }
}
const route = useRoute()
onMounted(async () => {
  await load()
  suppliers.value = (await fetchSupplierPool({ page: 1, size: 500 })).records.map((x) => ({ id: x.id, name: x.name }))
  rentContracts.value = (await fetchContracts({ page: 1, size: 500 })).records
  fetchRecognizeStatus().then((r) => { recognizeOk.value = r.configured }).catch(() => { recognizeOk.value = null })
  const id = Number(route.query.id)
  if (id) openDetail(id)
})

// ---- 新建合同 ----
interface TermRow {
  stageName: string; ratioPct?: number; amount?: number; manual: boolean; conditionText: string
  triggerPoint?: string; dueRule?: string; dueDays?: number; dueDayOfMonth?: number
}
const createDlg = ref(false)
const creating = ref(false)
const uploaderKey = ref(0)
const contractUploader = ref<InstanceType<typeof ProofUploader>>()
const form = reactive({
  contractNo: '', supplierId: undefined as number | undefined, rentContractId: undefined as number | undefined,
  detailSubtotal: undefined as number | undefined,
  discountAmount: 0 as number | undefined,
  totalAmount: undefined as number | undefined,
  taxPct: 13, signedDate: '', warrantyYears: 5 as number | undefined,
  deliveryAddress: '', deliveryContact: '',
  remark: '', proofFileIds: [] as number[],
  terms: [] as TermRow[],
})
const round2 = (v: number) => Math.round(v * 100) / 100
const newTerm = (name = ''): TermRow => ({
  stageName: name, ratioPct: undefined, amount: undefined, manual: false, conditionText: '',
  triggerPoint: '审核通过', dueRule: 'after_days', dueDays: 0, dueDayOfMonth: undefined,
})

function onSubtotalOrDiscountChange() {
  if (form.detailSubtotal != null) {
    form.totalAmount = round2(Math.max(0, form.detailSubtotal - (form.discountAmount || 0)))
  }
}
function applyMonthlyTermPreset() {
  form.terms = [
    { stageName: '首笔验收款', ratioPct: 40, amount: undefined, manual: false, triggerPoint: '审核通过', dueRule: 'month_end_days', dueDays: 30, dueDayOfMonth: undefined, conditionText: '验收且资料审核通过后当月月底+30天' },
    { stageName: '次月进度款', ratioPct: 30, amount: undefined, manual: false, triggerPoint: '审核通过', dueRule: 'next_month_day', dueDays: 0, dueDayOfMonth: 30, conditionText: '次月结清30%' },
    { stageName: '次次月尾款', ratioPct: 30, amount: undefined, manual: false, triggerPoint: '审核通过', dueRule: 'next_2_month_day', dueDays: 0, dueDayOfMonth: 30, conditionText: '次次月结清尾款30%' },
  ]
  form.terms.forEach(recalcTerm)
}

/** 按比例自动算金额(未手工改过金额的节点) */
function recalcTerm(t: TermRow) {
  if (!t.manual && form.totalAmount != null && t.ratioPct != null) t.amount = round2(form.totalAmount * t.ratioPct / 100)
}
function onRatioInput(t: TermRow) { t.manual = false; recalcTerm(t) }
/** 点「改金额」进入手工编辑；点「按比例」回到自动算 */
function toggleManual(t: TermRow) {
  t.manual = !t.manual
  if (!t.manual) recalcTerm(t)
}
function onAmountInput(t: TermRow) {
  if (form.totalAmount && t.amount != null) t.ratioPct = Math.round(t.amount / form.totalAmount * 1000000) / 10000
}
watch(() => form.totalAmount, () => form.terms.forEach(recalcTerm))

const termSum = computed(() => round2(form.terms.reduce((s, t) => s + (t.amount || 0), 0)))
const ratioSum = computed(() => Math.round(form.terms.reduce((s, t) => s + (t.ratioPct || 0), 0) * 10000) / 10000)
const termsOk = computed(() => form.totalAmount != null && Math.abs(termSum.value - form.totalAmount) <= 0.01)

function openCreate() {
  Object.assign(form, {
    contractNo: '', supplierId: undefined, rentContractId: undefined,
    detailSubtotal: undefined, discountAmount: 0, totalAmount: undefined,
    taxPct: 13, signedDate: '', warrantyYears: 5, deliveryAddress: '', deliveryContact: '',
    remark: '', proofFileIds: [],
    terms: [Object.assign(newTerm('首付款'), { ratioPct: 30 }), Object.assign(newTerm('验收款'), { ratioPct: 60 }), Object.assign(newTerm('尾款'), { ratioPct: 10 })],
  })
  uploaderKey.value++
  createDlg.value = true
}
function fillEvenly() {
  // 把未锁定节点的比例补齐到 100%：差额加到最后一个节点
  const rest = Math.round((100 - ratioSum.value) * 10000) / 10000
  const last = form.terms[form.terms.length - 1]
  if (last && rest !== 0) { last.ratioPct = Math.round(((last.ratioPct || 0) + rest) * 10000) / 10000; onRatioInput(last) }
}

/** 公司名归一化：去空格括号、地区前缀(深圳市/广州市…)和「有限公司」后缀，便于「某某市甲乙技术有限公司」匹配库里简写的「甲乙」 */
function normName(n: string) {
  return n.replace(/[\s()（）]/g, '').replace(/(股份)?有限(责任)?公司$/, '').replace(/^[\u4e00-\u9fa5]{2,3}市/, '')
}
const unmatchedSupplier = ref('')
/** 合同识别 → 预填表单；files 非空时(拍照按钮)同时把原件挂上，从上传框触发时原件已挂 */
async function onContractRecognized(d: RecognizedContract, files?: File[]) {
  if (d.contractNo) form.contractNo = d.contractNo
  unmatchedSupplier.value = ''
  if (d.supplierName) {
    const n = normName(d.supplierName)
    const hit = suppliers.value.find((x) => x.name === d.supplierName)
      || suppliers.value.find((x) => { const m = normName(x.name); return m && n && (m.includes(n) || n.includes(m)) })
    if (hit) form.supplierId = hit.id
    else {
      unmatchedSupplier.value = d.supplierName
      ElMessage.warning(`供应商库里没有匹配「${d.supplierName}」，请先到「供应商」建档，再回来选择`)
    }
  }
  if (d.mainContractNo) {
    const hit = rentContracts.value.find((x) => x.no === d.mainContractNo)
    if (hit) form.rentContractId = hit.id
  }
  if (d.signedDate) form.signedDate = d.signedDate
  if (d.taxRate != null) form.taxPct = Math.round(d.taxRate * 10000) / 100
  if (d.totalAmount != null) form.totalAmount = d.totalAmount
  if (d.terms?.length) {
    form.terms = d.terms.map((x) => {
      const t = newTerm(x.stageName || '')
      t.conditionText = x.conditionText || ''
      if (x.ratio != null) t.ratioPct = Math.round(x.ratio * 1000000) / 10000
      if (x.amount != null) {
        t.amount = x.amount
        const calc = form.totalAmount != null && t.ratioPct != null ? round2(form.totalAmount * t.ratioPct / 100) : null
        // 识别出的金额与按比例算的不一致（合同里写的是定额）→ 保留合同金额，标记为手工
        t.manual = calc == null || Math.abs(calc - x.amount) > 0.01
        if (x.ratio == null) onAmountInput(t)
      } else recalcTerm(t)
      return t
    })
  }
  if (files?.length) {
    await nextTick()
    await contractUploader.value?.addFiles(files)
  }
}
/** 新建时同时关联采购入库单：同一供应商、未关联其他合同、未红冲；选了主合同则只列该主合同下的采购单。默认全部勾选，可取消 */
const createCandidates = ref<PurchaseListItem[]>([])
const createLinkIds = ref<number[]>([])
async function refreshCreateCandidates() {
  if (!createDlg.value || !form.supplierId) { createCandidates.value = []; createLinkIds.value = []; return }
  const res = await fetchPurchases({ page: 1, size: 200 })
  createCandidates.value = res.records.filter((p) => p.supplierId === form.supplierId && p.status !== '已红冲' && !p.pcId
    && (!form.rentContractId || p.contractId === form.rentContractId))
  createLinkIds.value = createCandidates.value.map((p) => p.id)
}
watch(() => [form.supplierId, form.rentContractId, createDlg.value], refreshCreateCandidates)
async function submitCreate() {
  creating.value = true
  try {
    const newId = await createPurchaseContract({
      contractNo: form.contractNo, supplierId: form.supplierId, rentContractId: form.rentContractId,
      detailSubtotal: form.detailSubtotal ?? undefined,
      discountAmount: form.discountAmount ?? 0,
      totalAmount: form.totalAmount,
      taxRate: form.taxPct / 100, signedDate: form.signedDate || undefined,
      warrantyYears: form.warrantyYears ?? undefined,
      deliveryAddress: form.deliveryAddress || undefined,
      deliveryContact: form.deliveryContact || undefined,
      remark: form.remark || undefined,
      proofFileIds: form.proofFileIds.length ? form.proofFileIds : undefined,
      terms: form.terms.map((t) => ({
        stageName: t.stageName, amount: t.amount,
        ratio: t.ratioPct != null ? t.ratioPct / 100 : undefined,
        triggerPoint: t.triggerPoint || '审核通过',
        dueRule: t.dueRule || 'after_days',
        dueDays: t.dueDays ?? 0,
        dueDayOfMonth: t.dueDayOfMonth ?? undefined,
        conditionText: t.conditionText || undefined,
      })),
    })
    if (createLinkIds.value.length) await linkPcPurchases(newId, createLinkIds.value)
    ElMessage.success(createLinkIds.value.length ? `采购合同已创建，并关联了 ${createLinkIds.value.length} 张采购单` : '采购合同已创建')
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

// ---- 关联采购入库单 ----
const linkDlg = ref(false)
const linking = ref(false)
const candidates = ref<PurchaseListItem[]>([])
const picked = ref<number[]>([])
async function openLink() {
  const sid = detail.value!.contract.supplierId
  const res = await fetchPurchases({ page: 1, size: 200 })
  candidates.value = res.records.filter((p) => p.supplierId === sid && p.status !== '已红冲' && !p.pcId)
  picked.value = []
  linkDlg.value = true
}
async function submitLink() {
  linking.value = true
  try {
    await linkPcPurchases(detail.value!.contract.id, picked.value)
    ElMessage.success('已关联，之后合同付款会自动冲销这些采购单的应付')
    linkDlg.value = false
    await reloadDetail()
  } finally { linking.value = false }
}
async function undoLink(id: number) {
  await ElMessageBox.confirm('解除该采购单与本合同的关联？', '解除关联', { type: 'warning' })
  await unlinkPcPurchase(detail.value!.contract.id, id)
  ElMessage.success('已解除')
  await reloadDetail()
}

// ---- 补绑/变更主租赁合同 ----
const bindRentDlg = ref(false)
const bindRentSaving = ref(false)
const bindRentId = ref<number | undefined>(undefined)
function openBindRent() {
  bindRentId.value = detail.value?.contract.rentContractId
  bindRentDlg.value = true
}
async function submitBindRent() {
  if (!detail.value) return
  bindRentSaving.value = true
  try {
    await bindPcRentContract(detail.value.contract.id, bindRentId.value ?? null)
    bindRentDlg.value = false
    ElMessage.success('已更新关联的主租赁合同')
    await reloadDetail()
  } finally { bindRentSaving.value = false }
}

// ---- 登记验收与审核通过日（自动推算各付款节点到期日） ----
const pcAccDlg = ref(false)
const pcAccSaving = ref(false)
const pcAccForm = reactive({ acceptanceDate: '', auditPassDate: '' })
function openPcAcc() {
  pcAccForm.acceptanceDate = detail.value?.contract.acceptanceDate || new Date().toISOString().slice(0, 10)
  pcAccForm.auditPassDate = detail.value?.contract.auditPassDate || ''
  pcAccDlg.value = true
}
async function submitPcAcc() {
  if (!detail.value) return
  pcAccSaving.value = true
  try {
    await recordPcAcceptance(detail.value.contract.id, {
      acceptanceDate: pcAccForm.acceptanceDate || undefined,
      auditPassDate: pcAccForm.auditPassDate || undefined,
    })
    pcAccDlg.value = false
    ElMessage.success('已登记验收/审核日期，各节点到期日已按月结规则重算')
    await reloadDetail()
  } finally { pcAccSaving.value = false }
}

// ---- 登记付款 ----
const payDlg = ref(false)
const paying = ref(false)
const payRows = ref<ReceiptPaymentDraft[]>([])
const payReading = ref(false)
const payError = computed(() => receiptPaymentError(payRows.value))
const payTotal = computed(() => payRows.value.reduce((sum, row) => sum + Math.round((row.amount || 0) * 100), 0) / 100)
function openPay() {
  payRows.value = []
  payReading.value = false
  uploaderKey.value++
  payDlg.value = true
}
async function submitPay() {
  if (paying.value || payReading.value) return
  if (payError.value) { ElMessage.warning(payError.value); return }
  if (payTotal.value > (detail.value?.contract.summary.unpaid ?? 0)) { ElMessage.warning('本次付款合计超过合同未付金额，请核对各张回单'); return }
  const payments = receiptPaymentBody(payRows.value)
  paying.value = true
  try {
    await payPurchaseContractBatch(detail.value!.contract.id, payments)
    ElMessage.success(`已分别登记 ${payments.length} 笔付款，并逐笔生成凭证`)
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
const proofView = reactive({ show: false, id: 0, title: '', type: 'supplier_payment' as 'supplier_payment' | 'pc_contract' | 'pc_invoice' })
function viewProof(id: number) { Object.assign(proofView, { show: true, id, type: 'supplier_payment', title: `付款回单 #${id}` }) }
function viewContractFiles() { Object.assign(proofView, { show: true, id: detail.value!.contract.id, type: 'pc_contract', title: '合同原件' }) }
function viewInvoiceFiles(id: number, no: string) { Object.assign(proofView, { show: true, id, type: 'pc_invoice', title: `发票原件 ${no}` }) }

// ---- 登记收票 ----
const invDlg = ref(false)
const invSaving = ref(false)
const invForm = reactive({ invoiceNo: '', invoiceType: '专票', issueDate: '', sellerName: '', amountExTax: undefined as number | undefined, taxAmount: undefined as number | undefined, remark: '', proofFileIds: [] as number[] })
const invUploader = ref<InstanceType<typeof ProofUploader>>()
/** 发票照片识别 → 预填发票号/类型/日期/金额；原图同时挂成发票原件 */
async function onInvoiceRecognized(d: RecognizedInvoice, files?: File[]) {
  if (d.invoiceNo) invForm.invoiceNo = d.invoiceNo
  if (d.invoiceType) invForm.invoiceType = d.invoiceType
  if (d.issueDate) invForm.issueDate = d.issueDate
  if (d.sellerName) invForm.sellerName = d.sellerName
  if (d.amountExTax != null) invForm.amountExTax = d.amountExTax
  if (d.taxAmount != null) invForm.taxAmount = d.taxAmount
  if (d.totalAmount != null && Math.abs(invTotal.value - d.totalAmount) > 0.01) {
    ElMessage.warning(`识别的价税合计 ${d.totalAmount} 与不含税+税额 ${invTotal.value} 不一致，请对照发票核对`)
  }
  const sup = detail.value?.contract.supplierName
  if (d.sellerName && sup && !(d.sellerName.includes(sup) || sup.includes(d.sellerName))) {
    ElMessage.warning(`发票销方「${d.sellerName}」与合同供应商「${sup}」不一致，请核对`)
  }
  if (files?.length) {
    await nextTick()
    await invUploader.value?.addFiles(files)
  }
}
const invTotal = computed(() => Math.round(((invForm.amountExTax || 0) + (invForm.taxAmount || 0)) * 100) / 100)
function openInvoice() {
  Object.assign(invForm, { invoiceNo: '', invoiceType: '专票', issueDate: '', sellerName: detail.value?.contract.supplierName || '', amountExTax: undefined, taxAmount: undefined, remark: '', proofFileIds: [] })
  uploaderKey.value++
  invDlg.value = true
}
async function submitInvoice() {
  invSaving.value = true
  try {
    await addPcInvoice(detail.value!.contract.id, {
      invoiceNo: invForm.invoiceNo, invoiceType: invForm.invoiceType, issueDate: invForm.issueDate,
      sellerName: invForm.sellerName || undefined, amountExTax: invForm.amountExTax, taxAmount: invForm.taxAmount,
      totalAmount: invTotal.value, remark: invForm.remark || undefined,
      proofFileIds: invForm.proofFileIds.length ? invForm.proofFileIds : undefined,
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
          <el-descriptions-item label="最终含税总价">{{ money(detail.contract.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="明细原价 / 优惠">
            {{ detail.contract.detailSubtotal != null ? money(detail.contract.detailSubtotal) : '—' }}
            <span v-if="detail.contract.discountAmount">（优惠 {{ money(detail.contract.discountAmount) }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="税率 / 状态">{{ (detail.contract.taxRate * 100).toFixed(0) }}% · {{ detail.contract.status }}</el-descriptions-item>
          <el-descriptions-item label="已付">{{ money(detail.contract.summary.paid) }}</el-descriptions-item>
          <el-descriptions-item label="已收票">{{ money(detail.contract.summary.invoiced) }}</el-descriptions-item>
          <el-descriptions-item label="已付未收票">{{ money(detail.contract.summary.paidNotInvoiced) }}</el-descriptions-item>
          <el-descriptions-item label="验收 / 审核通过日">
            {{ detail.contract.acceptanceDate || '—' }} / {{ detail.contract.auditPassDate || '—' }}
            <el-button link type="primary" size="small" style="margin-left:4px" @click="openPcAcc">登记</el-button>
          </el-descriptions-item>
          <el-descriptions-item label="质保年限">{{ detail.contract.warrantyYears ? `${detail.contract.warrantyYears} 年` : '—' }}</el-descriptions-item>
          <el-descriptions-item label="合同原件">
            <el-button link type="primary" size="small" @click="viewContractFiles">查看原件（{{ detail.contractProofCount }}）</el-button>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.contract.deliveryAddress || detail.contract.deliveryContact" label="交付地 / 联系人" :span="3">
            {{ detail.contract.deliveryAddress || '—' }} <span v-if="detail.contract.deliveryContact">（{{ detail.contract.deliveryContact }}）</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="sec">
          <h4>主合同（客户租赁合同）</h4>
          <el-button size="small" @click="openBindRent">{{ detail.contract.rentContractNo ? '变更主合同' : '补绑主合同' }}</el-button>
        </div>
        <el-descriptions v-if="detail.contract.rentContractNo" :column="3" border size="small">
          <el-descriptions-item label="主合同号">{{ detail.contract.rentContractNo }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.contract.customerName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.contract.rentStatus || '-' }}</el-descriptions-item>
          <el-descriptions-item label="租期">{{ detail.contract.rentTermMonths ?? '-' }} 个月</el-descriptions-item>
          <el-descriptions-item label="月租">{{ money(detail.contract.rentMonthRent) }}</el-descriptions-item>
          <el-descriptions-item label="起租日">{{ detail.contract.rentStartDate || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-empty v-else :image-size="40" description="未关联主合同（先采后签，可点右上「补绑主合同」）" />

        <div class="sec"><h4>关联采购单</h4><el-button size="small" @click="openLink">关联采购单</el-button></div>
        <el-table :data="detail.purchases" size="small" border empty-text="尚未关联采购入库单">
          <el-table-column prop="no" label="采购单号" min-width="140" />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column label="采购金额" align="right" width="130"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
          <el-table-column label="待付应付" align="right" width="130"><template #default="{ row }">{{ money(row.pendingPayable) }}</template></el-table-column>
          <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="danger" size="small" @click="undoLink(row.id)">解除</el-button></template></el-table-column>
        </el-table>

        <h4>付款节点</h4>
        <el-table :data="detail.terms" size="small" border>
          <el-table-column prop="stageName" label="节点" width="110" />
          <el-table-column label="应付" align="right" width="115"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
          <el-table-column label="已核销" align="right" width="110"><template #default="{ row }">{{ money(row.paid) }}</template></el-table-column>
          <el-table-column label="未付" align="right" width="110"><template #default="{ row }">{{ money(row.unpaid) }}</template></el-table-column>
          <el-table-column label="到期日" width="110"><template #default="{ row }">{{ row.dueDate || '待验收/审核' }}</template></el-table-column>
          <el-table-column label="账期规则 / 条件" min-width="180">
            <template #default="{ row }">{{ row.ruleDesc || row.conditionText || '—' }}</template>
          </el-table-column>
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
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button link size="small" @click="viewInvoiceFiles(row.id, row.invoiceNo)">原件({{ detail.invoiceProofCounts?.[row.id] || 0 }})</el-button>
              <el-button v-if="row.status === '有效'" link type="danger" size="small" @click="undoInvoice(row.id)">作废</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <ProofViewer v-model="proofView.show" :biz-type="proofView.type" :biz-id="proofView.id" :title="proofView.title" :can-supplement="false" />

    <el-dialog v-model="createDlg" title="新建采购合同" width="780px">
      <el-alert v-if="recognizeOk === false" type="info" :closable="false" show-icon class="tip"
        title="当前使用本机识别（未配置大模型）：PDF 文字版最准，扫描件/照片可能有个别字读错。识别结果请逐项核对；读不出来的项会提示，手工补全即可。" />
      <el-alert type="info" :closable="false" show-icon class="tip"
        title="在下方「合同原件」上传合同（PDF、扫描件或照片，多页可一次选多张），系统自动识别合同号、供应商、总价、税率和付款节点并填入；识别结果只是预填，请核对后再创建。" />
      <div class="snap-row">
        <SnapRecognize kind="contract" label="拍照识别合同" @recognized="onContractRecognized" />
      </div>
      <el-form :model="form" label-width="100px">
        <el-form-item label="合同号" required><el-input v-model="form.contractNo" /></el-form-item>
        <el-form-item label="供应商" required>
          <el-select v-model="form.supplierId" filterable style="width: 100%">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <div v-if="unmatchedSupplier && !form.supplierId" class="sum bad">合同上的供应商「{{ unmatchedSupplier }}」在供应商库里没有，请先到「供应商」建档后再选择</div>
        </el-form-item>
        <el-form-item label="主合同">
          <el-select v-model="form.rentContractId" filterable clearable placeholder="对应的客户租赁合同（先签约后采购）" style="width: 100%">
            <el-option v-for="c in rentContracts" :key="c.id" :label="`${c.no} · ${c.customerName || ''} · ${c.termMonths}个月`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="明细原价">
              <el-input-number v-model="form.detailSubtotal" :min="0" :precision="2" :controls="false" style="width: 100%" placeholder="如 284838" @change="onSubtotalOrDiscountChange" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="整单优惠">
              <el-input-number v-model="form.discountAmount" :min="0" :precision="2" :controls="false" style="width: 100%" placeholder="如 4838" @change="onSubtotalOrDiscountChange" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="最终总价" required>
              <el-input-number v-model="form.totalAmount" :min="0" :precision="2" :controls="false" style="width: 100%" placeholder="如 280000" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="税率(%)"><el-input-number v-model="form.taxPct" :min="0" :max="100" :controls="false" style="width: 100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="签订日期"><el-date-picker v-model="form.signedDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="质保(年)"><el-input-number v-model="form.warrantyYears" :min="0" :max="20" :precision="0" :controls="false" style="width: 100%" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="14"><el-form-item label="交付地址"><el-input v-model="form.deliveryAddress" placeholder="如 广州市花都区茶塘园区B区1楼" /></el-form-item></el-col>
          <el-col :span="10"><el-form-item label="交付联系人"><el-input v-model="form.deliveryContact" placeholder="姓名 + 电话" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="付款节点" required>
          <div class="terms">
            <div class="term-row">
              <el-button size="small" type="primary" plain @click="applyMonthlyTermPreset">套用：审核月结30天40% + 次月30% + 次次月30%</el-button>
            </div>
            <div v-for="(t, i) in form.terms" :key="i" class="term-row">
              <el-input v-model="t.stageName" placeholder="节点名" style="width: 100px" />
              <el-input-number v-model="t.ratioPct" :min="0" :max="100" :precision="2" :controls="false" placeholder="比例%" style="width: 75px" @change="onRatioInput(t)" />
              <el-input-number v-model="t.amount" :min="0" :precision="2" :controls="false" placeholder="金额" style="width: 115px"
                :disabled="!t.manual" @change="onAmountInput(t)" />
              <el-button size="small" :type="t.manual ? 'warning' : 'default'" style="width: 62px" @click="toggleManual(t)">{{ t.manual ? '按比例' : '改金额' }}</el-button>
              <el-select v-model="t.triggerPoint" size="small" style="width: 95px">
                <el-option label="下单" value="下单" />
                <el-option label="入库" value="入库" />
                <el-option label="验收合格" value="验收合格" />
                <el-option label="审核通过" value="审核通过" />
              </el-select>
              <el-select v-model="t.dueRule" size="small" style="width: 112px">
                <el-option label="触发日+N天" value="after_days" />
                <el-option label="月底+N天" value="month_end_days" />
                <el-option label="次月固定日" value="next_month_day" />
                <el-option label="次次月固定日" value="next_2_month_day" />
              </el-select>
              <el-input-number v-if="t.dueRule === 'next_month_day' || t.dueRule === 'next_2_month_day'"
                v-model="t.dueDayOfMonth" size="small" :min="1" :max="31" :precision="0" :controls="false" placeholder="月末" style="width: 58px" />
              <el-input-number v-else v-model="t.dueDays" size="small" :min="0" :precision="0" :controls="false" placeholder="天数" style="width: 58px" />
              <el-button link type="danger" :disabled="form.terms.length === 1" @click="form.terms.splice(i, 1)">删</el-button>
            </div>
            <div class="term-row">
              <el-button size="small" @click="form.terms.push(newTerm())">＋ 加节点</el-button>
              <el-button size="small" :disabled="ratioSum === 100" @click="fillEvenly">比例补齐到100%</el-button>
              <span class="sum" :class="{ bad: !termsOk }">比例合计 {{ ratioSum }}% · 金额合计 {{ money(termSum) }}{{ termsOk ? '' : '（须等于合同总价）' }}</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item v-if="createCandidates.length" label="关联采购单">
          <div>
            <el-checkbox-group v-model="createLinkIds">
              <div v-for="p in createCandidates" :key="p.id"><el-checkbox :value="p.id">{{ p.no }} · {{ p.status }} · {{ money(p.totalAmount) }}</el-checkbox></div>
            </el-checkbox-group>
            <div class="sum">已自动勾选该供应商未关联的采购单，创建后其应付会随合同付款自动冲销；不需要的请取消勾选</div>
          </div>
        </el-form-item>
        <el-form-item label="合同原件">
          <ProofUploader :key="uploaderKey" ref="contractUploader" v-model="form.proofFileIds"
            recognize-kind="contract" @recognized="onContractRecognized" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDlg = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="!termsOk" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="linkDlg" title="关联采购入库单" width="560px">
      <el-alert type="info" :closable="false" show-icon class="tip"
        title="只列出同一供应商、未关联其他合同的采购单。关联后，本合同的每笔付款会按到期日先后自动冲销这些采购单的待付应付；这些采购单不能再在「采购入库」页直接登记付款。" />
      <el-checkbox-group v-model="picked">
        <div v-for="p in candidates" :key="p.id"><el-checkbox :value="p.id">{{ p.no }} · {{ p.status }} · {{ money(p.totalAmount) }}</el-checkbox></div>
      </el-checkbox-group>
      <div v-if="!candidates.length" class="sum">没有可关联的采购单</div>
      <template #footer>
        <el-button @click="linkDlg = false">取消</el-button>
        <el-button type="primary" :loading="linking" :disabled="!picked.length" @click="submitLink">确认关联</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="payDlg" title="登记付款 · 按回单逐笔登记" width="760px" destroy-on-close :close-on-click-modal="false" :show-close="!payReading && !paying" :close-on-press-escape="!payReading && !paying">
      <el-alert v-if="recognizeOk === false" type="info" :closable="false" show-icon class="tip"
        title="当前使用本机识别：原版电子回单 PDF 最准；截图/照片的金额可能读不全，请对照回单核对。" />
      <ReceiptPaymentEditor :key="uploaderKey" v-model="payRows" v-model:busy="payReading" :disabled="paying" :supplier-name="detail?.contract.supplierName" />
      <div class="sum">共 {{ payRows.length }} 笔付款，合计 {{ money(payTotal) }}；合同未付 {{ money(detail?.contract.summary.unpaid) }}</div>
      <el-alert type="info" :closable="false" show-icon title="每张回单单独生成付款记录和凭证，各自按节点顺序核销。请逐笔核对金额与日期后提交。" />
      <template #footer>
        <el-button :disabled="payReading || paying" @click="payDlg = false">取消</el-button>
        <el-button type="primary" :loading="paying" :disabled="payReading || !!payError" @click="submitPay">确认登记 {{ payRows.length }} 笔</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="invDlg" title="登记进项发票" width="560px">
      <el-alert v-if="recognizeOk === false" type="info" :closable="false" show-icon class="tip"
        title="当前使用本机识别：电子发票 PDF 最准；图片/照片请对照发票核对。" />
      <div class="snap-row">
        <SnapRecognize kind="invoice" label="拍照识别发票" @recognized="onInvoiceRecognized" />
        <span class="sum">拍发票，自动填发票号/日期/金额并留存原件</span>
      </div>
      <el-form :model="invForm" label-width="100px">
        <el-form-item label="发票号码" required><el-input v-model="invForm.invoiceNo" /></el-form-item>
        <el-form-item label="类型"><el-radio-group v-model="invForm.invoiceType"><el-radio value="专票">专票</el-radio><el-radio value="普通">普通</el-radio></el-radio-group></el-form-item>
        <el-form-item label="开票日期" required><el-date-picker v-model="invForm.issueDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="销方"><el-input v-model="invForm.sellerName" /></el-form-item>
        <el-form-item label="不含税金额" required><el-input-number v-model="invForm.amountExTax" :min="0" :precision="2" :controls="false" style="width: 200px" /></el-form-item>
        <el-form-item label="税额" required><el-input-number v-model="invForm.taxAmount" :min="0" :precision="2" :controls="false" style="width: 200px" /></el-form-item>
        <el-form-item label="价税合计">{{ money(invTotal) }}</el-form-item>
        <el-form-item label="发票原件"><ProofUploader :key="uploaderKey" ref="invUploader" v-model="invForm.proofFileIds" recognize-kind="invoice" @recognized="onInvoiceRecognized" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="invForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="invDlg = false">取消</el-button>
        <el-button type="primary" :loading="invSaving" :disabled="!invForm.invoiceNo || !invForm.issueDate || !invTotal" @click="submitInvoice">确认登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="bindRentDlg" title="补绑 / 变更主租赁合同" width="500px">
      <el-form label-width="100px">
        <el-form-item label="客户租赁合同">
          <el-select v-model="bindRentId" filterable clearable style="width: 100%" placeholder="选择客户租赁合同">
            <el-option v-for="c in rentContracts" :key="c.id" :label="`${c.no} · ${c.customerName || ''} · ${c.termMonths}个月`" :value="c.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bindRentDlg = false">取消</el-button>
        <el-button type="primary" :loading="bindRentSaving" @click="submitBindRent">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pcAccDlg" title="登记验收合格 / 审核通过日期" width="480px">
      <el-form label-width="110px">
        <el-form-item label="验收合格日">
          <el-date-picker v-model="pcAccForm.acceptanceDate" type="date" value-format="YYYY-MM-DD" style="width: 200px" />
        </el-form-item>
        <el-form-item label="资料审核通过日">
          <el-date-picker v-model="pcAccForm.auditPassDate" type="date" value-format="YYYY-MM-DD" style="width: 200px" />
        </el-form-item>
      </el-form>
      <div class="sum">登记后，系统按各付款节点的「月底+N天 / 次月 / 次次月」规则自动算出具体到期日。</div>
      <template #footer>
        <el-button @click="pcAccDlg = false">取消</el-button>
        <el-button type="primary" :loading="pcAccSaving" @click="submitPcAcc">保存并计算到期日</el-button>
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
.term-row { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.term-head { font-size: 12px; color: #909399; }
.snap-row { display: flex; gap: 10px; align-items: center; margin: 4px 0 12px; }
.sum { font-size: 12px; color: #909399; }
.sum.bad { color: #f56c6c; }
:deep(.el-table__row) { cursor: pointer; }
</style>
