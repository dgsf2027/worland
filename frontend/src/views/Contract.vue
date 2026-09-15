<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchContracts, fetchContractDetail, signContract, voidContract, renewContract, changeContract,
  editContract, fetchContractFiles, uploadContractFile, CONTRACT_ATTACHMENT_EXTS, CONTRACT_ATTACHMENT_MAX_MB,
  type ContractListItem, type ContractDetail, type ContractFile,
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
