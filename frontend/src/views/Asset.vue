<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchAssets, fetchAssetDetail, createAsset, updateAsset, changeAssetStatus,
  addBom, updateBom, deleteBom, uploadBomAttachment, fetchBomAttachments, fetchFileSignedUrl,
  type AssetListItem, type AssetDetail, type BomNode, type BomAttachment,
} from '@/api/asset'
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
    if (assetDialogVisible.value) assetForm.supplierId = supplierId
    supplierDialogVisible.value = false
    ElMessage.success('供应商已录入，并已加入供应商列表')
  } finally {
    supplierSaving.value = false
  }
}

const list = ref<AssetListItem[]>([])
const total = ref(0)
const loading = ref(false)

async function loadList() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 50 }
    if (filters.status) params.status = filters.status
    if (filters.category) params.category = filters.category
    if (filters.keyword) params.keyword = filters.keyword
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

// ---- 状态机允许流转(前端提示,后端强制) ----
const transitions: Record<string, string[]> = {
  采购: ['投放', '报废'],
  投放: ['在租', '收回待处置', '报废'],
  在租: ['待转让', '收回待处置'],
  待转让: ['已转让', '收回待处置'],
  收回待处置: ['投放', '已转让', '报废'],
  已转让: [],
  报废: [],
}

// ---- 详情抽屉 ----
const detail = ref<AssetDetail | null>(null)
const drawer = ref(false)
async function openDetail(id: number) {
  detail.value = await fetchAssetDetail(id)
  drawer.value = true
}
async function doChangeStatus(to: string) {
  if (!detail.value) return
  await ElMessageBox.confirm(`确认将设备 ${detail.value.serialNo} 流转到「${to}」？`, '状态流转', { type: 'warning' })
  await changeAssetStatus(detail.value.id, { targetStatus: to })
  ElMessage.success(`已流转到 ${to}`)
  openDetail(detail.value.id)
  loadList()
}

// ---- 新建 / 编辑设备 ----
const assetDialogVisible = ref(false)
const assetDialogMode = ref<'create' | 'edit'>('create')
const assetForm = reactive<Record<string, any>>({
  serialNo: '', category: '播种墙', model: '', marketPrice: undefined,
  purchasePrice: undefined, supplierId: undefined, monthlyLaborValue: undefined,
  replaceHeadcount: undefined, remark: '',
})

function resetAssetForm() {
  Object.assign(assetForm, {
    serialNo: '', category: '播种墙', model: '', marketPrice: undefined,
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
    serialNo: detail.value.serialNo,
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
  if (!assetForm.serialNo || !assetForm.category) {
    ElMessage.warning('序列号/品类必填')
    return
  }
  if (assetDialogMode.value === 'edit' && detail.value) {
    await updateAsset(detail.value.id, { ...assetForm })
    ElMessage.success('设备资料已更新')
    await openDetail(detail.value.id)
  } else {
    await createAsset({ ...assetForm })
    ElMessage.success('设备已建档(状态=采购)')
  }
  assetDialogVisible.value = false
  await loadList()
}

// ---- BOM 拆解维护 ----
const bomDialogVisible = ref(false)
const bomDialogMode = ref<'create' | 'edit'>('create')
const bomForm = reactive<Record<string, any>>({
  id: undefined, parentId: undefined, parentName: '一级总成', name: '', qty: 1,
  unitCost: undefined, supplierId: undefined, lifeYears: undefined,
  warrantyUntil: undefined, repairable: true, faultCount: 0,
  residualRate: undefined, remark: '',
})
const bomSubtotal = computed(() => Number(bomForm.qty || 0) * Number(bomForm.unitCost || 0))
const bomAttachments = ref<BomAttachment[]>([])
const queuedBomFiles = ref<File[]>([])
const bomFilesLoading = ref(false)
const bomUploading = ref(false)

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

async function uploadBomFileRequest(options: any) {
  const file = options.file as File
  if (!bomForm.id) {
    queuedBomFiles.value.push(file)
    ElMessage.info('文件已暂存，保存 BOM 节点后自动上传')
    return
  }
  bomUploading.value = true
  try {
    await uploadBomAttachment(bomForm.id, file)
    await loadBomAttachments(bomForm.id)
    ElMessage.success('附件上传成功')
  } finally {
    bomUploading.value = false
  }
}

function removeQueuedBomFile(index: number) {
  queuedBomFiles.value.splice(index, 1)
}

async function downloadBomAttachment(file: BomAttachment) {
  const signed = await fetchFileSignedUrl(file.id)
  const link = document.createElement('a')
  link.href = signed.url
  link.target = '_blank'
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  link.remove()
}

function resetBomForm(parent?: BomNode) {
  Object.assign(bomForm, {
    id: undefined,
    parentId: parent?.id,
    parentName: parent?.name || '一级总成',
    name: '',
    qty: 1,
    unitCost: undefined,
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
    parentName: row.parentId ? '上级节点 #' + row.parentId : '一级总成',
    name: row.name,
    qty: row.qty ?? 1,
    unitCost: row.unitCost,
    supplierId: row.supplierId,
    lifeYears: row.lifeYears,
    warrantyUntil: row.warrantyUntil,
    repairable: row.repairable ?? true,
    faultCount: row.faultCount ?? 0,
    residualRate: row.residualRate,
    remark: row.remark || '',
  })
  queuedBomFiles.value = []
  bomDialogVisible.value = true
  await loadBomAttachments(row.id)
}

function applySupplierToBomForm(supplierId?: number) {
  const supplier = selectedSupplier(supplierId)
  if (!supplier) return
  if (supplier.quotePrice != null) bomForm.unitCost = supplier.quotePrice
  if (!bomForm.name && supplier.itemDesc) bomForm.name = supplier.itemDesc
}

async function submitBom() {
  if (!detail.value || !bomForm.name) {
    ElMessage.warning('配件/模块名称必填')
    return
  }
  const body = {
    name: bomForm.name,
    parentId: bomForm.parentId,
    qty: bomForm.qty,
    unitCost: bomForm.unitCost,
    supplierId: bomForm.supplierId,
    lifeYears: bomForm.lifeYears,
    warrantyUntil: bomForm.warrantyUntil,
    repairable: bomForm.repairable,
    faultCount: bomForm.faultCount,
    residualRate: bomForm.residualRate,
    remark: bomForm.remark,
  }
  let bomId: number
  if (bomDialogMode.value === 'edit') {
    bomId = bomForm.id
    await updateBom(bomId, body)
    ElMessage.success('BOM 节点已更新')
  } else {
    bomId = await addBom(detail.value.id, body)
    bomForm.id = bomId
    ElMessage.success('BOM 节点已新增')
  }
  if (queuedBomFiles.value.length) {
    bomUploading.value = true
    try {
      await Promise.all(queuedBomFiles.value.map((file) => uploadBomAttachment(bomId, file)))
      ElMessage.success('BOM 附件已上传')
    } catch {
      ElMessage.warning('BOM 已保存，但有附件上传失败，请编辑节点后重试')
    } finally {
      bomUploading.value = false
    }
  }
  bomDialogVisible.value = false
  await openDetail(detail.value.id)
}
async function removeBom(row: BomNode) {
  if (!detail.value) return
  await ElMessageBox.confirm(
    '删除「' + row.name + '」将同时删除其全部下级部件，确认继续？',
    '删除 BOM 节点',
    { type: 'warning' },
  )
  await deleteBom(row.id)
  ElMessage.success('BOM 节点已删除')
  await openDetail(detail.value.id)
}

onMounted(() => {
  loadList()
  loadSuppliers()
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
      <el-input v-model="filters.keyword" placeholder="序列号/型号" clearable size="small" style="width:180px;margin-left:8px" @keyup.enter="loadList" />
      <el-button size="small" style="margin-left:8px" @click="loadList">查询</el-button>
      <span class="total">共 {{ total }} 台</span>
    </el-card>


    <el-table :data="list" v-loading="loading" size="small" @row-click="(r:any) => openDetail(r.id)" style="cursor:pointer">
      <el-table-column prop="serialNo" label="序列号" width="130" />
      <el-table-column prop="category" label="品类" width="90" />
      <el-table-column prop="model" label="型号" min-width="150" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }"><el-tag :type="statusType[row.status] || 'info'" size="small">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="市场价" width="100"><template #default="{ row }">{{ money(row.marketPrice) }}</template></el-table-column>
      <el-table-column label="集采价🔒" width="100"><template #default="{ row }">{{ money(row.purchasePrice) }}</template></el-table-column>
      <el-table-column label="账面价🔒" width="100"><template #default="{ row }">{{ money(row.bookValue) }}</template></el-table-column>
      <el-table-column label="残值" width="90"><template #default="{ row }">{{ money(row.residualValue) }}</template></el-table-column>
      <el-table-column prop="currentHolderName" label="承租客户" width="120"><template #default="{ row }">{{ row.currentHolderName || '—' }}</template></el-table-column>
    </el-table>


    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer" :title="detail ? `设备 ${detail.serialNo} · ${detail.status}` : '设备详情'" size="60%">
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
          <el-descriptions-item label="集采价">{{ money(detail.purchasePrice) }}</el-descriptions-item>
          <el-descriptions-item label="账面价(经营口径)">{{ money(detail.bookValue) }}</el-descriptions-item>
          <el-descriptions-item label="残值(市场价×转让率)">{{ money(detail.residualValue) }}</el-descriptions-item>
          <el-descriptions-item label="月替代人工">{{ money(detail.monthlyLaborValue) }}</el-descriptions-item>
          <el-descriptions-item label="自购回本(月)">{{ detail.selfPurchasePayback ?? '—' }}</el-descriptions-item>
        </el-descriptions>


        <!-- 状态机流转 -->
        <div class="block-title">状态流转</div>
        <div>
          <el-button v-for="t in (transitions[detail.status] || [])" :key="t" size="small" @click="doChangeStatus(t)">→ {{ t }}</el-button>
          <span v-if="!(transitions[detail.status] || []).length" style="color:#999">终态,无可流转</span>
        </div>


        <!-- 配件树 BOM -->
        <div class="block-title block-title-row">
          <span>配件树 BOM(成本拆解)</span>
          <el-button v-if="!detail.sensitiveMasked" type="primary" link size="small" @click="openAddBom()">+ 新增一级部件</el-button>
        </div>
        <el-table :data="detail.bom" row-key="id" default-expand-all size="small"
          :tree-props="{ children: 'children' }" border>
          <el-table-column prop="name" label="配件/模块" min-width="160" />
          <el-table-column prop="qty" label="数量" width="70" />
          <el-table-column label="单价🔒" width="100"><template #default="{ row }">{{ money(row.unitCost) }}</template></el-table-column>
          <el-table-column label="小计🔒" width="100"><template #default="{ row }">{{ money(row.subtotal) }}</template></el-table-column>
          <el-table-column prop="supplierName" label="供应商" width="110"><template #default="{ row }">{{ row.supplierName || '—' }}</template></el-table-column>
          <el-table-column prop="faultCount" label="故障" width="70">
            <template #default="{ row }"><el-tag v-if="row.faultCount > 0" type="danger" size="small">{{ row.faultCount }}</el-tag><span v-else>0</span></template>
          </el-table-column>
          <el-table-column prop="warrantyUntil" label="质保到" width="110"><template #default="{ row }">{{ row.warrantyUntil || '—' }}</template></el-table-column>
          <el-table-column v-if="!detail.sensitiveMasked" label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openAddBom(row)">新增下级</el-button>
              <el-button link size="small" @click="openEditBom(row)">编辑</el-button>
              <el-button link type="success" size="small" @click="openEditBom(row)">上传文件</el-button>
              <el-button link type="danger" size="small" @click="removeBom(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>


        <!-- 成本 / 残值 拆解 -->
        <el-row :gutter="12" v-if="detail.costBreakdown || detail.residualBreakdown">
          <el-col :span="12" v-if="detail.costBreakdown">
            <div class="block-title">成本拆解</div>
            <div v-for="it in detail.costBreakdown.items" :key="it.name" class="bar-row">
              <span class="bar-label">{{ it.name }}</span>
              <el-progress :percentage="Math.round(it.ratio * 100)" :stroke-width="14" style="flex:1" />
              <span class="bar-val">{{ money(it.amount) }}</span>
            </div>
            <div class="cost-foot">Σ成本 {{ money(detail.costBreakdown.total) }} · 集采 {{ money(detail.costBreakdown.purchasePrice) }} · 差 {{ money(detail.costBreakdown.gapVsPurchase) }}</div>
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
        </el-table>


        <!-- 单台收益 -->
        <div class="block-title">单台收益</div>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="单台月租分摊">{{ money(detail.singleUnitReturn.allocRent) }}</el-descriptions-item>
          <el-descriptions-item label="累计收租">{{ money(detail.singleUnitReturn.cumulativeRent) }}</el-descriptions-item>
          <el-descriptions-item label="单台回报率">{{ detail.singleUnitReturn.returnRate != null ? (detail.singleUnitReturn.returnRate * 100).toFixed(1) + '%' : '🔒' }}</el-descriptions-item>
          <el-descriptions-item label="在租天数">{{ detail.singleUnitReturn.inServiceDays ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="空置天数">
            <el-tag v-if="detail.singleUnitReturn.idleAlert" type="danger" size="small">{{ detail.singleUnitReturn.idleDays }} ⚠</el-tag>
            <span v-else>{{ detail.singleUnitReturn.idleDays ?? 0 }}</span>
          </el-descriptions-item>
        </el-descriptions>


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


    <!-- 新建 / 编辑设备弹窗 -->
    <el-dialog
      v-model="assetDialogVisible"
      :title="assetDialogMode === 'edit' ? '修改设备资料' : '新建设备(逐件建档)'"
      width="620px"
    >
      <el-form :model="assetForm" label-width="120px" size="small">
        <el-form-item label="序列号" required>
          <el-input v-model="assetForm.serialNo" placeholder="WL-BZQ-0004" />
        </el-form-item>
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
        <el-form-item label="集采价(元)">
          <el-input-number v-model="assetForm.purchasePrice" :min="0" :step="1000" style="width:100%" />
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
    <!-- BOM 节点维护弹窗 -->
    <el-dialog
      v-model="bomDialogVisible"
      :title="bomDialogMode === 'edit' ? '编辑 BOM 节点' : '新增 BOM 节点'"
      width="680px"
    >
      <el-form :model="bomForm" label-width="120px" size="small">
        <el-form-item label="父级节点"><el-input :model-value="bomForm.parentName" disabled /></el-form-item>
        <el-form-item label="配件/模块" required><el-input v-model="bomForm.name" /></el-form-item>
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
        </el-form-item>
        <div v-if="selectedSupplier(bomForm.supplierId)" class="supplier-tip">
          自动带出：供货项 {{ selectedSupplier(bomForm.supplierId)?.itemDesc || '—' }}
          · 报价 {{ money(selectedSupplier(bomForm.supplierId)?.quotePrice) }}
          · 联系人 {{ selectedSupplier(bomForm.supplierId)?.contact || '—' }}
        </div>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="数量"><el-input-number v-model="bomForm.qty" :min="0.01" :step="1" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="单价(元)"><el-input-number v-model="bomForm.unitCost" :min="0" :step="100" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="小计(自动)"><el-input :model-value="money(bomSubtotal)" disabled /></el-form-item>
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
            <el-form-item label="故障次数"><el-input-number v-model="bomForm.faultCount" :min="0" :step="1" style="width:100%" /></el-form-item>
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
              :show-file-list="false"
              multiple
            >
              <el-button type="primary" plain :loading="bomUploading">上传文件</el-button>
            </el-upload>
            <div class="upload-tip">新建节点可先选文件，保存后自动上传；已建节点会立即上传。</div>
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
        <el-form-item label="备注"><el-input v-model="bomForm.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bomDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitBom">保存</el-button>
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
</style>
