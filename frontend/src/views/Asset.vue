<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchCustomerPool, type CustomerPoolItem } from '@/api/customer'
import PaymentTermsEditor from '@/components/PaymentTermsEditor.vue'
import {
  fetchAssets, fetchAssetDetail, createAsset, updateAsset, changeAssetStatus,
  addBom, updateBom, deleteBom, updateBomPricing, updateBomFault,
  updateSingleUnitReturn, updateIntendedCustomer,
  updatePaymentTerms, toTermInputs, toTermRows, checkTermRows, type TermRow,
  uploadBomAttachment, fetchBomAttachments, saveFile, checkUploadFile, importBom, exportBom,
  BOM_ATTACHMENT_EXTS, BOM_ATTACHMENT_MAX_MB,
  type AssetListItem, type AssetDetail, type BomNode, type BomAttachment, type FaultItem, type BomImportResult,
} from '@/api/asset'
import { fetchContracts, type ContractListItem } from '@/api/contract'
import { createSupplier, fetchSupplierPool, type SupplierPoolItem } from '@/api/supplier'

// ---- 身份切换(占位头,验证字段级隔离) ----
const identities = [
  { name: '老板', role: '老板' },
  { name: '供应链', role: '供应链' },
  { name: '财务', role: '财务' },
  { name: '投资人', role: 'LP' },
]
const curIdentity = ref(localStorage.getItem('rent_user_name') || '老板')
function switchIdentity(name: string) {
  const id = identities.find((i) => i.name === name)!
  localStorage.setItem('rent_user_name', id.name)
  localStorage.setItem('rent_user_role', id.role)
  curIdentity.value = name
  loadList()
  if (detail.value) openDetail(detail.value.id)
  ElMessage.success(`已切换为 ${id.name}（${id.role}）· 观察集采价/账面价字段级隔离`)
}

// ---- 台账列表 ----
const filters = reactive<{ status: string; category: string; keyword: string }>({ status: '', category: '', keyword: '' })
const statuses = ['采购', '投放', '在租', '待转让', '已转让', '收回待处置', '报废']
const categories = ['播种墙', '货架', '阁楼', '配件']
const suppliers = ref<SupplierPoolItem[]>([])
const suppliersLoading = ref(false)

function selectedSupplier(id?: number) {
  return suppliers.value.find((item) => item.id === id)
}

async function loadSuppliers() {
  suppliersLoading.value = true
  try {
    const res = await fetchSupplierPool({ page: 1, size: 200 })
    suppliers.value = res.records
  } finally {
    suppliersLoading.value = false
  }
}

const supplierDialogVisible = ref(false)
const supplierSaving = ref(false)
const supplierForm = reactive({ name: '', companyAccount: '', openingBank: '' })

function openCreateSupplier() {
  Object.assign(supplierForm, { name: '', companyAccount: '', openingBank: '' })
  supplierDialogVisible.value = true
}

async function submitSupplier() {
  if (!supplierForm.name.trim() || !supplierForm.companyAccount.trim() || !supplierForm.openingBank.trim()) {
    ElMessage.warning('公司名称、公司账户和开户银行均为必填项')
    return
  }
  supplierSaving.value = true
  try {
    const supplierId = await createSupplier({
      name: supplierForm.name.trim(),
      companyAccount: supplierForm.companyAccount.trim(),
      openingBank: supplierForm.openingBank.trim(),
      status: '接触',
    })
    await loadSuppliers()
    if (!selectedSupplier(supplierId)) {
      suppliers.value.unshift({ id: supplierId, ...supplierForm, status: '接触' } as SupplierPoolItem)
    }
    if (bomDialogVisible.value) bomForm.supplierId = supplierId
    else if (assetDialogVisible.value) assetForm.supplierId = supplierId
    supplierDialogVisible.value = false
    ElMessage.success('供应商已录入，并已加入供应商列表')
  } finally {
    supplierSaving.value = false
  }
}

const list = ref<AssetListItem[]>([])
const total = ref(0)
const loading = ref(false)

// 从合同页跳过来:?contractId= 只看该合同下的设备
const contractFilterId = ref<number | null>(null)
const contractFilterNo = ref('')
function clearContractFilter() {
  contractFilterId.value = null
  contractFilterNo.value = ''
  const q = { ...route.query }
  delete q.contractId
  router.replace({ query: q })
  loadList()
}

async function loadList() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 50 }
    if (filters.status) params.status = filters.status
    if (filters.category) params.category = filters.category
    if (filters.keyword) params.keyword = filters.keyword
    if (contractFilterId.value) params.contractId = contractFilterId.value
    const res = await fetchAssets(params)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

const statusType: Record<string, string> = {
  采购: 'info', 投放: 'warning', 在租: 'success', 待转让: 'primary',
  已转让: 'info', 收回待处置: 'danger', 报废: 'info',
}
function money(v?: number | null) {
  if (v === null || v === undefined) return '🔒'
  return v >= 10000 ? (v / 10000).toFixed(2) + '万' : '¥' + v.toLocaleString()
}

// ---- 状态:可直接改(后端同口径校验),改为「在租」须选客户与合同 ----
const statusChanging = ref(false)
const rentDialogVisible = ref(false)
const rentForm = reactive<{ customerId?: number; contractId?: number; remark: string }>({ remark: '' })
const contracts = ref<ContractListItem[]>([])
const contractsLoading = ref(false)

async function loadContractsOf(customerId?: number) {
  contracts.value = []
  if (!customerId) return
  contractsLoading.value = true
  try {
    contracts.value = (await fetchContracts({ customerId, page: 1, size: 200 })).records
  } finally {
    contractsLoading.value = false
  }
}

// ---- 详情抽屉 ----
const detail = ref<AssetDetail | null>(null)
const drawer = ref(false)
async function openDetail(id: number) {
  detail.value = await fetchAssetDetail(id)
  drawer.value = true
}
async function onStatusSelect(to: string) {
  if (!detail.value || to === detail.value.status) return
  if (to === '在租') {
    // 在租要落到具体客户与合同(关联关系)
    await loadCustomers()
    rentForm.customerId = detail.value.currentHolderCustomerId ?? detail.value.intendedCustomerId ?? undefined
    rentForm.contractId = detail.value.contractId ?? undefined
    rentForm.remark = ''
    await loadContractsOf(rentForm.customerId)
    rentDialogVisible.value = true
    return
  }
  const label = detail.value.contractNo || detail.value.serialNo
  try {
    await ElMessageBox.confirm(`确认把设备 ${label} 的状态改为「${to}」？`, '调整状态', { type: 'warning' })
  } catch {
    return
  }
  await applyStatus(to)
}

async function applyStatus(to: string, body: Record<string, any> = {}) {
  if (!detail.value) return
  statusChanging.value = true
  try {
    await changeAssetStatus(detail.value.id, { targetStatus: to, ...body })
    ElMessage.success(`状态已改为 ${to}`)
    await openDetail(detail.value.id)
    await loadList()
  } finally {
    statusChanging.value = false
  }
}

async function confirmRented() {
  if (!rentForm.customerId) { ElMessage.warning('请选择承租客户'); return }
  if (!rentForm.contractId) { ElMessage.warning('请选择关联合同'); return }
  await applyStatus('在租', { customerId: rentForm.customerId, contractId: rentForm.contractId, remark: rentForm.remark })
  rentDialogVisible.value = false
}

// ---- 新建 / 编辑设备 ----
const assetDialogVisible = ref(false)
const assetDialogMode = ref<'create' | 'edit'>('create')
const assetForm = reactive<Record<string, any>>({
  category: '播种墙', model: '', marketPrice: undefined,
  purchasePrice: undefined, supplierId: undefined, monthlyLaborValue: undefined,
  replaceHeadcount: undefined, remark: '',
})

function resetAssetForm() {
  Object.assign(assetForm, {
    category: '播种墙', model: '', marketPrice: undefined,
    purchasePrice: undefined, supplierId: undefined, monthlyLaborValue: undefined,
    replaceHeadcount: undefined, remark: '',
  })
}

function openCreateAsset() {
  assetDialogMode.value = 'create'
  resetAssetForm()
  assetDialogVisible.value = true
}

function openEditAsset() {
  if (!detail.value) return
  assetDialogMode.value = 'edit'
  Object.assign(assetForm, {
    category: detail.value.category,
    model: detail.value.model || '',
    marketPrice: detail.value.marketPrice,
    purchasePrice: detail.value.purchasePrice,
    supplierId: detail.value.supplierId,
    monthlyLaborValue: detail.value.monthlyLaborValue,
    replaceHeadcount: detail.value.replaceHeadcount,
    remark: detail.value.remark || '',
  })
  assetDialogVisible.value = true
}

function applySupplierToAssetForm(supplierId?: number) {
  const supplier = selectedSupplier(supplierId)
  if (!supplier) return
  if (supplier.quotePrice != null) assetForm.purchasePrice = supplier.quotePrice
  if (!assetForm.model && supplier.itemDesc) assetForm.model = supplier.itemDesc
  if (supplier.mainCategory && categories.includes(supplier.mainCategory)) {
    assetForm.category = supplier.mainCategory
  }
}

async function submitAsset() {
  if (!assetForm.category) {
    ElMessage.warning('品类必填')
    return
  }
  const body = { ...assetForm, supplierId: assetForm.supplierId || null }
  if (assetDialogMode.value === 'edit' && detail.value) {
    await updateAsset(detail.value.id, body)
    ElMessage.success('设备资料已更新')
    await openDetail(detail.value.id)
  } else {
    await createAsset(body)
    ElMessage.success('设备已建档(状态=采购)')
  }
  assetDialogVisible.value = false
  await loadList()
}

// ---- BOM 拆解维护 ----
const bomDialogVisible = ref(false)
const bomDialogMode = ref<'create' | 'edit'>('create')
const bomForm = reactive<Record<string, any>>({
  id: undefined, parentId: undefined, parentName: '一级项', name: '', qty: 1,
  unitCost: undefined, subtotalOverride: null, supplierId: undefined, lifeYears: undefined,
  warrantyUntil: undefined, repairable: true, faultCount: 0,
  residualRate: undefined, remark: '',
})
const bomSaving = ref(false)
const bomSubtotal = computed<number | undefined>({
  get: () => bomForm.subtotalOverride ?? Math.round(Number(bomForm.qty || 0) * Number(bomForm.unitCost || 0) * 100) / 100,
  set: (value) => { bomForm.subtotalOverride = value ?? null },
})
function resetBomSubtotal() { bomForm.subtotalOverride = null }
const bomAttachments = ref<BomAttachment[]>([])
const queuedBomFiles = ref<File[]>([])
const bomFilesLoading = ref(false)
const bomUploadCount = ref(0)
const bomUploading = computed(() => bomUploadCount.value > 0)

async function loadBomAttachments(bomId?: number) {
  if (!bomId) {
    bomAttachments.value = []
    return
  }
  bomFilesLoading.value = true
  try {
    bomAttachments.value = await fetchBomAttachments(bomId)
  } finally {
    bomFilesLoading.value = false
  }
}

const bomAttachmentAccept = BOM_ATTACHMENT_EXTS.map((e) => '.' + e).join(',')
const bomAttachmentHint = `支持 Word/Excel/PPT/PDF/压缩文件（${BOM_ATTACHMENT_EXTS.join('/')}），单个不超过 ${BOM_ATTACHMENT_MAX_MB}MB`

function beforeBomFileUpload(file: File) {
  const err = checkUploadFile(file, BOM_ATTACHMENT_EXTS, BOM_ATTACHMENT_MAX_MB)
  if (err) {
    ElMessage.warning(err)
    return false
  }
  return true
}

async function uploadBomFileRequest(options: any) {
  const file = options.file as File
  if (!bomForm.id) {
    queuedBomFiles.value.push(file)
    ElMessage.info('文件已暂存，保存清单项后自动上传')
    return
  }
  bomUploadCount.value += 1
  try {
    await uploadBomAttachment(bomForm.id, file)
    await loadBomAttachments(bomForm.id)
    ElMessage.success('附件上传成功')
  } finally {
    bomUploadCount.value -= 1
  }
}

function removeQueuedBomFile(index: number) {
  queuedBomFiles.value.splice(index, 1)
}

async function downloadBomAttachment(file: BomAttachment) {
  await saveFile(file.id, file.fileName)
}

// ---- 配件 BOM：导入 / 导出 ----
const bomImporting = ref(false)
const bomExporting = ref(false)
const bomImportResult = ref<BomImportResult | null>(null)
const bomImportVisible = ref(false)

function beforeBomImport(file: File) {
  const err = checkUploadFile(file, ['xls', 'xlsx'], 10)
  if (err) {
    ElMessage.warning(err)
    return false
  }
  return true
}
async function bomImportRequest(options: any) {
  const d = detail.value
  if (!d) return
  const file = options.file as File
  try {
    await ElMessageBox.confirm(
      `导入「${file.name}」会整表替换本设备的配件 BOM 明细（原有配件删除，挂了附件的配件会拦下来）。配件 BOM 不影响合同金额。确认导入？`,
      '导入配件 BOM 明细', { type: 'warning', confirmButtonText: '确认导入' },
    )
  } catch {
    return
  }
  bomImporting.value = true
  try {
    bomImportResult.value = await importBom(d.id, file)
    bomImportVisible.value = true
    await openDetail(d.id)
  } finally {
    bomImporting.value = false
  }
}
async function onExportBom(template = false) {
  const d = detail.value
  if (!d) return
  bomExporting.value = true
  try {
    await exportBom(d.id, d.serialNo, template)
    ElMessage.success(template ? '模板已下载' : '已导出，可修改后再导入回系统')
  } finally {
    bomExporting.value = false
  }
}

// ---- 配件 BOM：行内改数量/单价 ----
const pricingRowId = ref<number | null>(null)
const pricingForm = reactive<Record<string, any>>({ qty: 1, unitCost: undefined })
const pricingSaving = ref(false)

function startPricing(row: BomNode) {
  pricingRowId.value = row.id
  pricingForm.qty = row.qty ?? 1
  pricingForm.unitCost = row.unitCost ?? undefined
}

function cancelPricing() {
  pricingRowId.value = null
}

async function savePricing(row: BomNode) {
  if (!detail.value) return
  if (!Number.isFinite(pricingForm.qty) || pricingForm.qty < 0.01) {
    ElMessage.warning('数量须大于零')
    return
  }
  if (row.subtotalOverride != null) {
    await ElMessageBox.confirm('该项当前是手动合价，保存后将改为按「数量 × 单价」自动计算，确认继续？', '改数量/单价', { type: 'warning' })
  }
  pricingSaving.value = true
  try {
    await updateBomPricing(row.id, { qty: pricingForm.qty, unitCost: pricingForm.unitCost ?? null })
    pricingRowId.value = null
    ElMessage.success('已保存（配件 BOM 只记配件构成，不影响合同价）')
    await openDetail(detail.value.id)
    await loadList()
  } finally {
    pricingSaving.value = false
  }
}

const pricingPreview = computed(() =>
  Math.round(Number(pricingForm.qty || 0) * Number(pricingForm.unitCost || 0) * 100) / 100,
)

// ---- 工程量清单：附件 ----
const attachDialogVisible = ref(false)
const attachRow = ref<BomNode | null>(null)

async function openAttachments(row: BomNode) {
  attachRow.value = row
  bomAttachments.value = []
  attachDialogVisible.value = true
  await loadBomAttachments(row.id)
}

async function uploadAttachRequest(options: any) {
  if (!attachRow.value) return
  bomUploadCount.value += 1
  try {
    await uploadBomAttachment(attachRow.value.id, options.file as File)
    await loadBomAttachments(attachRow.value.id)
    ElMessage.success('附件上传成功')
  } finally {
    bomUploadCount.value -= 1
  }
}

// ---- 承接客户(承租客户跳转 / 意向承接客户) ----
const router = useRouter()
function goCustomer(id?: number) {
  if (id) router.push({ path: '/customer', query: { id: String(id) } })
}
function goContract(id?: number) {
  if (id) router.push({ path: '/contract', query: { id: String(id) } })
}
const customers = ref<CustomerPoolItem[]>([])
const customersLoading = ref(false)
async function loadCustomers() {
  if (customers.value.length) return
  customersLoading.value = true
  try {
    customers.value = (await fetchCustomerPool({ page: 1, size: 1000 })).records
  } finally {
    customersLoading.value = false
  }
}
const intendedVisible = ref(false)
const intendedSaving = ref(false)
const intendedCustomerId = ref<number | undefined>()
async function openIntended() {
  if (!detail.value) return
  intendedCustomerId.value = detail.value.intendedCustomerId
  intendedVisible.value = true
  await loadCustomers()
}
async function submitIntended() {
  if (!detail.value) return
  intendedSaving.value = true
  try {
    await updateIntendedCustomer(detail.value.id, intendedCustomerId.value ?? null)
    intendedVisible.value = false
    ElMessage.success(intendedCustomerId.value ? '已设置意向承接客户' : '已清除意向承接客户')
    await openDetail(detail.value.id)
    loadList()
  } finally {
    intendedSaving.value = false
  }
}

// ---- 单台收益：手工覆盖 ----
const surDims = [
  { key: 'allocRent', label: '单台月租分摊(元)', precision: 2, step: 500, max: undefined as number | undefined },
  { key: 'cumulativeRent', label: '累计收租(元)', precision: 2, step: 1000, max: undefined },
  { key: 'returnRatePct', label: '单台回报率(%)', precision: 2, step: 1, max: undefined },
  { key: 'inServiceDays', label: '在租天数', precision: 0, step: 1, max: undefined },
  { key: 'idleDays', label: '空置天数', precision: 0, step: 1, max: undefined },
]
const surVisible = ref(false)
const surSaving = ref(false)
const surForm = reactive<Record<string, any>>({})
function isManual(field: string) {
  return !!detail.value?.singleUnitReturn.manualFields?.includes(field)
}
function openSurEdit() {
  const sr = detail.value?.singleUnitReturn
  if (!sr) return
  const m = (f: string) => sr.manualFields?.includes(f)
  Object.keys(surForm).forEach((k) => delete surForm[k])
  Object.assign(surForm, {
    allocRent: m('allocRent') ? sr.allocRent : null,
    cumulativeRent: m('cumulativeRent') ? sr.cumulativeRent : null,
    returnRatePct: m('returnRate') && sr.returnRate != null ? Math.round(sr.returnRate * 10000) / 100 : null,
    inServiceDays: m('inServiceDays') ? sr.inServiceDays : null,
    idleDays: m('idleDays') ? sr.idleDays : null,
  })
  surVisible.value = true
}
function surAutoValue(key: string) {
  const sr = detail.value?.singleUnitReturn as any
  if (!sr) return '—'
  if (key === 'returnRatePct') return isManual('returnRate') ? '（当前为手工值）' : (sr.returnRate != null ? (sr.returnRate * 100).toFixed(1) + '%' : '—')
  if (isManual(key)) return '（当前为手工值）'
  const v = sr[key]
  return v == null ? '—' : (key.endsWith('Days') ? v + ' 天' : money(v))
}
async function submitSur() {
  if (!detail.value) return
  surSaving.value = true
  try {
    await updateSingleUnitReturn(detail.value.id, {
      allocRent: surForm.allocRent ?? null,
      cumulativeRent: surForm.cumulativeRent ?? null,
      returnRate: surForm.returnRatePct == null ? null : Math.round(Number(surForm.returnRatePct) * 100) / 10000,
      inServiceDays: surForm.inServiceDays ?? null,
      idleDays: surForm.idleDays ?? null,
    })
    surVisible.value = false
    ElMessage.success('单台收益已保存')
    await openDetail(detail.value.id)
  } finally {
    surSaving.value = false
  }
}

// ---- 合同付款条件 ----
const payVisible = ref(false)
const paySaving = ref(false)
const payRows = ref<TermRow[]>([])
const payStatusType: Record<string, string> = { 待付: 'warning', 已付: 'success', 红冲: 'danger' }
function openPayEdit() {
  const plan = detail.value?.paymentPlan
  if (!plan) return
  payRows.value = toTermRows(plan.terms.length ? plan.terms : plan.defaultTemplate)
  payVisible.value = true
}
async function submitPay() {
  if (!detail.value) return
  const err = checkTermRows(payRows.value)
  if (err) {
    ElMessage.warning(err)
    return
  }
  const plan = detail.value.paymentPlan
  if (plan.purchaseInId && plan.purchaseStatus !== '已红冲') {
    try {
      await ElMessageBox.confirm(
        `该设备来自采购单 ${plan.purchaseNo}，保存后会按新条件重算本设备的「待付」应付（已付阶段不变），采购入库应付模块同步更新。确认保存？`,
        '更新付款条件', { type: 'warning', confirmButtonText: '确认保存' },
      )
    } catch {
      return
    }
  }
  paySaving.value = true
  try {
    await updatePaymentTerms(detail.value.id, toTermInputs(payRows.value))
    payVisible.value = false
    ElMessage.success('合同付款条件已保存')
    await openDetail(detail.value.id)
  } finally {
    paySaving.value = false
  }
}
function goPurchase() {
  if (detail.value?.paymentPlan.purchaseInId) router.push({ path: '/purchase', query: { id: String(detail.value.paymentPlan.purchaseInId) } })
}
function pctText(r?: number | null) {
  return r == null ? '—' : (Math.round(r * 1000000) / 10000) + '%'
}

// ---- 故障档案：编辑 ----
const faultDialogVisible = ref(false)
const faultSaving = ref(false)
const faultForm = reactive<Record<string, any>>({
  bomId: undefined, name: '', faultCount: 0, repairable: true, warrantyUntil: undefined, supplierId: undefined,
})

function openEditFault(row: FaultItem) {
  Object.assign(faultForm, {
    bomId: row.bomId,
    name: row.name,
    faultCount: row.faultCount ?? 0,
    repairable: row.repairable ?? true,
    warrantyUntil: row.warrantyUntil,
    supplierId: row.supplierId,
  })
  faultDialogVisible.value = true
}

async function submitFault() {
  if (!detail.value || !faultForm.bomId) return
  if (!Number.isInteger(faultForm.faultCount) || faultForm.faultCount < 0) {
    ElMessage.warning('故障次数须为非负整数')
    return
  }
  faultSaving.value = true
  try {
    await updateBomFault(faultForm.bomId, {
      faultCount: faultForm.faultCount,
      repairable: faultForm.repairable,
      warrantyUntil: faultForm.warrantyUntil || null,
      supplierId: faultForm.supplierId || null,
    })
    faultDialogVisible.value = false
    ElMessage.success('故障档案已更新')
    await openDetail(detail.value.id)
  } finally {
    faultSaving.value = false
  }
}

function resetBomForm(parent?: BomNode) {
  Object.assign(bomForm, {
    id: undefined,
    parentId: parent?.id,
    parentName: parent?.name || '一级项',
    name: '',
    qty: 1,
    unitCost: undefined,
    subtotalOverride: null,
    supplierId: undefined,
    lifeYears: undefined,
    warrantyUntil: undefined,
    repairable: true,
    faultCount: 0,
    residualRate: undefined,
    remark: '',
  })
}

function openAddBom(parent?: BomNode) {
  bomDialogMode.value = 'create'
  resetBomForm(parent)
  bomAttachments.value = []
  queuedBomFiles.value = []
  bomDialogVisible.value = true
}

async function openEditBom(row: BomNode) {
  bomDialogMode.value = 'edit'
  Object.assign(bomForm, {
    id: row.id,
    parentId: row.parentId,
    parentName: row.parentId ? '上级项 #' + row.parentId : '一级项',
    name: row.name,
    qty: row.qty ?? 1,
    unitCost: row.unitCost,
    subtotalOverride: row.subtotalOverride ?? null,
    supplierId: row.supplierId,
    lifeYears: row.lifeYears,
    warrantyUntil: row.warrantyUntil,
    repairable: row.repairable ?? true,
    faultCount: row.faultCount ?? 0,
    residualRate: row.residualRate,
    remark: row.remark || '',
  })
  queuedBomFiles.value = []
  bomAttachments.value = []
  bomDialogVisible.value = true
  await loadBomAttachments(row.id)
}

function applySupplierToBomForm(supplierId?: number) {
  const supplier = selectedSupplier(supplierId)
  if (!supplier) return
  if (supplier.quotePrice != null) {
    bomForm.unitCost = supplier.quotePrice
    resetBomSubtotal()
  }
  if (!bomForm.name && supplier.itemDesc) bomForm.name = supplier.itemDesc
}

async function submitBom() {
  if (bomSaving.value || bomUploading.value) return
  if (!detail.value || !bomForm.name.trim()) {
    ElMessage.warning('项目名称必填')
    return
  }
  if (!Number.isFinite(bomForm.qty) || bomForm.qty < 0.01 || !Number.isInteger(bomForm.faultCount) || bomForm.faultCount < 0) {
    ElMessage.warning('数量须大于零，故障次数须为非负整数')
    return
  }
  const assetId = detail.value.id
  const body = {
    name: bomForm.name.trim(), parentId: bomForm.parentId ?? null,
    qty: bomForm.qty, unitCost: bomForm.unitCost ?? null,
    subtotalOverride: bomForm.subtotalOverride ?? null,
    supplierId: bomForm.supplierId || null, lifeYears: bomForm.lifeYears ?? null,
    warrantyUntil: bomForm.warrantyUntil || null, repairable: bomForm.repairable,
    faultCount: bomForm.faultCount, residualRate: bomForm.residualRate ?? null,
    remark: bomForm.remark,
  }
  bomSaving.value = true
  try {
    let bomId = bomForm.id as number | undefined
    if (bomId) {
      await updateBom(bomId, body)
    } else {
      bomId = await addBom(assetId, body)
      bomForm.id = bomId
      bomDialogMode.value = 'edit'
    }
    // 成功一个移除一个；失败时保留剩余文件和已保存节点，重试不重复建档或上传。
    while (queuedBomFiles.value.length) {
      try {
        await uploadBomAttachment(bomId, queuedBomFiles.value[0])
        queuedBomFiles.value.shift()
      } catch {
        ElMessage.warning('BOM 已保存，附件上传失败；待上传文件已保留，请点击保存重试')
        await openDetail(assetId)
        await loadBomAttachments(bomId)
        return
      }
    }
    ElMessage.success('清单项已保存')
    bomDialogVisible.value = false
    await openDetail(assetId)
    await loadList()
  } finally {
    bomSaving.value = false
  }
}

async function removeBom(row: BomNode) {
  if (!detail.value) return
  await ElMessageBox.confirm(
    '删除「' + row.name + '」将同时删除其全部下级项，确认继续？',
    '删除清单项',
    { type: 'warning' },
  )
  await deleteBom(row.id)
  ElMessage.success('清单项已删除')
  await openDetail(detail.value.id)
  await loadList()
}

// 从客户详情「关联合同」跳转过来(?id=):直接打开该设备详情
const route = useRoute()
onMounted(async () => {
  const cid = Number(route.query.contractId)
  if (cid) {
    contractFilterId.value = cid
    try {
      contractFilterNo.value = (await fetchContracts({ page: 1, size: 1 })).records.find((c) => c.id === cid)?.no || ('#' + cid)
    } catch {
      contractFilterNo.value = '#' + cid
    }
  }
  loadList()
  loadSuppliers()
  const id = Number(route.query.id)
  if (id) openDetail(id)
})
</script>

<template>
  <div class="asset-page">
    <div class="toolbar">
      <el-radio-group :model-value="curIdentity" @change="switchIdentity" size="small">
        <el-radio-button v-for="i in identities" :key="i.name" :value="i.name">{{ i.name }}</el-radio-button>
      </el-radio-group>
      <span class="hint">当前身份决定「集采价/账面价/成本」是否可见（GP/LP 打码 🔒）</span>
      <el-button size="small" style="margin-left:auto" @click="openCreateSupplier">+ 录入供应商</el-button>
      <el-button type="primary" size="small" @click="openCreateAsset">+ 新建设备</el-button>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-select v-model="filters.status" placeholder="状态" clearable size="small" style="width:130px" @change="loadList">
        <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
      </el-select>
      <el-select v-model="filters.category" placeholder="品类" clearable size="small" style="width:120px;margin-left:8px" @change="loadList">
        <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
      </el-select>
      <el-input v-model="filters.keyword" placeholder="合同编号/序列号/型号" clearable size="small" style="width:200px;margin-left:8px" @keyup.enter="loadList" />
      <el-button size="small" style="margin-left:8px" @click="loadList">查询</el-button>
      <el-tag v-if="contractFilterId" size="small" closable type="warning" style="margin-left:8px" @close="clearContractFilter">
        只看合同 {{ contractFilterNo }} 的设备
      </el-tag>
      <span class="total">共 {{ total }} 台</span>
    </el-card>

    <el-table :data="list" v-loading="loading" size="small" @row-click="(r:any) => openDetail(r.id)" style="cursor:pointer">
      <el-table-column label="合同编号" width="150">
        <template #default="{ row }">
          <a v-if="row.contractId" class="lnk" @click.stop="goContract(row.contractId)">{{ row.contractNo }}</a>
          <span v-else class="upload-tip">未挂合同</span>
          <div class="upload-tip">{{ row.serialNo }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="品类" width="90" />
      <el-table-column prop="model" label="型号" min-width="150" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }"><el-tag :type="statusType[row.status] || 'info'" size="small">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="市场价" width="100"><template #default="{ row }">{{ money(row.marketPrice) }}</template></el-table-column>
      <el-table-column label="集采价🔒" width="100"><template #default="{ row }">{{ money(row.purchasePrice) }}</template></el-table-column>
      <el-table-column label="账面价🔒" width="100"><template #default="{ row }">{{ money(row.bookValue) }}</template></el-table-column>
      <el-table-column label="残值" width="90"><template #default="{ row }">{{ money(row.residualValue) }}</template></el-table-column>
      <el-table-column label="承接客户" min-width="170">
        <template #default="{ row }">
          <a v-if="row.currentHolderCustomerId" class="lnk" @click.stop="goCustomer(row.currentHolderCustomerId)">{{ row.currentHolderName }}</a>
          <div v-if="row.intendedCustomerId" :class="row.currentHolderCustomerId ? 'upload-tip' : ''">
            <a class="lnk" @click.stop="goCustomer(row.intendedCustomerId)">{{ row.intendedCustomerName }}</a>
            <el-tag size="small" type="warning" effect="plain" class="manual-tag">意向</el-tag>
          </div>
          <span v-if="!row.currentHolderCustomerId && !row.intendedCustomerId">—</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer" :title="detail ? `设备 ${detail.contractNo || detail.serialNo} · ${detail.status}` : '设备详情'" size="60%">
      <div v-if="detail" class="detail">
        <el-alert v-if="detail.sensitiveMasked" type="info" :closable="false" show-icon
          title="投资人视角:集采价/账面价/成本/回报率等敏感财务字段已打码 🔒" style="margin-bottom:12px" />
        <div class="detail-actions">
          <el-button v-if="!detail.sensitiveMasked" type="primary" size="small" @click="openEditAsset">修改设备</el-button>
        </div>

        <!-- 要点 -->
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="品类">{{ detail.category }}</el-descriptions-item>
          <el-descriptions-item label="型号">{{ detail.model || '—' }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detail.supplierName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="市场价">{{ money(detail.marketPrice) }}</el-descriptions-item>
          <el-descriptions-item label="所属合同">
            <a v-if="detail.contractId" class="lnk" @click="goContract(detail.contractId)">{{ detail.contractNo }}</a>
            <span v-else class="upload-tip">未挂合同</span>
          </el-descriptions-item>
          <el-descriptions-item label="合同价(含税)">{{ money(detail.purchasePrice) }}</el-descriptions-item>
          <el-descriptions-item label="账面价(经营口径)">{{ money(detail.bookValue) }}</el-descriptions-item>
          <el-descriptions-item label="残值(市场价×转让率)">{{ money(detail.residualValue) }}</el-descriptions-item>
          <el-descriptions-item label="月替代人工">{{ money(detail.monthlyLaborValue) }}</el-descriptions-item>
          <el-descriptions-item label="自购回本(月)">{{ detail.selfPurchasePayback ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="承接客户" :span="3">
            <template v-if="detail.currentHolderCustomerId">
              <a class="lnk" @click="goCustomer(detail.currentHolderCustomerId)">{{ detail.currentHolderName }}</a>
              <el-tag size="small" type="success" class="manual-tag">在租</el-tag>
              <span v-if="detail.contractNo" class="upload-tip inline-tip">合同 <a class="lnk" @click="goContract(detail.contractId)">{{ detail.contractNo }}</a></span>
            </template>
            <template v-if="detail.intendedCustomerId">
              <a class="lnk" @click="goCustomer(detail.intendedCustomerId)">{{ detail.intendedCustomerName }}</a>
              <el-tag size="small" type="warning" effect="plain" class="manual-tag">意向</el-tag>
            </template>
            <span v-else-if="!detail.currentHolderCustomerId" class="upload-tip inline-tip">未签约，暂无承接客户</span>
            <el-button v-if="!detail.sensitiveMasked" link type="primary" size="small" class="manual-tag" @click="openIntended">
              {{ detail.intendedCustomerId ? '更换意向客户' : '设置意向客户' }}
            </el-button>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 状态(可直接改) -->
        <div class="block-title">状态</div>
        <div class="status-row">
          <el-select :model-value="detail.status" size="small" style="width:150px" :loading="statusChanging"
            :disabled="detail.sensitiveMasked" @change="onStatusSelect">
            <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
          </el-select>
          <span class="upload-tip">改为「在租」要选承租客户和合同；改为其它状态会解除承租关系。每次调整都留痕。</span>
        </div>

        <!-- 配件 BOM 明细(同清单格式,不参与合同价) -->
        <div class="block-title block-title-row">
          <span>配件 BOM 明细</span>
          <span v-if="!detail.sensitiveMasked" class="boq-actions">
            <el-button link type="primary" size="small" :loading="bomExporting" @click="onExportBom(false)">⬇ 导出</el-button>
            <el-button link type="primary" size="small" :loading="bomExporting" @click="onExportBom(true)">下载模板</el-button>
            <el-upload :http-request="bomImportRequest" :before-upload="beforeBomImport" accept=".xls,.xlsx"
              :show-file-list="false" :disabled="bomImporting" style="display:inline-block">
              <el-button link type="primary" size="small" :loading="bomImporting">⬆ 导入</el-button>
            </el-upload>
            <el-button type="primary" link size="small" @click="openAddBom()">+ 新增配件</el-button>
          </span>
        </div>
        <el-table :data="detail.bom" row-key="id" default-expand-all size="small"
          :tree-props="{ children: 'children' }" border>
          <el-table-column prop="seq" label="序号" width="60" align="center"><template #default="{ row }">{{ row.seq ?? '—' }}</template></el-table-column>
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="model" label="型号" width="110" show-overflow-tooltip><template #default="{ row }">{{ row.model || '—' }}</template></el-table-column>
          <el-table-column prop="spec" label="规格" min-width="130" show-overflow-tooltip><template #default="{ row }">{{ row.spec || '—' }}</template></el-table-column>
          <el-table-column prop="unit" label="单位" width="60" align="center"><template #default="{ row }">{{ row.unit || '—' }}</template></el-table-column>
          <el-table-column label="数量" width="120">
            <template #default="{ row }">
              <el-input-number v-if="pricingRowId === row.id" v-model="pricingForm.qty" :min="0.01" :precision="2" :step="1"
                size="small" controls-position="right" style="width:100%" />
              <span v-else>{{ row.qty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单价🔒" width="140">
            <template #default="{ row }">
              <el-input-number v-if="pricingRowId === row.id" v-model="pricingForm.unitCost" :min="0" :precision="2" :step="100"
                size="small" controls-position="right" style="width:100%" />
              <span v-else>{{ money(row.unitCost) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="合价🔒" width="110">
            <template #default="{ row }">
              <span v-if="pricingRowId === row.id">{{ money(pricingPreview) }}</span>
              <span v-else>{{ money(row.subtotal) }}<el-tag v-if="row.subtotalOverride != null" size="small" type="info" class="manual-tag">手动</el-tag></span>
            </template>
          </el-table-column>
          <el-table-column prop="supplierName" label="供应商" width="110"><template #default="{ row }">{{ row.supplierName || '—' }}</template></el-table-column>
          <el-table-column v-if="!detail.sensitiveMasked" label="操作" width="290" fixed="right">
            <template #default="{ row }">
              <template v-if="pricingRowId === row.id">
                <el-button link type="primary" size="small" :loading="pricingSaving" @click="savePricing(row)">保存</el-button>
                <el-button link size="small" :disabled="pricingSaving" @click="cancelPricing">取消</el-button>
              </template>
              <template v-else>
                <el-button link type="primary" size="small" :disabled="pricingRowId !== null" @click="startPricing(row)">改数量/单价</el-button>
                <el-button link type="success" size="small" @click="openAttachments(row)">附件</el-button>
                <el-button link size="small" @click="openAddBom(row)">新增下级</el-button>
                <el-button link size="small" @click="openEditBom(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeBom(row)">删除</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="detail.costBreakdown" class="boq-total">
          配件 BOM 合计 <b>{{ money(detail.costBreakdown.total) }}</b>
          <span class="upload-tip">仅作配件构成与故障档案参考，<b>不参与合同金额</b>（合同清单在「合同 · 签约与租金计划」里）。</span>
        </div>

        <!-- 成本 / 残值 拆解 -->
        <el-row :gutter="12" v-if="detail.costBreakdown || detail.residualBreakdown">
          <el-col :span="12" v-if="detail.costBreakdown">
            <div class="block-title">成本拆解</div>
            <div v-for="it in detail.costBreakdown.items" :key="it.name" class="bar-row">
              <span class="bar-label">{{ it.name }}</span>
              <el-progress :percentage="Math.round(it.ratio * 100)" :stroke-width="14" style="flex:1" />
              <span class="bar-val">{{ money(it.amount) }}</span>
            </div>
            <div class="cost-foot">Σ配件成本 {{ money(detail.costBreakdown.total) }} · 合同价 {{ money(detail.costBreakdown.purchasePrice) }} · 差 {{ money(detail.costBreakdown.gapVsPurchase) }}</div>
          </el-col>
          <el-col :span="12">
            <div class="block-title">残值构成</div>
            <div v-if="detail.residualBreakdown">
              <div v-for="it in (detail.residualBreakdown.items || [])" :key="it.name" class="bar-row">
                <span class="bar-label">{{ it.name }}</span>
                <span class="bar-val">{{ money(it.amount) }} <em>({{ (it.ratio * 100).toFixed(0) }}%)</em></span>
              </div>
              <div class="cost-foot">部件残值合计 {{ money(detail.residualBreakdown.bomResidualTotal) }} · 整机残值 {{ money(detail.residualBreakdown.categoryResidual) }}</div>
            </div>
          </el-col>
        </el-row>

        <!-- 故障档案 -->
        <div class="block-title">故障档案(按配件)</div>
        <el-table :data="detail.faultArchive" size="small" border>
          <el-table-column prop="name" label="配件" min-width="140" />
          <el-table-column prop="faultCount" label="故障次数" width="90" />
          <el-table-column label="可维修" width="80"><template #default="{ row }">{{ row.repairable ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="质保剩余(天)" width="120"><template #default="{ row }">
            <el-tag v-if="row.warrantyDaysLeft != null" :type="row.warrantyDaysLeft < 0 ? 'danger' : (row.warrantyDaysLeft < 60 ? 'warning' : 'success')" size="small">{{ row.warrantyDaysLeft }}</el-tag>
            <span v-else>—</span>
          </template></el-table-column>
          <el-table-column prop="supplierName" label="质保方" width="110"><template #default="{ row }">{{ row.supplierName || '—' }}</template></el-table-column>
          <el-table-column v-if="!detail.sensitiveMasked" label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEditFault(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 单台收益 -->
        <div class="block-title block-title-row">
          <span>单台收益</span>
          <el-button v-if="!detail.sensitiveMasked" type="primary" link size="small" @click="openSurEdit">编辑</el-button>
        </div>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="单台月租分摊">{{ money(detail.singleUnitReturn.allocRent) }}<el-tag v-if="isManual('allocRent')" size="small" type="warning" class="manual-tag">手工</el-tag></el-descriptions-item>
          <el-descriptions-item label="累计收租">{{ money(detail.singleUnitReturn.cumulativeRent) }}<el-tag v-if="isManual('cumulativeRent')" size="small" type="warning" class="manual-tag">手工</el-tag></el-descriptions-item>
          <el-descriptions-item label="单台回报率">{{ detail.singleUnitReturn.returnRate != null ? (detail.singleUnitReturn.returnRate * 100).toFixed(1) + '%' : '🔒' }}<el-tag v-if="isManual('returnRate')" size="small" type="warning" class="manual-tag">手工</el-tag></el-descriptions-item>
          <el-descriptions-item label="在租天数">{{ detail.singleUnitReturn.inServiceDays ?? 0 }}<el-tag v-if="isManual('inServiceDays')" size="small" type="warning" class="manual-tag">手工</el-tag></el-descriptions-item>
          <el-descriptions-item label="空置天数">
            <el-tag v-if="detail.singleUnitReturn.idleAlert" type="danger" size="small">{{ detail.singleUnitReturn.idleDays }} ⚠</el-tag>
            <span v-else>{{ detail.singleUnitReturn.idleDays ?? 0 }}</span>
            <el-tag v-if="isManual('idleDays')" size="small" type="warning" class="manual-tag">手工</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 合同付款条件 -->
        <div class="block-title block-title-row">
          <span>合同付款条件（预计付款 = 集采价 × 比例）</span>
          <el-button v-if="!detail.sensitiveMasked" type="primary" link size="small" @click="openPayEdit">
            {{ detail.paymentPlan.terms.length ? '编辑' : '设置付款条件' }}
          </el-button>
        </div>
        <div class="pay-head">
          <span>集采价 <b>{{ money(detail.paymentPlan.basePrice) }}</b></span>
          <span v-if="detail.paymentPlan.purchaseInId">采购单 <a class="lnk" @click="goPurchase">{{ detail.paymentPlan.purchaseNo }}</a>
            <el-tag size="small" class="manual-tag">{{ detail.paymentPlan.purchaseStatus }}</el-tag></span>
          <span v-if="detail.paymentPlan.terms.length">待付应付 <b class="warn-text">{{ money(detail.paymentPlan.pendingTotal) }}</b></span>
          <span v-if="detail.paymentPlan.terms.length">已付 <b>{{ money(detail.paymentPlan.paidTotal) }}</b></span>
        </div>
        <el-table v-if="detail.paymentPlan.terms.length" :data="detail.paymentPlan.terms" size="small" border>
          <el-table-column prop="stageName" label="阶段" width="100" />
          <el-table-column label="比例" width="80" align="right"><template #default="{ row }">{{ pctText(row.ratio) }}</template></el-table-column>
          <el-table-column label="触发 / 到期" width="130"><template #default="{ row }">{{ row.triggerPoint }} + {{ row.dueDays }} 天</template></el-table-column>
          <el-table-column label="预计付款金额" width="130" align="right"><template #default="{ row }">{{ money(row.expectedAmount) }}</template></el-table-column>
          <el-table-column label="采购应付" min-width="200">
            <template #default="{ row }">
              <template v-if="row.payableId">
                {{ money(row.payableAmount) }} · 到期 {{ row.payableDueDate || '—' }}
                <el-tag size="small" :type="(payStatusType[row.payableStatus] as any) || 'info'" class="manual-tag">{{ row.payableStatus }}</el-tag>
              </template>
              <span v-else class="upload-tip inline-tip">{{ detail.paymentPlan.purchaseInId ? '触发后自动生成' : '未关联采购单' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else :description="detail.sensitiveMasked ? '当前角色不可见' : '尚未设置付款条件，点「设置付款条件」'" :image-size="50" />
        <div v-if="detail.paymentPlan.note" class="upload-tip">{{ detail.paymentPlan.note }}</div>

        <!-- 状态机时间轴 -->
        <div class="block-title">状态机时间轴</div>
        <el-timeline>
          <el-timeline-item v-for="(e, i) in detail.timeline" :key="i" :timestamp="e.bizTime" placement="top">
            <el-tag size="small" :type="statusType[e.eventType] || 'info'">{{ e.eventType }}</el-tag>
            <span style="margin-left:8px">{{ e.remark || '' }}</span>
            <span v-if="e.operatorName" style="color:#999;margin-left:8px">· {{ e.operatorName }}</span>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- 改为在租:选客户与合同 -->
    <el-dialog v-model="rentDialogVisible" title="改为「在租」" width="520px">
      <el-form label-width="100px" size="small">
        <el-form-item label="承租客户" required>
          <el-select v-model="rentForm.customerId" filterable clearable style="width:100%" :loading="customersLoading"
            placeholder="从客户 CRM 选择" @change="loadContractsOf">
            <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联合同" required>
          <el-select v-model="rentForm.contractId" clearable style="width:100%" :loading="contractsLoading"
            :placeholder="rentForm.customerId ? (contracts.length ? '选择合同' : '该客户暂无合同') : '先选客户'">
            <el-option v-for="c in contracts" :key="c.id" :label="`${c.no} · ${c.status}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="rentForm.remark" maxlength="255" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rentDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="statusChanging" @click="confirmRented">确认在租</el-button>
      </template>
    </el-dialog>

    <!-- 配件 BOM 导入结果 -->
    <el-dialog v-model="bomImportVisible" title="导入结果" width="560px">
      <template v-if="bomImportResult">
        <div class="import-kpi">
          <span>导入 <b>{{ bomImportResult.imported }}</b> 行</span>
          <span v-if="bomImportResult.skipped">跳过 <b>{{ bomImportResult.skipped }}</b></span>
          <span>一级项合计 <b>{{ money(bomImportResult.bomTotal) }}</b></span>
        </div>
        <ul v-if="bomImportResult.messages.length" class="import-msg">
          <li v-for="(m, i) in bomImportResult.messages" :key="i">{{ m }}</li>
        </ul>
        <div v-else class="upload-tip">没有需要注意的行，配件明细与表格一致。</div>
      </template>
      <template #footer><el-button type="primary" @click="bomImportVisible = false">知道了</el-button></template>
    </el-dialog>

    <!-- 新建 / 编辑设备弹窗 -->
    <el-dialog
      v-model="assetDialogVisible"
      :title="assetDialogMode === 'edit' ? '修改设备资料' : '新建设备(逐件建档)'"
      width="620px"
    >
      <el-form :model="assetForm" label-width="120px" size="small">
        <div class="supplier-tip">
          合同编号、合同税率和《工程量清单计价表》在「合同 · 签约与租金计划」里维护；设备序列号由系统自动生成。
        </div>
        <el-form-item label="供应商">
          <div class="supplier-field">
            <el-select
              v-model="assetForm.supplierId"
              filterable
              clearable
              :loading="suppliersLoading"
              placeholder="选择供应商并自动带出报价信息"
              @change="applySupplierToAssetForm"
            >
              <el-option
                v-for="supplier in suppliers"
                :key="supplier.id"
                :label="supplier.name + (supplier.mainCategory ? ' · ' + supplier.mainCategory : '')"
                :value="supplier.id"
                :disabled="supplier.status === '淘汰' || supplier.status === '已淘汰'"
              />
            </el-select>
            <el-button type="primary" plain @click="openCreateSupplier">录入供应商</el-button>
          </div>
        </el-form-item>
        <div v-if="selectedSupplier(assetForm.supplierId)" class="supplier-tip">
          自动带出：联系人 {{ selectedSupplier(assetForm.supplierId)?.contact || '—' }}
          · 主营 {{ selectedSupplier(assetForm.supplierId)?.mainCategory || '—' }}
          · 供货项 {{ selectedSupplier(assetForm.supplierId)?.itemDesc || '—' }}
          · 报价 {{ money(selectedSupplier(assetForm.supplierId)?.quotePrice) }}
          · 公司账户 {{ selectedSupplier(assetForm.supplierId)?.companyAccount || '—' }}
          · 开户银行 {{ selectedSupplier(assetForm.supplierId)?.openingBank || '—' }}
        </div>
        <el-form-item label="品类" required>
          <el-select v-model="assetForm.category" style="width:100%">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="型号"><el-input v-model="assetForm.model" /></el-form-item>
        <el-form-item label="市场价(元)">
          <el-input-number v-model="assetForm.marketPrice" :min="0" :step="1000" style="width:100%" />
        </el-form-item>
        <el-form-item label="合同价(元)">
          <el-input-number v-model="assetForm.purchasePrice" :min="0" :step="1000" style="width:100%" />
          <span v-if="assetDialogMode === 'edit' && detail?.boqLineId" class="upload-tip">
            本台由合同清单生成，价格取清单行单价；整份合同的金额请在「合同 · 签约与租金计划」的合同清单里改
          </span>
        </el-form-item>
        <el-form-item label="月替代人工(元)">
          <el-input-number v-model="assetForm.monthlyLaborValue" :min="0" :step="500" style="width:100%" />
        </el-form-item>
        <el-form-item label="替代人数">
          <el-input-number v-model="assetForm.replaceHeadcount" :min="0" :step="1" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="assetForm.remark" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assetDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAsset">
          {{ assetDialogMode === 'edit' ? '保存修改' : '建档' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 供应商快速录入弹窗 -->
    <el-dialog v-model="supplierDialogVisible" title="录入供应商" width="520px" append-to-body>
      <el-form :model="supplierForm" label-width="110px" size="small">
        <el-form-item label="公司名称" required>
          <el-input v-model="supplierForm.name" maxlength="128" placeholder="请输入供应商公司名称" />
        </el-form-item>
        <el-form-item label="公司账户" required>
          <el-input v-model="supplierForm.companyAccount" maxlength="128" autocomplete="off" placeholder="请输入公司开户账号" />
        </el-form-item>
        <el-form-item label="开户银行" required>
          <el-input v-model="supplierForm.openingBank" maxlength="128" placeholder="请输入开户银行全称" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="supplierDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="supplierSaving" @click="submitSupplier">保存供应商</el-button>
      </template>
    </el-dialog>
    <!-- 清单项维护弹窗 -->
    <el-dialog
      v-model="bomDialogVisible"
      :title="bomDialogMode === 'edit' ? '编辑清单项' : '新增清单项'"
      width="680px"
      :close-on-click-modal="false"
      :close-on-press-escape="!bomSaving && !bomUploading"
      :show-close="!bomSaving && !bomUploading"
    >
      <el-form :model="bomForm" label-width="120px" size="small" :disabled="bomSaving || bomUploading">
        <el-form-item label="上级项"><el-input :model-value="bomForm.parentName" disabled /></el-form-item>
        <el-form-item label="项目名称" required><el-input v-model="bomForm.name" maxlength="128" /></el-form-item>
        <el-form-item label="供应商">
          <el-select
            v-model="bomForm.supplierId"
            filterable
            clearable
            :loading="suppliersLoading"
            placeholder="选择供应商并自动带出配件报价"
            style="width:100%"
            @change="applySupplierToBomForm"
          >
            <el-option
              v-for="supplier in suppliers"
              :key="supplier.id"
              :label="supplier.name + (supplier.itemDesc ? ' · ' + supplier.itemDesc : '')"
              :value="supplier.id"
              :disabled="supplier.status === '淘汰' || supplier.status === '已淘汰'"
            />
          </el-select>
          <el-button type="primary" link @click="openCreateSupplier">+ 录入供应商</el-button>
        </el-form-item>
        <div v-if="selectedSupplier(bomForm.supplierId)" class="supplier-tip">
          自动带出：供货项 {{ selectedSupplier(bomForm.supplierId)?.itemDesc || '—' }}
          · 报价 {{ money(selectedSupplier(bomForm.supplierId)?.quotePrice) }}
          · 联系人 {{ selectedSupplier(bomForm.supplierId)?.contact || '—' }}
        </div>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="数量"><el-input-number v-model="bomForm.qty" :min="0.01" :precision="2" :step="1" @change="resetBomSubtotal" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="单价(元)"><el-input-number v-model="bomForm.unitCost" :min="0" :precision="2" :step="100" @change="resetBomSubtotal" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="合价(元)">
              <el-input-number v-model="bomSubtotal" :min="0" :precision="2" style="width:100%" />
              <el-button v-if="bomForm.subtotalOverride != null" link type="primary" @click="resetBomSubtotal">恢复自动计算</el-button>
              <span class="upload-tip">{{ bomForm.subtotalOverride == null ? '自动：数量 × 单价' : '手动合价；修改数量或单价后恢复自动计算' }}</span>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="使用年限"><el-input-number v-model="bomForm.lifeYears" :min="0" :step="0.5" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="质保到期">
              <el-date-picker v-model="bomForm.warrantyUntil" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="可维修"><el-switch v-model="bomForm.repairable" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="故障次数"><el-input-number v-model="bomForm.faultCount" :min="0" :precision="0" :step="1" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="残值率">
              <el-input-number v-model="bomForm.residualRate" :min="0" :max="1" :step="0.05" :precision="2" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="附件">
          <div class="bom-attachment-box">
            <el-upload
              :http-request="uploadBomFileRequest"
              :before-upload="beforeBomFileUpload"
              :accept="bomAttachmentAccept"
              :show-file-list="false"
              multiple
            >
              <el-button type="primary" plain :loading="bomUploading">上传附件</el-button>
            </el-upload>
            <div class="upload-tip">{{ bomAttachmentHint }}。新建清单项可先选文件，保存后自动上传；已建项会立即上传。</div>
            <div v-if="queuedBomFiles.length" class="attachment-list">
              <el-tag v-for="(file, index) in queuedBomFiles" :key="file.name + index" closable @close="removeQueuedBomFile(index)">
                待上传：{{ file.name }}
              </el-tag>
            </div>
            <div v-loading="bomFilesLoading" class="attachment-list">
              <el-button
                v-for="file in bomAttachments"
                :key="file.id"
                link
                type="primary"
                @click="downloadBomAttachment(file)"
              >
                {{ file.fileName }}（{{ Math.max(1, Math.round(file.size / 1024)) }} KB）
              </el-button>
              <span v-if="!bomFilesLoading && !bomAttachments.length" class="empty-attachment">暂无已上传文件</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="bomForm.remark" maxlength="255" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="bomSaving || bomUploading" @click="bomDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="bomSaving" :disabled="bomUploading" @click="submitBom">保存</el-button>
      </template>
    </el-dialog>

    <!-- 清单项附件弹窗 -->
    <el-dialog v-model="attachDialogVisible" :title="'附件 · ' + (attachRow?.name || '')" width="560px"
      :close-on-press-escape="!bomUploading" :show-close="!bomUploading">
      <el-upload
        :http-request="uploadAttachRequest"
        :before-upload="beforeBomFileUpload"
        :accept="bomAttachmentAccept"
        :show-file-list="false"
        multiple
      >
        <el-button type="primary" :loading="bomUploading">上传附件</el-button>
      </el-upload>
      <div class="upload-tip">{{ bomAttachmentHint }}</div>
      <div v-loading="bomFilesLoading" class="attachment-list attachment-col">
        <el-button v-for="file in bomAttachments" :key="file.id" link type="primary" @click="downloadBomAttachment(file)">
          {{ file.fileName }}（{{ Math.max(1, Math.round(file.size / 1024)) }} KB · {{ file.uploaderName || '—' }}）
        </el-button>
        <span v-if="!bomFilesLoading && !bomAttachments.length" class="empty-attachment">暂无附件</span>
      </div>
      <template #footer>
        <el-button :disabled="bomUploading" @click="attachDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 合同付款条件 -->
    <el-dialog v-model="payVisible" title="合同付款条件" width="760px" :close-on-click-modal="false">
      <el-alert v-if="detail?.paymentPlan.purchaseInId" type="info" :closable="false" show-icon style="margin-bottom:10px"
        :title="`来自采购单 ${detail?.paymentPlan.purchaseNo}：保存后按新条件重算本设备待付应付，已付阶段锁定不可改。`" />
      <PaymentTermsEditor v-model="payRows" :base-price="detail?.paymentPlan.basePrice ?? null" :disabled="paySaving" />
      <template #footer>
        <el-button :disabled="paySaving" @click="payVisible = false">取消</el-button>
        <el-button type="primary" :loading="paySaving" @click="submitPay">保存</el-button>
      </template>
    </el-dialog>

    <!-- 意向承接客户 -->
    <el-dialog v-model="intendedVisible" title="设置意向承接客户" width="460px">
      <el-form label-width="100px" size="small" :disabled="intendedSaving">
        <el-form-item label="意向客户">
          <el-select v-model="intendedCustomerId" filterable clearable :loading="customersLoading" placeholder="选择客户 CRM 中的客户" style="width:100%">
            <el-option v-for="c in customers" :key="c.id" :label="c.name + (c.contact ? ' · ' + c.contact : '')" :value="c.id" />
          </el-select>
        </el-form-item>
        <div v-if="detail?.currentHolderCustomerId" class="upload-tip fault-tip">
          本设备在租中（承接客户：{{ detail.currentHolderName }}，以合同为准）。这里设的意向客户只表示「下一手/在谈的承接方」，
          不会改动当前承租关系；签下一份合同时会以新合同的客户为准。清空后保存即取消。
        </div>
        <div v-else class="upload-tip fault-tip">未签约设备可先预设意向客户，客户详情里能看到；签约起租后自动以合同客户为准。清空后保存即取消。</div>
      </el-form>
      <template #footer>
        <el-button :disabled="intendedSaving" @click="intendedVisible = false">取消</el-button>
        <el-button type="primary" :loading="intendedSaving" @click="submitIntended">保存</el-button>
      </template>
    </el-dialog>

    <!-- 单台收益手工覆盖 -->
    <el-dialog v-model="surVisible" title="编辑单台收益" width="540px">
      <el-form label-width="130px" size="small" :disabled="surSaving">
        <el-form-item v-for="d in surDims" :key="d.key" :label="d.label">
          <el-input-number v-model="surForm[d.key]" :min="0" :precision="d.precision" :step="d.step" controls-position="right" placeholder="自动" style="width:180px" />
          <span class="upload-tip inline-tip">自动值 {{ surAutoValue(d.key) }}</span>
        </el-form-item>
        <div class="upload-tip fault-tip">填写即按手工值展示并标记「手工」；清空保存即恢复系统自动计算。只影响本设备展示，不改合同和收租单。</div>
      </el-form>
      <template #footer>
        <el-button :disabled="surSaving" @click="surVisible = false">取消</el-button>
        <el-button type="primary" :loading="surSaving" @click="submitSur">保存</el-button>
      </template>
    </el-dialog>

    <!-- 故障档案编辑弹窗 -->
    <el-dialog v-model="faultDialogVisible" :title="'编辑故障档案 · ' + faultForm.name" width="520px">
      <el-form :model="faultForm" label-width="110px" size="small" :disabled="faultSaving">
        <el-form-item label="故障次数" required>
          <el-input-number v-model="faultForm.faultCount" :min="0" :precision="0" :step="1" style="width:100%" />
        </el-form-item>
        <el-form-item label="可维修"><el-switch v-model="faultForm.repairable" /></el-form-item>
        <el-form-item label="质保到期">
          <el-date-picker v-model="faultForm.warrantyUntil" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="质保方">
          <el-select v-model="faultForm.supplierId" filterable clearable :loading="suppliersLoading" placeholder="选择供应商" style="width:100%">
            <el-option v-for="supplier in suppliers" :key="supplier.id" :label="supplier.name" :value="supplier.id" />
          </el-select>
        </el-form-item>
        <div class="upload-tip fault-tip">维保工单完工时也会自动累加故障次数，这里用于人工校正。</div>
      </el-form>
      <template #footer>
        <el-button :disabled="faultSaving" @click="faultDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="faultSaving" @click="submitFault">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.asset-page { padding: 4px; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.toolbar .hint { font-size: 12px; color: #999; }
.filter-card { margin-bottom: 10px; }
.filter-card :deep(.el-card__body) { padding: 10px 12px; display: flex; align-items: center; }
.filter-card .total { margin-left: auto; font-size: 12px; color: #666; }
.detail .block-title { font-weight: 600; margin: 16px 0 8px; border-left: 3px solid #409eff; padding-left: 8px; }
.bar-row { display: flex; align-items: center; gap: 8px; margin: 4px 0; }
.bar-label { width: 90px; font-size: 12px; }
.bar-val { width: 110px; text-align: right; font-size: 12px; }
.bar-val em { color: #999; font-style: normal; }
.cost-foot { font-size: 12px; color: #666; margin-top: 6px; }
.detail-actions { display: flex; justify-content: flex-end; margin-bottom: 10px; }
.block-title-row { display: flex; align-items: center; justify-content: space-between; }
.supplier-tip {
  margin: -6px 0 12px 120px;
  padding: 8px 10px;
  border-radius: 4px;
  background: #f0f9eb;
  color: #529b2e;
  font-size: 12px;
  line-height: 1.6;
}
.supplier-field { display: flex; align-items: center; gap: 8px; width: 100%; }
.supplier-field .el-select { flex: 1; }
.bom-attachment-box { width: 100%; }
.upload-tip { margin-top: 6px; color: #909399; font-size: 12px; line-height: 1.5; }
.attachment-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; min-height: 24px; align-items: center; }
.empty-attachment { color: #c0c4cc; font-size: 12px; }
.attachment-col { flex-direction: column; align-items: flex-start; }
.attachment-col .el-button + .el-button { margin-left: 0; }
.status-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.boq-actions { display: flex; align-items: center; gap: 8px; }
.import-kpi { display: flex; gap: 18px; font-size: 13px; margin-bottom: 8px; }
.import-msg { margin: 0; padding-left: 18px; color: #e6a23c; font-size: 12px; line-height: 1.7; }
.boq-total { display: flex; align-items: center; gap: 8px; margin-top: 8px; font-size: 13px; }
.boq-total .upload-tip { margin-top: 0; }
.manual-tag { margin-left: 4px; }
.inline-tip { margin: 0 0 0 8px; display: inline; }
.pay-head { display: flex; gap: 18px; flex-wrap: wrap; align-items: center; font-size: 13px; margin-bottom: 8px; }
.warn-text { color: #e8a33d; }
.lnk { color: #2e6da4; cursor: pointer; font-weight: 600; }
.lnk:hover { text-decoration: underline; }
.fault-tip { margin-left: 110px; }
</style>
