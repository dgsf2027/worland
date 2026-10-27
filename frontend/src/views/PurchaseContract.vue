<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchSupplierPool } from '@/api/supplier'
import { fetchPurchases, type PurchaseListItem } from '@/api/purchase'
import { fetchContracts, type ContractListItem } from '@/api/contract'
import {
  addPcInvoice, createPurchaseContract, fetchPurchaseContract, fetchPurchaseContracts, fetchRecognizeStatus,
  linkPcPurchases, payPurchaseContract, unlinkPcPurchase, voidPcInvoice, voidPcPayment,
  type PcContract, type PcDetail, type RecognizedContract, type RecognizedInvoice, type RecognizedReceipt,
} from '@/api/purchaseContract'
import ProofUploader from '@/components/payproof/ProofUploader.vue'
import ProofViewer from '@/components/payproof/ProofViewer.vue'
import SnapRecognize from '@/components/payproof/SnapRecognize.vue'

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
onMounted(async () => {
  await load()
  suppliers.value = (await fetchSupplierPool({ page: 1, size: 500 })).records.map((x) => ({ id: x.id, name: x.name }))
  rentContracts.value = (await fetchContracts({ page: 1, size: 500 })).records
  fetchRecognizeStatus().then((r) => { recognizeOk.value = r.configured }).catch(() => { recognizeOk.value = null })
})

// ---- 新建合同 ----
interface TermRow { stageName: string; ratioPct?: number; amount?: number; manual: boolean; conditionText: string }
const createDlg = ref(false)
const creating = ref(false)
const uploaderKey = ref(0)
const contractUploader = ref<InstanceType<typeof ProofUploader>>()
const form = reactive({
  contractNo: '', supplierId: undefined as number | undefined, rentContractId: undefined as number | undefined,
  totalAmount: undefined as number | undefined,
  taxPct: 13, signedDate: '', remark: '', proofFileIds: [] as number[],
  terms: [] as TermRow[],
})
const round2 = (v: number) => Math.round(v * 100) / 100
const newTerm = (name = ''): TermRow => ({ stageName: name, ratioPct: undefined, amount: undefined, manual: false, conditionText: '' })

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
  Object.assign(form, { contractNo: '', supplierId: undefined, rentContractId: undefined, totalAmount: undefined,
    taxPct: 13, signedDate: '', remark: '', proofFileIds: [],
    terms: [Object.assign(newTerm('首付款'), { ratioPct: 30 }), Object.assign(newTerm('验收款'), { ratioPct: 60 }), Object.assign(newTerm('尾款'), { ratioPct: 10 })] })
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
      contractNo: form.contractNo, supplierId: form.supplierId, rentContractId: form.rentContractId, totalAmount: form.totalAmount,
      taxRate: form.taxPct / 100, signedDate: form.signedDate || undefined, remark: form.remark || undefined,
      proofFileIds: form.proofFileIds.length ? form.proofFileIds : undefined,
      terms: form.terms.map((t) => ({
        stageName: t.stageName, amount: t.amount,
        ratio: t.ratioPct != null ? t.ratioPct / 100 : undefined, conditionText: t.conditionText || undefined,
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

// ---- 登记付款 ----
const payDlg = ref(false)
const paying = ref(false)
const payForm = reactive({ amount: undefined as number | undefined, paidDate: '', bankSerial: '', remark: '', proofFileIds: [] as number[] })
const payUploader = ref<InstanceType<typeof ProofUploader>>()
/** 回单照片识别 → 预填金额/日期/流水号；原图同时挂成付款回单（无需再手工上传） */
async function onReceiptRecognized(d: RecognizedReceipt, files?: File[]) {
  if (d.amount != null) payForm.amount = d.amount
  if (d.paidDate) payForm.paidDate = d.paidDate
  if (d.bankSerial) payForm.bankSerial = d.bankSerial
  const who = [d.payeeName ? `收款方 ${d.payeeName}` : '', d.remark || ''].filter(Boolean).join(' · ')
  if (who && !payForm.remark) payForm.remark = who
  const sup = detail.value?.contract.supplierName
  if (d.payeeName && sup && !(d.payeeName.includes(sup) || sup.includes(d.payeeName))) {
    ElMessage.warning(`回单收款方「${d.payeeName}」与合同供应商「${sup}」不一致，请核对`)
  }
  if (files?.length) {
    await nextTick()
    await payUploader.value?.addFiles(files)
  }
}
function openPay() {
  Object.assign(payForm, { amount: undefined, paidDate: new Date().toISOString().slice(0, 10), bankSerial: '', remark: '', proofFileIds: [] })
  uploaderKey.value++
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
          <el-descriptions-item label="含税总价">{{ money(detail.contract.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="税率">{{ (detail.contract.taxRate * 100).toFixed(0) }}%</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.contract.status }}</el-descriptions-item>
          <el-descriptions-item label="已付">{{ money(detail.contract.summary.paid) }}</el-descriptions-item>
          <el-descriptions-item label="已收票">{{ money(detail.contract.summary.invoiced) }}</el-descriptions-item>
          <el-descriptions-item label="已付未收票">{{ money(detail.contract.summary.paidNotInvoiced) }}</el-descriptions-item>
          <el-descriptions-item label="合同原件">
            <el-button link type="primary" size="small" @click="viewContractFiles">查看原件（{{ detail.contractProofCount }}）</el-button>
          </el-descriptions-item>
        </el-descriptions>

        <h4>主合同（客户租赁合同）</h4>
        <el-descriptions v-if="detail.contract.rentContractNo" :column="3" border size="small">
          <el-descriptions-item label="主合同号">{{ detail.contract.rentContractNo }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.contract.customerName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.contract.rentStatus || '-' }}</el-descriptions-item>
          <el-descriptions-item label="租期">{{ detail.contract.rentTermMonths ?? '-' }} 个月</el-descriptions-item>
          <el-descriptions-item label="月租">{{ money(detail.contract.rentMonthRent) }}</el-descriptions-item>
          <el-descriptions-item label="起租日">{{ detail.contract.rentStartDate || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-empty v-else :image-size="40" description="未关联主合同（新建采购合同时可选择）" />

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
        <el-form-item label="含税总价" required><el-input-number v-model="form.totalAmount" :min="0" :precision="2" :controls="false" style="width: 200px" /></el-form-item>
        <el-form-item label="税率(%)"><el-input-number v-model="form.taxPct" :min="0" :max="100" :controls="false" /></el-form-item>
        <el-form-item label="签订日期"><el-date-picker v-model="form.signedDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="付款节点" required>
          <div class="terms">
            <div class="term-row term-head"><span style="width:110px">节点名</span><span style="width:100px">合同比例(%)</span><span style="width:150px">金额(元)</span><span style="width:70px"></span><span>触发条件</span></div>
            <div v-for="(t, i) in form.terms" :key="i" class="term-row">
              <el-input v-model="t.stageName" placeholder="节点名" style="width: 110px" />
              <el-input-number v-model="t.ratioPct" :min="0" :max="100" :precision="2" :controls="false" placeholder="比例" style="width: 100px" @change="onRatioInput(t)" />
              <el-input-number v-model="t.amount" :min="0" :precision="2" :controls="false" placeholder="金额" style="width: 150px"
                :disabled="!t.manual" @change="onAmountInput(t)" />
              <el-button size="small" :type="t.manual ? 'warning' : 'default'" style="width: 70px" @click="toggleManual(t)">{{ t.manual ? '按比例' : '改金额' }}</el-button>
              <el-input v-model="t.conditionText" placeholder="触发条件(可选)" style="width: 150px" />
              <el-button link type="danger" :disabled="form.terms.length === 1" @click="form.terms.splice(i, 1)">删除</el-button>
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

    <el-dialog v-model="payDlg" title="登记付款" width="560px">
      <el-alert v-if="recognizeOk === false" type="info" :closable="false" show-icon class="tip"
        title="当前使用本机识别：原版电子回单 PDF 最准；截图/照片的金额可能读不全，请对照回单核对。" />
      <div class="snap-row">
        <SnapRecognize kind="receipt" label="拍照识别回单" @recognized="onReceiptRecognized" />
        <span class="sum">拍银行回单，自动填金额/日期/流水号并留存回单</span>
      </div>
      <el-form :model="payForm" label-width="100px">
        <el-form-item label="付款金额" required><el-input-number v-model="payForm.amount" :min="0" :precision="2" :controls="false" style="width: 200px" /></el-form-item>
        <el-form-item label="付款日期" required><el-date-picker v-model="payForm.paidDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="银行流水号"><el-input v-model="payForm.bankSerial" placeholder="回单上的交易流水号，不可重复" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="payForm.remark" /></el-form-item>
        <el-form-item label="付款回单" required><ProofUploader :key="uploaderKey" ref="payUploader" v-model="payForm.proofFileIds" recognize-kind="receipt" @recognized="onReceiptRecognized" /></el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon title="按节点顺序自动核销：先冲满第一个未付清的节点，余额冲下一个。" />
      <template #footer>
        <el-button @click="payDlg = false">取消</el-button>
        <el-button type="primary" :loading="paying" :disabled="!payForm.proofFileIds.length || !payForm.amount" @click="submitPay">确认登记</el-button>
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
