<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchContracts, fetchContractDetail, signContract, voidContract, renewContract, changeContract,
  editContract, fetchContractFiles, uploadContractFile, CONTRACT_ATTACHMENT_EXTS, CONTRACT_ATTACHMENT_MAX_MB,
  saveContractBoq, importContractBoq, exportContractBoq, generateAssetsFromBoq, BOQ_ASSET_CATEGORIES,
  type ContractListItem, type ContractDetail, type ContractFile, type BoqLine, type BoqImportResult,
} from '@/api/contract'
import { fetchCustomerPool, type CustomerPoolItem } from '@/api/customer'
import { fetchAssets, checkUploadFile, saveFile, type AssetListItem } from '@/api/asset'

const route = useRoute()
const router = useRouter()

// ---- 身份切换(占位头,验证字段级隔离) ----
const identities = [
  { name: '财务', role: '财务' },
  { name: '老板', role: '老板' },
  { name: '供应链', role: '供应链' },
  { name: '投资人', role: 'LP' },
]
const curIdentity = ref(localStorage.getItem('rent_user_name') || '财务')
function switchIdentity(name: string) {
  const id = identities.find((i) => i.name === name)!
  localStorage.setItem('rent_user_name', id.name)
  localStorage.setItem('rent_user_role', id.role)
  curIdentity.value = name
  loadList()
  if (detail.value) openDetail(detail.value.id)
  ElMessage.success(`已切换为 ${id.name}（${id.role}）· 观察单笔P&L/每期构成字段级隔离`)
}

// ---- 客户(客户 CRM) / 设备(设备租赁台账) 选项 ----
const customers = ref<CustomerPoolItem[]>([])
const customersLoading = ref(false)
async function loadCustomers() {
  customersLoading.value = true
  try {
    customers.value = (await fetchCustomerPool({ page: 1, size: 1000 })).records
  } finally {
    customersLoading.value = false
  }
}
const assetOptions = ref<AssetListItem[]>([])
const assetsLoading = ref(false)
async function loadAssets() {
  assetsLoading.value = true
  try {
    assetOptions.value = (await fetchAssets({ page: 1, size: 1000 })).records
  } finally {
    assetsLoading.value = false
  }
}
/** 可签约设备:台账状态为 采购/投放(未在租) */
const signableAssets = computed(() => assetOptions.value.filter((a) => a.status === '采购' || a.status === '投放'))
function goCustomer(id?: number) {
  if (id) router.push({ path: '/customer', query: { id: String(id) } })
}
function goAsset(id?: number) {
  if (id) router.push({ path: '/asset', query: { id: String(id) } })
}

// ---- 列表 ----
const filters = reactive<{ status: string; keyword: string; customerId?: number }>({ status: '', keyword: '' })
const statuses = ['草稿', '生效', '到期转让', '关闭', '已作废']
const list = ref<ContractListItem[]>([])
const total = ref(0)
const loading = ref(false)
async function loadList() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 200 }
    if (filters.status) params.status = filters.status
    if (filters.keyword) params.keyword = filters.keyword
    if (filters.customerId) params.customerId = filters.customerId
    const res = await fetchContracts(params)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}
const statusType: Record<string, string> = { 草稿: 'info', 生效: 'success', 到期转让: 'primary', 关闭: 'warning', 已作废: 'danger' }
function money(v?: number | null) {
  if (v === null || v === undefined) return '🔒'
  return v >= 10000 ? (v / 10000).toFixed(2) + '万' : '¥' + v.toLocaleString()
}

// ---- 详情 ----
const detail = ref<ContractDetail | null>(null)
const drawer = ref(false)
async function openDetail(id: number) {
  detail.value = await fetchContractDetail(id)
  drawer.value = true
  loadFiles()
}

// ---- 签约 ----
const signVisible = ref(false)
const signSaving = ref(false)
const signForm = reactive<Record<string, any>>({})
function resetSign() {
  Object.keys(signForm).forEach((k) => delete signForm[k])
  Object.assign(signForm, {
    no: '', customerId: filters.customerId, termMonths: undefined, monthRent: undefined, deposit: undefined,
    endTransferPrice: undefined, targetIrrPct: undefined, signDate: '', startDate: '', remark: '',
    assets: [{ assetId: undefined, allocRent: undefined }],
  })
}
async function openSign() {
  resetSign()
  signVisible.value = true
  await Promise.all([customers.value.length ? null : loadCustomers(), loadAssets()])
}
function addAssetRow() { signForm.assets.push({ assetId: undefined, allocRent: undefined }) }
function removeAssetRow(i: number) { signForm.assets.splice(i, 1) }
function assetLabel(a: AssetListItem) {
  return `${a.serialNo} · ${a.category}${a.model ? ' · ' + a.model : ''} · ${a.status}`
    + (a.intendedCustomerName ? ` · 意向:${a.intendedCustomerName}` : '')
}
async function submitSign() {
  const assets = signForm.assets.filter((a: any) => a.assetId)
  if (!signForm.no || !signForm.customerId || !assets.length) {
    ElMessage.warning('合同编号、客户、至少 1 台设备必填'); return
  }
  if (new Set(assets.map((a: any) => a.assetId)).size !== assets.length) {
    ElMessage.warning('同一台设备不能重复挂载'); return
  }
  const body: Record<string, any> = { no: signForm.no, customerId: signForm.customerId, assets }
  for (const k of ['termMonths', 'monthRent', 'deposit', 'endTransferPrice', 'signDate', 'startDate', 'remark']) {
    if (signForm[k] !== undefined && signForm[k] !== '' && signForm[k] !== null) body[k] = signForm[k]
  }
  if (signForm.targetIrrPct != null) body.targetIrr = Math.round(Number(signForm.targetIrrPct) * 100) / 10000
  signSaving.value = true
  try {
    const id = await signContract(body)
    ElMessage.success('签约成功 · 已自动生成 N 期租金计划，可在详情里上传合同压缩包')
    signVisible.value = false
    await loadList()
    openDetail(id)
  } finally {
    signSaving.value = false
  }
}

// ---- 合同清单(《工程量清单计价表》;合计 = 设备总价) ----
const boqRows = ref<BoqLine[]>([])
const boqEditing = ref(false)
const boqSaving = ref(false)
const boqImporting = ref(false)
const boqExporting = ref(false)
const boqGenerating = ref(false)
const boqImportResult = ref<BoqImportResult | null>(null)
const boqImportVisible = ref(false)

function rowAmount(r: BoqLine): number | null {
  if (r.amountManual) return r.amount ?? null
  if (r.qty == null || r.unitPrice == null) return null
  return Math.round(Number(r.qty) * Number(r.unitPrice) * 100) / 100
}
const boqTotal = computed(() => boqRows.value.reduce((sum, r) => sum + Number(rowAmount(r) ?? 0), 0))
const boqTaxRate = computed(() => Number(detail.value?.taxRate ?? 0))
const boqWithoutTax = computed(() => (boqTaxRate.value > 0 ? Math.round((boqTotal.value / (1 + boqTaxRate.value)) * 100) / 100 : null))
const boqTaxAmount = computed(() => (boqWithoutTax.value == null ? null : Math.round((boqTotal.value - boqWithoutTax.value) * 100) / 100))

function startBoqEdit() {
  boqRows.value = (detail.value?.boq?.lines || []).map((l) => ({ ...l }))
  if (!boqRows.value.length) addBoqRow()
  boqEditing.value = true
}
function cancelBoqEdit() {
  boqEditing.value = false
  boqRows.value = []
}
function addBoqRow() {
  boqRows.value.push({ name: '', model: null, spec: null, unit: '台', qty: 1, unitPrice: null, amount: null, amountManual: false, assetCategory: null, remark: null })
}
function removeBoqRow(i: number) {
  const row = boqRows.value[i]
  if (row.generatedCount) {
    ElMessage.warning(`「${row.name}」已生成 ${row.generatedCount} 台设备，请先在设备台账里删除这些设备`)
    return
  }
  boqRows.value.splice(i, 1)
}
async function submitBoq() {
  const d = detail.value
  if (!d) return
  if (boqRows.value.some((r) => !String(r.name || '').trim())) {
    ElMessage.warning('清单每行都要填名称')
    return
  }
  boqSaving.value = true
  try {
    await saveContractBoq(d.id, boqRows.value)
    ElMessage.success('合同清单已保存，设备总价已同步为含税合计')
    boqEditing.value = false
    await openDetail(d.id)
    loadList()
  } finally {
    boqSaving.value = false
  }
}
function beforeBoqImport(file: File) {
  const err = checkUploadFile(file, ['xls', 'xlsx'], 10)
  if (err) {
    ElMessage.warning(err)
    return false
  }
  return true
}
async function boqImportRequest(options: any) {
  const d = detail.value
  if (!d) return
  const file = options.file as File
  try {
    await ElMessageBox.confirm(
      `导入「${file.name}」会整表替换本合同的清单（原有清单行删除；已生成设备的行会拦下来），合计将同步为合同设备总价。确认导入？`,
      '导入合同清单', { type: 'warning', confirmButtonText: '确认导入' },
    )
  } catch {
    return
  }
  boqImporting.value = true
  try {
    boqImportResult.value = await importContractBoq(d.id, file)
    boqImportVisible.value = true
    boqEditing.value = false
    await openDetail(d.id)
    loadList()
  } finally {
    boqImporting.value = false
  }
}
async function onExportBoq(template = false) {
  const d = detail.value
  if (!d) return
  boqExporting.value = true
  try {
    await exportContractBoq(d.id, d.no, template)
    ElMessage.success(template ? '模板已下载' : '已导出，可修改后再导入回系统')
  } finally {
    boqExporting.value = false
  }
}
async function onGenerateAssets() {
  const d = detail.value
  if (!d) return
  const pending = d.boq?.pendingAssets || 0
  if (!pending) {
    ElMessage.warning('没有待生成的设备：请在清单行的「生成设备品类」里选好品类，数量要大于已生成台数')
    return
  }
  try {
    await ElMessageBox.confirm(
      `按清单行数量生成 ${pending} 台设备，挂到本合同下（状态=采购，合同价取该行单价）。确认生成？`,
      '按清单生成设备', { type: 'warning', confirmButtonText: '确认生成' },
    )
  } catch {
    return
  }
  boqGenerating.value = true
  try {
    const res = await generateAssetsFromBoq(d.id)
    ElMessage.success(`已生成 ${res.created} 台设备` + (res.messages.length ? `：${res.messages.join('；')}` : ''))
    await openDetail(d.id)
    loadList()
  } finally {
    boqGenerating.value = false
  }
}
function goAssetsOfContract() {
  if (detail.value) router.push({ path: '/asset', query: { contractId: String(detail.value.id) } })
}

// ---- 编辑合同要素 ----
const editVisible = ref(false)
const editSaving = ref(false)
const editForm = reactive<Record<string, any>>({})
const canEdit = computed(() => !!detail.value && !detail.value.sensitiveMasked
  && (detail.value.status === '草稿' || detail.value.status === '生效'))
async function openEdit() {
  const d = detail.value
  if (!d) return
  Object.keys(editForm).forEach((k) => delete editForm[k])
  Object.assign(editForm, {
    customerId: d.customerId,
    nature: d.nature || '分期收款销售',
    targetIrrPct: d.targetIrr == null ? null : Math.round(d.targetIrr * 10000) / 100,
    taxRatePct: d.taxRate == null ? null : Math.round(d.taxRate * 1000000) / 10000,
    termMonths: d.termMonths,
    monthRent: d.monthRent,
    endTransferPrice: d.endTransferPrice,
    deposit: d.deposit,
    startDate: d.startDate,
    signDate: d.signDate,
    remark: d.remark || '',
    detail: '',
  })
  editVisible.value = true
  if (!customers.value.length) await loadCustomers()
}
const billedPeriods = computed(() => detail.value?.schedule.filter((s) => s.planStatus === '已生成单').length || 0)
async function submitEdit() {
  const d = detail.value
  if (!d) return
  if (!editForm.customerId || !editForm.termMonths || editForm.monthRent == null || !editForm.startDate) {
    ElMessage.warning('客户、租期、月租、起租日必填'); return
  }
  if (d.status === '生效') {
    try {
      await ElMessageBox.confirm(
        `生效合同的修改会：保留已生成收租单的 ${billedPeriods.value} 期，其余期次按新租期、月租、起租日重新生成；押金有变化时自动记补收或退回；改客户会同步在租设备的承租客户。全部写入变更留痕。确认保存？`,
        '修改生效合同', { type: 'warning', confirmButtonText: '确认修改' },
      )
    } catch {
      return
    }
  }
  editSaving.value = true
  try {
    await editContract(d.id, {
      customerId: editForm.customerId,
      nature: editForm.nature,
      targetIrr: editForm.targetIrrPct == null ? null : Math.round(Number(editForm.targetIrrPct) * 100) / 10000,
      taxRate: editForm.taxRatePct == null ? null : Math.round(Number(editForm.taxRatePct) * 1000000) / 100000000,
      termMonths: editForm.termMonths,
      monthRent: editForm.monthRent,
      endTransferPrice: editForm.endTransferPrice ?? null,
      deposit: editForm.deposit ?? null,
      startDate: editForm.startDate,
      signDate: editForm.signDate || null,
      remark: editForm.remark,
      detail: editForm.detail,
    })
    editVisible.value = false
    ElMessage.success('合同已更新')
    await openDetail(d.id)
    loadList()
  } finally {
    editSaving.value = false
  }
}

// ---- 合同附件(压缩文件) ----
const files = ref<ContractFile[]>([])
const filesLoading = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const fileAccept = CONTRACT_ATTACHMENT_EXTS.map((e) => '.' + e).join(',')
async function loadFiles() {
  if (!detail.value || detail.value.sensitiveMasked) {
    files.value = []
    return
  }
  filesLoading.value = true
  try {
    files.value = await fetchContractFiles(detail.value.id)
  } catch {
    files.value = []
  } finally {
    filesLoading.value = false
  }
}
function beforeUpload(file: File) {
  const err = checkUploadFile(file, CONTRACT_ATTACHMENT_EXTS, CONTRACT_ATTACHMENT_MAX_MB)
  if (err) {
    ElMessage.warning(err)
    return false
  }
  return true
}
async function uploadRequest(options: any) {
  if (!detail.value) return
  uploading.value = true
  uploadPercent.value = 0
  try {
    await uploadContractFile(detail.value.id, options.file as File, (p) => { uploadPercent.value = p })
    ElMessage.success('合同附件已上传')
    await loadFiles()
  } finally {
    uploading.value = false
  }
}
function fileSize(bytes: number) {
  return bytes >= 1024 * 1024 ? (bytes / 1024 / 1024).toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' KB'
}

// ---- 逆向操作 ----
async function doVoid() {
  if (!detail.value) return
  const { value } = await ElMessageBox.prompt('作废原因(整份红冲,限未采购)', '合同作废', { inputType: 'textarea' })
  await voidContract(detail.value.id, { detail: value })
  ElMessage.success('已作废(整份红冲)')
  openDetail(detail.value.id); loadList()
}
async function doRenew() {
  if (!detail.value) return
  const { value } = await ElMessageBox.prompt('续租追加期数(月)', '续租', { inputPattern: /^\d+$/, inputErrorMessage: '请输入正整数' })
  await renewContract(detail.value.id, { renewMonths: Number(value), detail: '续租' })
  ElMessage.success(`已续租 +${value} 期`)
  openDetail(detail.value.id); loadList()
}
async function doChange() {
  if (!detail.value) return
  await ElMessageBox.confirm('提前结清:截断剩余未到期计划,合同关闭,押金期末抵?', '提前结清', { type: 'warning' })
  await changeContract(detail.value.id, { detail: '提前结清' })
  ElMessage.success('已提前结清')
  openDetail(detail.value.id); loadList()
}

const filterCustomerName = computed(() =>
  filters.customerId ? (customers.value.find((c) => c.id === filters.customerId)?.name || `客户#${filters.customerId}`) : '')
function clearCustomerFilter() {
  filters.customerId = undefined
  router.replace({ path: '/contract' })
  loadList()
}

// 从客户详情跳转过来:?id= 打开合同详情;?customerId= 只看该客户的合同
onMounted(() => {
  const cid = Number(route.query.customerId)
  if (cid) filters.customerId = cid
  loadList()
  loadCustomers()
  const id = Number(route.query.id)
  if (id) openDetail(id)
})
</script>

<template>
  <div class="contract-page">
    <div class="toolbar">
      <el-radio-group :model-value="curIdentity" @change="switchIdentity" size="small">
        <el-radio-button v-for="i in identities" :key="i.name" :value="i.name">{{ i.name }}</el-radio-button>
      </el-radio-group>
      <span class="hint">投资人(GP/LP)看不到单笔P&L/每期构成/集采成本 🔒</span>
      <el-button type="primary" size="small" style="margin-left:auto" @click="openSign">+ 签约</el-button>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-select v-model="filters.status" placeholder="状态" clearable size="small" style="width:130px" @change="loadList">
        <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
      </el-select>
      <el-select v-model="filters.customerId" placeholder="客户" clearable filterable size="small" style="width:200px;margin-left:8px"
        :loading="customersLoading" @change="loadList" @clear="clearCustomerFilter">
        <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-input v-model="filters.keyword" placeholder="合同编号" clearable size="small" style="width:180px;margin-left:8px" @keyup.enter="loadList" />
      <el-button size="small" style="margin-left:8px" @click="loadList">查询</el-button>
      <el-tag v-if="filters.customerId" size="small" closable style="margin-left:8px" @close="clearCustomerFilter">只看：{{ filterCustomerName }}</el-tag>
      <span class="total">共 {{ total }} 份</span>
    </el-card>

    <el-table :data="list" v-loading="loading" size="small" @row-click="(r:any) => openDetail(r.id)" style="cursor:pointer">
      <el-table-column prop="no" label="合同编号" width="150" />
      <el-table-column label="客户" min-width="140">
        <template #default="{ row }"><a class="lnk" @click.stop="goCustomer(row.customerId)">{{ row.customerName }}</a></template>
      </el-table-column>
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="statusType[row.status] || 'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="termMonths" label="租期(月)" width="90" />
      <el-table-column label="月租" width="100"><template #default="{ row }">{{ money(row.monthRent) }}</template></el-table-column>
      <el-table-column label="转让价" width="100"><template #default="{ row }">{{ money(row.endTransferPrice) }}</template></el-table-column>
      <el-table-column prop="assetCount" label="设备(台)" width="90" />
      <el-table-column label="回款进度" width="130"><template #default="{ row }">
        <el-progress :percentage="row.termMonths ? Math.round((row.elapsedPeriods || 0) / row.termMonths * 100) : 0" :stroke-width="12" />
      </template></el-table-column>
    </el-table>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer" :title="detail ? `合同 ${detail.no} · ${detail.status}` : '合同详情'" size="62%">
      <div v-if="detail" class="detail">
        <el-alert v-if="detail.sensitiveMasked" type="info" :closable="false" show-icon
          title="投资人视角:单笔P&L/每期构成/集采成本已打码 🔒(勾稽与客户总付仍可见)" style="margin-bottom:12px" />

        <!-- 操作 -->
        <div style="margin-bottom:12px">
          <el-button size="small" type="primary" :disabled="!canEdit" @click="openEdit">编辑合同</el-button>
          <el-button size="small" :disabled="detail.status !== '生效'" @click="doRenew">续租</el-button>
          <el-button size="small" :disabled="detail.status !== '生效'" @click="doChange">提前结清</el-button>
          <el-button size="small" type="danger" :disabled="detail.status === '已作废' || detail.status === '关闭'" @click="doVoid">作废(红冲)</el-button>
        </div>

        <!-- 要点 -->
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="客户"><a class="lnk" @click="goCustomer(detail.customerId)">{{ detail.customerName }}</a></el-descriptions-item>
          <el-descriptions-item label="性质">{{ detail.nature }}</el-descriptions-item>
          <el-descriptions-item label="目标IRR">{{ detail.targetIrr != null ? (detail.targetIrr * 100).toFixed(2).replace(/\.?0+$/, '') + '%' : '—' }}</el-descriptions-item>
          <el-descriptions-item label="租期">{{ detail.termMonths }} 月</el-descriptions-item>
          <el-descriptions-item label="月租">{{ money(detail.monthRent) }}</el-descriptions-item>
          <el-descriptions-item label="期末转让价">{{ money(detail.endTransferPrice) }}</el-descriptions-item>
          <el-descriptions-item label="押金">{{ money(detail.deposit) }}</el-descriptions-item>
          <el-descriptions-item label="设备总价(含税)">{{ detail.equipmentTotal == null ? '—' : money(detail.equipmentTotal) }}</el-descriptions-item>
          <el-descriptions-item label="合同税率">{{ detail.taxRate == null ? '—' : (detail.taxRate * 100).toFixed(2).replace(/\.?0+$/, '') + '%' }}</el-descriptions-item>
          <el-descriptions-item label="起租日">{{ detail.startDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="签约日">{{ detail.signDate || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.remark" label="备注" :span="3">{{ detail.remark }}</el-descriptions-item>
        </el-descriptions>

        <!-- 合同附件 -->
        <div class="block-title block-title-row">
          <span>合同附件（压缩文件）</span>
          <el-upload v-if="!detail.sensitiveMasked" :http-request="uploadRequest" :before-upload="beforeUpload" :accept="fileAccept"
            :show-file-list="false" :disabled="uploading">
            <el-button type="primary" link size="small" :loading="uploading">上传压缩包</el-button>
          </el-upload>
        </div>
        <div v-if="detail.sensitiveMasked" class="sub">当前角色不可查看合同附件 🔒</div>
        <template v-else>
          <div class="sub">仅支持 {{ CONTRACT_ATTACHMENT_EXTS.join('/') }}，单个不超过 {{ CONTRACT_ATTACHMENT_MAX_MB / 1024 }}GB。</div>
          <el-progress v-if="uploading" :percentage="uploadPercent" :stroke-width="10" style="margin:6px 0" />
          <el-table v-loading="filesLoading" :data="files" size="small" border style="margin-top:6px">
            <el-table-column prop="fileName" label="文件" min-width="200" show-overflow-tooltip />
            <el-table-column label="大小" width="90"><template #default="{ row }">{{ fileSize(row.size) }}</template></el-table-column>
            <el-table-column label="上传人" width="90"><template #default="{ row }">{{ row.uploaderName || '—' }}</template></el-table-column>
            <el-table-column label="" width="70">
              <template #default="{ row }"><el-button link type="primary" size="small" @click="saveFile(row.id, row.fileName)">下载</el-button></template>
            </el-table-column>
          </el-table>
        </template>

        <!-- 勾稽校验行 -->
        <div class="block-title">勾稽校验(期数×月租 + 转让价 = 客户总付,不含押金)</div>
        <div class="recon" :class="{ ok: detail.reconciliation.balanced && detail.reconciliation.scheduleBalanced }">
          <span>{{ detail.reconciliation.periods }} 期 × {{ money(detail.reconciliation.monthRent) }}</span>
          <span>= {{ money(detail.reconciliation.rentTotal) }}</span>
          <span>+ 转让 {{ money(detail.reconciliation.endTransferPrice) }}</span>
          <span class="eq">= 客户总付 {{ money(detail.reconciliation.customerTotal) }}</span>
          <el-tag :type="detail.reconciliation.balanced && detail.reconciliation.scheduleBalanced ? 'success' : 'danger'" size="small">
            {{ detail.reconciliation.balanced && detail.reconciliation.scheduleBalanced ? '✓ 勾稽对平' : '✗ 不平' }}
          </el-tag>
          <span class="deposit">押金(单列不进总付) {{ money(detail.reconciliation.deposit) }}</span>
        </div>

        <!-- 回款进度 -->
        <div class="block-title">回款进度(计划态口径;真实收款态归 M2)</div>
        <el-progress :percentage="Math.round(detail.repayment.progressRatio * 100)" :stroke-width="16" />
        <div class="sub">已过 {{ detail.repayment.elapsedPeriods }} / {{ detail.repayment.totalPeriods }} 期 · 已生成收租单 {{ detail.repayment.billedPeriods }} 期</div>

        <!-- 每期租金构成 -->
        <div class="block-title" v-if="detail.rentComposition">每期租金构成</div>
        <el-descriptions v-if="detail.rentComposition" :column="5" border size="small">
          <el-descriptions-item label="月租">{{ money(detail.rentComposition.monthRent) }}</el-descriptions-item>
          <el-descriptions-item label="本金摊">{{ money(detail.rentComposition.principal) }}</el-descriptions-item>
          <el-descriptions-item label="资金成本">{{ money(detail.rentComposition.capitalCost) }}</el-descriptions-item>
          <el-descriptions-item label="残值预留">{{ money(detail.rentComposition.residualReserve) }}</el-descriptions-item>
          <el-descriptions-item label="差价(毛利)">{{ money(detail.rentComposition.margin) }}</el-descriptions-item>
        </el-descriptions>

        <!-- 单笔 P&L -->
        <div class="block-title" v-if="detail.pnl">单笔 P&amp;L(按合同归集 · 经营口径简化)</div>
        <el-descriptions v-if="detail.pnl" :column="3" border size="small">
          <el-descriptions-item label="收租总额">{{ money(detail.pnl.rentTotal) }}</el-descriptions-item>
          <el-descriptions-item label="+ 期末转让">{{ money(detail.pnl.transferPrice) }}</el-descriptions-item>
          <el-descriptions-item label="− 集采成本">{{ money(detail.pnl.purchaseCost) }}</el-descriptions-item>
          <el-descriptions-item label="− 资金成本">{{ money(detail.pnl.capitalCost) }}</el-descriptions-item>
          <el-descriptions-item label="− 坏账拨备">{{ money(detail.pnl.badDebtReserve) }}</el-descriptions-item>
          <el-descriptions-item label="= 税后净利"><strong style="color:#67c23a">{{ money(detail.pnl.netProfit) }}</strong> ({{ (detail.pnl.netMargin * 100).toFixed(1) }}%)</el-descriptions-item>
        </el-descriptions>

        <!-- 合同清单(《工程量清单计价表》) -->
        <div class="block-title block-title-row">
          <span>合同清单（工程量清单计价表）</span>
          <span v-if="!detail.sensitiveMasked" class="boq-actions">
            <el-button link type="primary" size="small" :loading="boqExporting" @click="onExportBoq(false)">⬇ 导出</el-button>
            <el-button link type="primary" size="small" :loading="boqExporting" @click="onExportBoq(true)">下载模板</el-button>
            <el-upload :http-request="boqImportRequest" :before-upload="beforeBoqImport" accept=".xls,.xlsx"
              :show-file-list="false" :disabled="boqImporting" style="display:inline-block">
              <el-button link type="primary" size="small" :loading="boqImporting">⬆ 导入</el-button>
            </el-upload>
            <el-button v-if="!boqEditing" link type="primary" size="small" @click="startBoqEdit">编辑</el-button>
            <template v-else>
              <el-button link type="primary" size="small" @click="addBoqRow">+ 加一行</el-button>
              <el-button link type="primary" size="small" :loading="boqSaving" @click="submitBoq">保存</el-button>
              <el-button link size="small" :disabled="boqSaving" @click="cancelBoqEdit">取消</el-button>
            </template>
            <el-button v-if="!boqEditing" link type="success" size="small" :loading="boqGenerating" @click="onGenerateAssets">
              一键生成设备{{ detail.boq?.pendingAssets ? `（待生成 ${detail.boq.pendingAssets} 台）` : '' }}
            </el-button>
          </span>
        </div>
        <div v-if="detail.sensitiveMasked" class="sub">当前角色不可查看清单金额 🔒</div>
        <template v-else>
          <el-table v-if="!boqEditing" :data="detail.boq?.lines || []" size="small" border>
            <el-table-column label="序号" width="60" align="center"><template #default="{ $index }">{{ $index + 1 }}</template></el-table-column>
            <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
            <el-table-column prop="model" label="型号" min-width="110" show-overflow-tooltip />
            <el-table-column prop="spec" label="规格" min-width="150" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="60" align="center" />
            <el-table-column prop="qty" label="数量" width="80" align="right" />
            <el-table-column label="单价🔒" width="110" align="right"><template #default="{ row }">{{ row.unitPrice == null ? '—' : money(row.unitPrice) }}</template></el-table-column>
            <el-table-column label="金额🔒" width="120" align="right"><template #default="{ row }">{{ row.amount == null ? '-' : money(row.amount) }}</template></el-table-column>
            <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
            <el-table-column label="生成设备" width="120">
              <template #default="{ row }">
                <span v-if="!row.assetCategory" class="sub">—</span>
                <template v-else>
                  {{ row.assetCategory }}
                  <div class="sub">已生成 {{ row.generatedCount || 0 }}{{ row.pendingCount ? ` · 待 ${row.pendingCount}` : '' }}</div>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <el-table v-else :data="boqRows" size="small" border>
            <el-table-column label="序号" width="55" align="center"><template #default="{ $index }">{{ $index + 1 }}</template></el-table-column>
            <el-table-column label="名称" min-width="130"><template #default="{ row }"><el-input v-model="row.name" size="small" maxlength="128" /></template></el-table-column>
            <el-table-column label="型号" min-width="100"><template #default="{ row }"><el-input v-model="row.model" size="small" maxlength="128" /></template></el-table-column>
            <el-table-column label="规格" min-width="120"><template #default="{ row }"><el-input v-model="row.spec" size="small" maxlength="255" /></template></el-table-column>
            <el-table-column label="单位" width="70"><template #default="{ row }"><el-input v-model="row.unit" size="small" maxlength="16" /></template></el-table-column>
            <el-table-column label="数量" width="95"><template #default="{ row }"><el-input-number v-model="row.qty" size="small" :min="0" :precision="2" :controls="false" style="width:100%" /></template></el-table-column>
            <el-table-column label="单价" width="105"><template #default="{ row }"><el-input-number v-model="row.unitPrice" size="small" :precision="2" :controls="false" style="width:100%" /></template></el-table-column>
            <el-table-column label="金额" width="145">
              <template #default="{ row }">
                <el-input-number v-if="row.amountManual" v-model="row.amount" size="small" :precision="2" :controls="false" style="width:100%" />
                <span v-else>{{ rowAmount(row) == null ? '-' : money(rowAmount(row)) }}</span>
                <el-checkbox v-model="row.amountManual" size="small">手填</el-checkbox>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="120"><template #default="{ row }"><el-input v-model="row.remark" size="small" maxlength="500" /></template></el-table-column>
            <el-table-column label="生成设备品类" width="130">
              <template #default="{ row }">
                <el-select v-model="row.assetCategory" size="small" clearable placeholder="不生成">
                  <el-option v-for="c in BOQ_ASSET_CATEGORIES" :key="c" :label="c" :value="c" />
                </el-select>
                <div v-if="row.generatedCount" class="sub">已生成 {{ row.generatedCount }}</div>
              </template>
            </el-table-column>
            <el-table-column label="" width="50">
              <template #default="{ $index }"><el-button link type="danger" size="small" @click="removeBoqRow($index)">删</el-button></template>
            </el-table-column>
          </el-table>
          <div class="boq-total">
            <template v-if="boqEditing">
              合计(含税) <b>{{ money(boqTotal) }}</b>
              <span v-if="boqWithoutTax != null" class="sub">不含税 {{ money(boqWithoutTax) }} · 税额 {{ money(boqTaxAmount) }}</span>
            </template>
            <template v-else-if="detail.boq?.linked">
              设备总价(含税) <b>{{ money(detail.boq.totalWithTax) }}</b>
              <span v-if="detail.boq.totalWithoutTax != null" class="sub">不含税 {{ money(detail.boq.totalWithoutTax) }} · 税额 {{ money(detail.boq.taxAmount) }}（税率 {{ ((detail.boq.taxRate || 0) * 100).toFixed(2).replace(/\.?0+$/, '') }}%）</span>
              <span v-else class="sub">在「修改合同」里填合同税率后可拆出不含税金额与税额</span>
              <span v-if="detail.boq.generatedAssets" class="sub">已按清单生成 {{ detail.boq.generatedAssets }} 台设备 · <a class="lnk" @click="goAssetsOfContract">查看</a></span>
              <div v-if="detail.boq.totalUpper" class="sub">{{ detail.boq.totalUpper }}</div>
            </template>
            <span v-else class="sub">暂无清单：可点「导入」按《工程量清单计价表》导入，或「编辑」手工录入。</span>
          </div>
        </template>

        <!-- 挂设备 -->
        <div class="block-title">挂载设备(单台分摊 · 关联设备租赁台账)</div>
        <el-table :data="detail.assets" size="small" border>
          <el-table-column label="序列号" width="150">
            <template #default="{ row }"><a class="lnk" @click="goAsset(row.assetId)">{{ row.serialNo || ('#' + row.assetId) }}</a></template>
          </el-table-column>
          <el-table-column prop="category" label="品类" width="90" />
          <el-table-column prop="model" label="型号" min-width="140" show-overflow-tooltip />
          <el-table-column prop="assetStatus" label="台账状态" width="100" />
          <el-table-column label="单台月租分摊" width="130"><template #default="{ row }">{{ money(row.allocRent) }}</template></el-table-column>
        </el-table>

        <!-- 租金计划逐期 -->
        <div class="block-title">租金计划表(逐期)</div>
        <el-table :data="detail.schedule" size="small" border max-height="280">
          <el-table-column prop="periodNo" label="期次" width="70" />
          <el-table-column prop="dueDate" label="到期日" width="120" />
          <el-table-column label="应收"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
          <el-table-column label="计划态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.planStatus === '已生成单' ? 'success' : 'info'">{{ row.planStatus }}</el-tag></template></el-table-column>
          <el-table-column label="到期" width="90"><template #default="{ row }"><el-tag size="small" :type="row.dueState === '已到期' ? 'warning' : 'info'">{{ row.dueState }}</el-tag></template></el-table-column>
        </el-table>

        <!-- 押金台账 + 变更留痕 -->
        <el-row :gutter="12">
          <el-col :span="12">
            <div class="block-title">押金台账</div>
            <el-table :data="detail.depositLedger" size="small" border>
              <el-table-column prop="direction" label="方向" width="80" />
              <el-table-column label="金额"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
              <el-table-column prop="remark" label="备注" show-overflow-tooltip />
            </el-table>
          </el-col>
          <el-col :span="12">
            <div class="block-title">变更留痕</div>
            <el-table :data="detail.changes" size="small" border>
              <el-table-column prop="changeType" label="类型" width="90" />
              <el-table-column label="红冲" width="60"><template #default="{ row }">{{ row.isReverse ? '是' : '否' }}</template></el-table-column>
              <el-table-column prop="detail" label="说明" show-overflow-tooltip />
            </el-table>
          </el-col>
        </el-row>
      </div>
    </el-drawer>

    <!-- 签约弹窗 -->
    <el-dialog v-model="signVisible" title="签约(自动生成 N 期租金计划)" width="680px" :close-on-click-modal="false">
      <el-form :model="signForm" label-width="120px" size="small" :disabled="signSaving">
        <el-form-item label="合同编号" required><el-input v-model="signForm.no" placeholder="HT-2026-0004" /></el-form-item>
        <el-form-item label="客户" required>
          <el-select v-model="signForm.customerId" filterable :loading="customersLoading" placeholder="从客户 CRM 选择" style="width:100%">
            <el-option v-for="c in customers" :key="c.id" :label="c.name + (c.contact ? ' · ' + c.contact : '')" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="租期(月)"><el-input-number v-model="signForm.termMonths" :min="1" placeholder="缺省按品类" controls-position="right" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="月租合计(元)"><el-input-number v-model="signForm.monthRent" :min="0" :step="500" placeholder="缺省=Σ单台分摊" controls-position="right" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="期末转让价(元)"><el-input-number v-model="signForm.endTransferPrice" :min="0" :step="1000" controls-position="right" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="押金(元)"><el-input-number v-model="signForm.deposit" :min="0" :step="1000" placeholder="缺省按押金月数" controls-position="right" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="目标IRR(%)"><el-input-number v-model="signForm.targetIrrPct" :min="0" :max="100" :precision="2" controls-position="right" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="签约日"><el-date-picker v-model="signForm.signDate" type="date" value-format="YYYY-MM-DD" placeholder="缺省今天" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="起租日"><el-date-picker v-model="signForm.startDate" type="date" value-format="YYYY-MM-DD" placeholder="缺省签约日" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="性质"><el-input model-value="分期收款销售" disabled /></el-form-item></el-col>
        </el-row>
        <el-form-item label="挂载设备" required>
          <div style="width:100%">
            <div v-for="(a, i) in signForm.assets" :key="i" style="display:flex;gap:8px;margin-bottom:6px">
              <el-select v-model="a.assetId" filterable :loading="assetsLoading" placeholder="按序列号选择设备租赁台账中的设备" style="flex:1">
                <el-option v-for="opt in signableAssets" :key="opt.id" :label="assetLabel(opt)" :value="opt.id" />
              </el-select>
              <el-input-number v-model="a.allocRent" :min="0" :step="500" placeholder="单台分摊(可空)" controls-position="right" style="width:150px" />
              <el-button size="small" @click="removeAssetRow(i)" :disabled="signForm.assets.length === 1">删</el-button>
            </div>
            <el-button size="small" @click="addAssetRow">+ 加一台</el-button>
            <div class="sub">只列出台账状态为「采购」「投放」的设备；单台分摊都留空则按月租均摊。</div>
          </div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="signForm.remark" maxlength="255" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="signSaving" @click="signVisible = false">取消</el-button><el-button type="primary" :loading="signSaving" @click="submitSign">签约</el-button></template>
    </el-dialog>

    <!-- 合同清单导入结果 -->
    <el-dialog v-model="boqImportVisible" title="导入结果" width="560px">
      <template v-if="boqImportResult">
        <div class="import-kpi">
          <span>导入 <b>{{ boqImportResult.imported }}</b> 行</span>
          <span>设备总价(含税) <b>{{ money(boqImportResult.totalWithTax) }}</b></span>
        </div>
        <ul v-if="boqImportResult.messages.length" class="import-msg">
          <li v-for="(m, i) in boqImportResult.messages" :key="i">{{ m }}</li>
        </ul>
        <div v-else class="sub">没有需要注意的行，清单与表格一致。</div>
      </template>
      <template #footer><el-button type="primary" @click="boqImportVisible = false">知道了</el-button></template>
    </el-dialog>

    <!-- 编辑合同要素 -->
    <el-dialog v-model="editVisible" :title="detail ? `编辑合同 ${detail.no}（${detail.status}）` : '编辑合同'" width="680px" :close-on-click-modal="false">
      <el-alert v-if="detail?.status === '生效'" type="warning" :closable="false" show-icon style="margin-bottom:12px"
        :title="`生效合同：已生成收租单的 ${billedPeriods} 期保留，其余期次按新要素重排；押金变化自动补收或退回；全部写入变更留痕。`" />
      <el-form :model="editForm" label-width="120px" size="small" :disabled="editSaving">
        <el-form-item label="客户" required>
          <el-select v-model="editForm.customerId" filterable :loading="customersLoading" style="width:100%">
            <el-option v-for="c in customers" :key="c.id" :label="c.name + (c.contact ? ' · ' + c.contact : '')" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="性质">
              <el-select v-model="editForm.nature" style="width:100%"><el-option label="分期收款销售" value="分期收款销售" /></el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="目标IRR(%)"><el-input-number v-model="editForm.targetIrrPct" :min="0" :max="100" :precision="2" controls-position="right" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="合同税率(%)"><el-input-number v-model="editForm.taxRatePct" :min="0" :max="100" :precision="2" controls-position="right" style="width:100%" placeholder="如 13" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="租期(月)" required><el-input-number v-model="editForm.termMonths" :min="Math.max(1, billedPeriods)" :precision="0" controls-position="right" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="月租(元)" required><el-input-number v-model="editForm.monthRent" :min="0" :precision="2" :step="500" controls-position="right" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="期末转让价(元)"><el-input-number v-model="editForm.endTransferPrice" :min="0" :precision="2" :step="1000" controls-position="right" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="押金(元)"><el-input-number v-model="editForm.deposit" :min="0" :precision="2" :step="1000" controls-position="right" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="起租日" required><el-date-picker v-model="editForm.startDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="签约日"><el-date-picker v-model="editForm.signDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="备注"><el-input v-model="editForm.remark" maxlength="255" /></el-form-item>
        <el-form-item label="变更说明"><el-input v-model="editForm.detail" maxlength="255" placeholder="写入变更留痕，如：客户要求延长租期" /></el-form-item>
        <div class="sub edit-tip">月租变化时，挂载设备的单台分摊按原比例重新分配。需要财务或老板角色。</div>
      </el-form>
      <template #footer><el-button :disabled="editSaving" @click="editVisible = false">取消</el-button><el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.boq-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.boq-total { display: flex; align-items: center; gap: 10px; margin-top: 8px; font-size: 13px; flex-wrap: wrap; }
.import-kpi { display: flex; gap: 18px; font-size: 13px; margin-bottom: 8px; }
.import-msg { margin: 0; padding-left: 18px; color: #e6a23c; font-size: 12px; line-height: 1.7; }
.contract-page { padding: 4px; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.toolbar .hint { font-size: 12px; color: #999; }
.filter-card { margin-bottom: 10px; }
.filter-card :deep(.el-card__body) { padding: 10px 12px; display: flex; align-items: center; }
.filter-card .total { margin-left: auto; font-size: 12px; color: #666; }
.detail .block-title { font-weight: 600; margin: 16px 0 8px; border-left: 3px solid #409eff; padding-left: 8px; }
.block-title-row { display: flex; align-items: center; justify-content: space-between; }
.recon { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 10px 12px; background: #fef0f0; border-radius: 6px; font-size: 13px; }
.recon.ok { background: #f0f9eb; }
.recon .eq { font-weight: 600; }
.recon .deposit { color: #909399; font-size: 12px; margin-left: auto; }
.sub { font-size: 12px; color: #666; margin-top: 6px; }
.edit-tip { margin-left: 120px; }
.lnk { color: #2e6da4; cursor: pointer; font-weight: 600; }
.lnk:hover { text-decoration: underline; }
</style>
