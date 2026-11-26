<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createProject,
  createProjectKitAsset,
  deleteBizFile,
  deleteProject,
  downloadBizFile,
  fetchBizFiles,
  fetchProjectDetail,
  fetchProjects,
  syncProjectsFromContracts,
  updateProject,
  updateProjectDelivery,
  uploadBizFile,
  type ProjectDetail,
  type ProjectFileItem,
  type ProjectListItem,
} from '@/api/project'
import { fetchCustomerPool, type CustomerPoolItem } from '@/api/customer'
import { fetchSupplierPool, type SupplierPoolItem } from '@/api/supplier'
import { fetchContracts, type ContractListItem } from '@/api/contract'
import { fetchPurchaseContracts, type PcContract } from '@/api/purchaseContract'
import { fetchAssets, type AssetListItem } from '@/api/asset'
import { sessionRole } from '@/utils/session'

const router = useRouter()
const isLp = computed(() => (sessionRole.value || '').toUpperCase() === 'LP')
const canWrite = computed(() => ['老板', '财务', '供应链', '销售'].includes(sessionRole.value || ''))

const STAGES = ['全部', '报价测算', '合同落定', '排产发货', '安装验收', '收租履约', '质保维保', '留购结项']
const stageFilter = ref('全部')
const keyword = ref('')
const loading = ref(false)
const list = ref<ProjectListItem[]>([])

const customers = ref<CustomerPoolItem[]>([])
const suppliers = ref<SupplierPoolItem[]>([])
const contracts = ref<ContractListItem[]>([])
const purchaseContracts = ref<PcContract[]>([])
const assets = ref<AssetListItem[]>([])

async function loadOptions() {
  const [cRes, sRes, ctRes, pcRes, aRes] = await Promise.allSettled([
    fetchCustomerPool({ page: 1, size: 200 }),
    fetchSupplierPool({ page: 1, size: 200 }),
    fetchContracts({ page: 1, size: 200 }),
    fetchPurchaseContracts(),
    fetchAssets({ page: 1, size: 200 }),
  ])
  if (cRes.status === 'fulfilled') customers.value = cRes.value.records || []
  if (sRes.status === 'fulfilled') suppliers.value = sRes.value.records || []
  if (ctRes.status === 'fulfilled') contracts.value = ctRes.value.records || []
  if (pcRes.status === 'fulfilled') purchaseContracts.value = pcRes.value || []
  if (aRes.status === 'fulfilled') assets.value = aRes.value.records || []
}

async function loadList() {
  loading.value = true
  try {
    list.value = await fetchProjects({
      stage: stageFilter.value === '全部' ? undefined : stageFilter.value,
      keyword: keyword.value.trim() || undefined,
    })
  } finally {
    loading.value = false
  }
}

const syncing = ref(false)
async function handleSyncFromContracts() {
  syncing.value = true
  try {
    const res = await syncProjectsFromContracts()
    if (res.createdCount > 0) {
      ElMessage.success(`已从现有租赁合同同步生成 ${res.createdCount} 个项目`)
      await loadList()
    } else {
      ElMessage.info('所有生效租赁合同均已纳入项目中心')
    }
  } finally {
    syncing.value = false
  }
}

// ---- 新建 / 编辑项目 ----
const formVisible = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const formSaving = ref(false)
const form = reactive<Record<string, any>>({
  projectNo: '',
  name: '',
  assetMode: '工程套装',
  customerId: undefined,
  supplierId: undefined,
  rentContractId: undefined,
  purchaseContractId: undefined,
  primaryAssetId: undefined,
  palletSlots: undefined,
  plannedTons: undefined,
  warehouseZone: '',
  deliveryAddress: '',
  deliveryContact: '',
  warrantyYears: 5,
  quotePrincipal: undefined,
  quoteAnnualRate: 0.12,
  quoteTermMonths: 60,
  quoteMonthRent: undefined,
  quoteBuyoutPrice: 1,
  remark: '',
})

function resetForm() {
  Object.assign(form, {
    projectNo: '',
    name: '',
    assetMode: '工程套装',
    customerId: undefined,
    supplierId: undefined,
    rentContractId: undefined,
    purchaseContractId: undefined,
    primaryAssetId: undefined,
    palletSlots: undefined,
    plannedTons: undefined,
    warehouseZone: '',
    deliveryAddress: '',
    deliveryContact: '',
    warrantyYears: 5,
    quotePrincipal: undefined,
    quoteAnnualRate: 0.12,
    quoteTermMonths: 60,
    quoteMonthRent: undefined,
    quoteBuyoutPrice: 1,
    remark: '',
  })
}

async function openCreate() {
  formMode.value = 'create'
  editingId.value = null
  resetForm()
  formVisible.value = true
  if (!customers.value.length) await loadOptions()
}

async function openEdit(d: ProjectDetail) {
  formMode.value = 'edit'
  editingId.value = d.id
  Object.assign(form, {
    projectNo: d.projectNo,
    name: d.name,
    assetMode: d.assetMode || '工程套装',
    customerId: d.customerId,
    supplierId: d.supplierId,
    rentContractId: d.rentContractId,
    purchaseContractId: d.purchaseContractId,
    primaryAssetId: d.primaryAssetId,
    palletSlots: d.palletSlots,
    plannedTons: d.plannedTons,
    warehouseZone: d.warehouseZone || '',
    deliveryAddress: d.deliveryAddress || '',
    deliveryContact: d.deliveryContact || '',
    warrantyYears: d.warrantyYears ?? 5,
    quotePrincipal: d.quotePrincipal,
    quoteAnnualRate: d.quoteAnnualRate,
    quoteTermMonths: d.quoteTermMonths,
    quoteMonthRent: d.quoteMonthRent,
    quoteBuyoutPrice: d.quoteBuyoutPrice,
    remark: d.remark || '',
  })
  formVisible.value = true
  if (!customers.value.length) await loadOptions()
}

async function submitForm() {
  if (!form.name?.trim()) {
    ElMessage.warning('项目名称必填')
    return
  }
  formSaving.value = true
  try {
    const payload = { ...form }
    if (formMode.value === 'create') {
      const id = await createProject(payload)
      ElMessage.success('租赁项目已创建')
      formVisible.value = false
      await loadList()
      await openDetail(id)
    } else if (editingId.value) {
      detail.value = await updateProject(editingId.value, payload)
      ElMessage.success('项目信息与双合同绑定已保存')
      formVisible.value = false
      await loadList()
    }
  } finally {
    formSaving.value = false
  }
}

// ---- 项目详情抽屉（7步主线 + 交付验收工作台 + 真实利润全景） ----
const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ProjectDetail | null>(null)
const deliveryFiles = ref<ProjectFileItem[]>([])
const fileUploading = ref(false)

const deliveryForm = reactive<Record<string, any>>({
  productionStartDate: '',
  shippedDate: '',
  installDoneDate: '',
  loadTestDate: '',
  actualWeighTons: undefined,
  acceptanceDate: '',
  auditPassDate: '',
  warrantyYears: 5,
  palletSlots: undefined,
  warehouseZone: '',
  deliveryAddress: '',
  deliveryContact: '',
})
const deliverySaving = ref(false)

function fillDeliveryForm(d: ProjectDetail) {
  Object.assign(deliveryForm, {
    productionStartDate: d.productionStartDate || '',
    shippedDate: d.shippedDate || '',
    installDoneDate: d.installDoneDate || '',
    loadTestDate: d.loadTestDate || '',
    actualWeighTons: d.actualWeighTons,
    acceptanceDate: d.acceptanceDate || '',
    auditPassDate: d.auditPassDate || '',
    warrantyYears: d.warrantyYears ?? 5,
    palletSlots: d.palletSlots,
    warehouseZone: d.warehouseZone || '',
    deliveryAddress: d.deliveryAddress || '',
    deliveryContact: d.deliveryContact || '',
  })
}

async function openDetail(id: number) {
  drawerVisible.value = true
  detailLoading.value = true
  try {
    const d = await fetchProjectDetail(id)
    detail.value = d
    fillDeliveryForm(d)
    deliveryFiles.value = await fetchBizFiles('project_delivery', id).catch(() => [])
  } finally {
    detailLoading.value = false
  }
}

async function submitDelivery() {
  if (!detail.value) return
  deliverySaving.value = true
  try {
    const body: Record<string, any> = {}
    for (const k of Object.keys(deliveryForm)) {
      if (deliveryForm[k] !== '' && deliveryForm[k] !== undefined) body[k] = deliveryForm[k]
    }
    detail.value = await updateProjectDelivery(detail.value.id, body)
    fillDeliveryForm(detail.value)
    ElMessage.success('现场交付与验收已保存，已自动联动采购月结账期、5年质保与资产台账')
    await loadList()
  } finally {
    deliverySaving.value = false
  }
}

const kitCreating = ref(false)
async function handleCreateKitAsset() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(
      `将为项目「${detail.value.name}」按【工程套装】建 1 套资产台账（货位数：${detail.value.palletSlots ?? '未填'}，吨数：${detail.value.actualWeighTons ?? detail.value.plannedTons ?? '未填'}t），不再逐件拆单台。确认？`,
      '一键建工程套装资产',
      { type: 'info', confirmButtonText: '确认建档' },
    )
  } catch {
    return
  }
  kitCreating.value = true
  try {
    await createProjectKitAsset(detail.value.id)
    ElMessage.success('已生成「工程套装」资产台账并回填本项目')
    await openDetail(detail.value.id)
    await loadList()
  } finally {
    kitCreating.value = false
  }
}

async function handleDeliveryUpload(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !detail.value) return
  fileUploading.value = true
  try {
    await uploadBizFile('project_delivery', detail.value.id, file)
    ElMessage.success(`已上传「${file.name}」`)
    deliveryFiles.value = await fetchBizFiles('project_delivery', detail.value.id)
  } finally {
    fileUploading.value = false
    input.value = ''
  }
}

async function handleDeleteFile(f: ProjectFileItem) {
  if (!detail.value) return
  await ElMessageBox.confirm(`确认删除附件「${f.fileName}」？`, '删除附件', { type: 'warning' })
  await deleteBizFile(f.id)
  deliveryFiles.value = await fetchBizFiles('project_delivery', detail.value.id)
  ElMessage.success('已删除')
}

async function handleDeleteProject(row?: { id: number; projectNo: string; name: string }) {
  const target = row || detail.value
  if (!target) return
  try {
    await ElMessageBox.confirm(`确认安全删除项目「${target.projectNo} · ${target.name}」？（不影响已生效财务单据，可随时重新同步）`, '删除项目', {
      type: 'warning',
      confirmButtonText: '确认删除',
    })
  } catch { return }
  await deleteProject(target.id)
  ElMessage.success('项目已删除')
  if (detail.value?.id === target.id) {
    drawerVisible.value = false
    detail.value = null
  }
  await loadList()
}

function fmtMoney(v?: number | null) {
  if (v == null) return '—'
  return '¥' + Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtPct(v?: number | null) {
  if (v == null) return '—'
  return (Number(v) * 100).toFixed(1) + '%'
}
function stageTagType(stage: string) {
  if (stage === '留购结项') return 'success'
  if (stage === '收租履约' || stage === '质保维保') return 'primary'
  if (stage === '安装验收' || stage === '排产发货') return 'warning'
  return 'info'
}

onMounted(async () => {
  await Promise.all([loadList(), loadOptions()])
})
</script>

<template>
  <div class="project-page">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-radio-group v-model="stageFilter" size="small" @change="loadList">
          <el-radio-button v-for="s in STAGES" :key="s" :value="s">{{ s }}</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="keyword"
          placeholder="搜索项目编号/名称/库区"
          clearable
          size="small"
          style="width: 220px"
          @keyup.enter="loadList"
          @clear="loadList"
        />
        <el-button size="small" @click="loadList">查询</el-button>
      </div>
      <div class="toolbar-right" v-if="canWrite">
        <el-button size="small" :loading="syncing" @click="handleSyncFromContracts">🔄 从现有合同同步</el-button>
        <el-button type="primary" size="small" @click="openCreate">+ 新建租赁项目</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      style="margin-bottom: 12px"
      title="一单到底主线：每个货架采购+出租项目串联 ①报价测算 → ②双合同落定 → ③排产发货 → ④过磅安装验收 → ⑤收租开票 → ⑥5年质保 → ⑦留购结项，现场验收后自动推算采购月结账期与真实毛利。"
    />

    <el-table :data="list" v-loading="loading" border stripe @row-click="(r: ProjectListItem) => openDetail(r.id)" style="cursor: pointer">
      <el-table-column prop="projectNo" label="项目编号" width="145" />
      <el-table-column label="项目名称与形态" min-width="210">
        <template #default="{ row }">
          <div style="font-weight: 600">{{ row.name }}</div>
          <div class="sub-line">
            <el-tag size="small" :type="row.assetMode === '工程套装' ? 'warning' : 'info'" effect="plain">
              {{ row.assetMode || '工程套装' }}
            </el-tag>
            <span v-if="row.palletSlots"> {{ row.palletSlots }} 货位</span>
            <span v-if="row.actualWeighTons || row.plannedTons"> · {{ row.actualWeighTons ?? row.plannedTons }} 吨</span>
            <span v-if="row.warehouseZone"> · {{ row.warehouseZone }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="当前阶段（7步主线）" width="160">
        <template #default="{ row }">
          <el-tag :type="stageTagType(row.stage)" size="small">{{ row.stage }}</el-tag>
          <div class="sub-line">第 {{ row.currentStepNo }} / 7 步</div>
        </template>
      </el-table-column>
      <el-table-column label="承租客户 / 供应商" min-width="180">
        <template #default="{ row }">
          <div>客：{{ row.customerName || '待定' }}</div>
          <div class="sub-line">供：{{ row.supplierName || '待定' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="双合同与套装" min-width="165">
        <template #default="{ row }">
          <div>租：{{ row.rentContractNo || '未签' }}</div>
          <div class="sub-line">采：{{ row.purchaseContractNo || '未签' }}</div>
        </template>
      </el-table-column>
      <el-table-column v-if="!isLp" label="采购投入" width="125" align="right">
        <template #default="{ row }">{{ fmtMoney(row.purchaseTotal) }}</template>
      </el-table-column>
      <el-table-column label="实收租金" width="125" align="right">
        <template #default="{ row }">{{ fmtMoney(row.receivedRent) }}</template>
      </el-table-column>
      <el-table-column v-if="!isLp" label="净现金流 / 预计毛利" width="165" align="right">
        <template #default="{ row }">
          <div :style="{ color: (row.actualNetCashflow ?? 0) >= 0 ? '#16a34a' : '#d97706', fontWeight: 600 }">
            {{ fmtMoney(row.actualNetCashflow) }}
          </div>
          <div class="sub-line">毛利 {{ fmtMoney(row.expectedGrossProfit) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="回本进度" width="110" align="center">
        <template #default="{ row }">
          <span v-if="row.paybackProgress != null">{{ fmtPct(row.paybackProgress) }}</span>
          <span v-else>—</span>
          <div v-if="row.paybackPeriodNo" class="sub-line">第{{ row.paybackPeriodNo }}期回本</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click.stop="openDetail(row.id)">详情</el-button>
          <el-button v-if="canWrite" link type="danger" size="small" @click.stop="handleDeleteProject(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑项目弹窗 -->
    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新建货架/设备租赁项目' : '编辑项目与双合同绑定'" width="720px">
      <el-form :model="form" label-width="115px" size="small">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="项目名称" required>
              <el-input v-model="form.name" placeholder="如：东莞A仓2279货位重型货架项目" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="资产建档形态">
              <el-radio-group v-model="form.assetMode">
                <el-radio-button value="工程套装">工程套装（货架整套）</el-radio-button>
                <el-radio-button value="单机设备">单机设备</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="承租客户">
              <el-select v-model="form.customerId" filterable clearable style="width: 100%" placeholder="选择客户">
                <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="货架/设备供应商">
              <el-select v-model="form.supplierId" filterable clearable style="width: 100%" placeholder="选择供应商">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="绑定租赁合同">
              <el-select v-model="form.rentContractId" filterable clearable style="width: 100%" placeholder="可选，先采后签可后补">
                <el-option v-for="ct in contracts" :key="ct.id" :label="`${ct.no} · ${ct.customerName || ''}`" :value="ct.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="绑定采购合同">
              <el-select v-model="form.purchaseContractId" filterable clearable style="width: 100%" placeholder="可选，绑定后自动对齐双合同">
                <el-option v-for="pc in purchaseContracts" :key="pc.id" :label="`${pc.contractNo} · ${pc.supplierName || ''}`" :value="pc.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="绑定工程套装/资产">
              <el-select v-model="form.primaryAssetId" filterable clearable style="width: 100%" placeholder="可选，绑定已有工程套装/主设备台账">
                <el-option
                  v-for="a in assets"
                  :key="a.id"
                  :label="`${a.serialNo} · ${a.assetMode || '单机设备'} · ${a.model || a.category}`"
                  :value="a.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="规划货位数">
              <el-input-number v-model="form.palletSlots" :min="0" :precision="0" style="width: 100%" placeholder="如 2279" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="设计吨数(t)">
              <el-input-number v-model="form.plannedTons" :min="0" :precision="2" style="width: 100%" placeholder="如 45.00" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="质保年限(年)">
              <el-input-number v-model="form.warrantyYears" :min="0" :max="20" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="安装库区/楼层">
              <el-input v-model="form.warehouseZone" placeholder="如：B栋二楼东区" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="直发安装地址">
              <el-input v-model="form.deliveryAddress" placeholder="供应商直发客户仓地址" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="现场对接人">
              <el-input v-model="form.deliveryContact" placeholder="姓名 + 电话" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="报价本金(元)">
              <el-input-number v-model="form.quotePrincipal" :min="0" :precision="2" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="租期(月)">
              <el-input-number v-model="form.quoteTermMonths" :min="1" :max="120" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="测算月租(元)">
              <el-input-number v-model="form.quoteMonthRent" :min="0" :precision="2" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="期末留购价(元)">
              <el-input-number v-model="form.quoteBuyoutPrice" :min="0" :precision="2" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="项目备注">
              <el-input v-model="form.remark" type="textarea" :rows="2" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="formSaving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 项目全生命周期抽屉 -->
    <el-drawer v-model="drawerVisible" :title="detail ? `${detail.projectNo} · ${detail.name}` : '项目详情'" size="78%">
      <div v-if="detail" v-loading="detailLoading" class="drawer-body">
        <!-- 顶部快捷联动栏 -->
        <div class="action-bar">
          <el-tag :type="stageTagType(detail.stage)">{{ detail.stage }}（第 {{ detail.currentStepNo }}/7 步）</el-tag>
          <el-tag type="warning" effect="plain">{{ detail.assetMode || '工程套装' }}</el-tag>
          <div style="flex: 1" />
          <el-button v-if="canWrite" size="small" @click="openEdit(detail)">✏️ 编辑 / 绑双合同</el-button>
          <el-button
            v-if="canWrite && !detail.primaryAssetId"
            size="small"
            type="warning"
            :loading="kitCreating"
            @click="handleCreateKitAsset"
          >
            📦 一键建工程套装资产
          </el-button>
          <el-button size="small" @click="router.push('/quote')">🧮 报价测算</el-button>
          <el-button v-if="detail.rentContractId" size="small" type="primary" plain @click="router.push('/contract')">
            📄 租赁合同 {{ detail.rentContractNo }}
          </el-button>
          <el-button v-if="detail.purchaseContractId" size="small" type="success" plain @click="router.push('/purchase-contract')">
            📑 采购合同 {{ detail.purchaseContractNo }}
          </el-button>
          <el-button size="small" @click="router.push('/rent')">🧾 收租开票</el-button>
          <el-button v-if="canWrite" size="small" type="danger" plain @click="handleDeleteProject()">🗑️ 删除项目</el-button>
        </div>

        <!-- 7步全生命周期可视化进度条 -->
        <el-card shadow="never" class="sec-card">
          <template #header><b>7 步全生命周期追踪（报价 → 双合同 → 排产 → 过磅验收 → 收租开票 → 5年质保 → 留购结项）</b></template>
          <el-steps :active="Math.max(0, detail.currentStepNo - 1)" align-center finish-status="success">
            <el-step
              v-for="s in detail.steps"
              :key="s.stepNo"
              :title="`${s.stepNo}. ${s.title}`"
              :status="s.status === 'done' ? 'success' : s.status === 'active' ? 'process' : 'wait'"
              :description="s.summary + (s.dateText ? ` (${s.dateText})` : '')"
            />
          </el-steps>
        </el-card>

        <!-- 真实账利润与回本概览（已绑租赁合同时展示） -->
        <el-card v-if="detail.panorama" shadow="never" class="sec-card">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center">
              <b>项目真实账利润全景（含双合同实收实付、回本进度与税负估算）</b>
              <span class="sub-line" v-if="detail.panorama.paybackPeriodNo">
                预计第 {{ detail.panorama.paybackPeriodNo }} 期回本 · 峰值资金占用 {{ fmtMoney(detail.panorama.peakCapitalOutlay) }}
              </span>
            </div>
          </template>
          <el-row :gutter="12" class="kpi-grid">
            <el-col :span="4" v-if="detail.costVisible">
              <div class="kpi-box">
                <div class="kpi-label">采购总投入</div>
                <div class="kpi-val">{{ fmtMoney(detail.panorama.purchaseTotal) }}</div>
                <div class="kpi-sub">已付 {{ fmtMoney(detail.panorama.purchasePaid) }}</div>
              </div>
            </el-col>
            <el-col :span="4">
              <div class="kpi-box">
                <div class="kpi-label">合同租金+留购</div>
                <div class="kpi-val">{{ fmtMoney(detail.panorama.plannedCustomerTotal) }}</div>
                <div class="kpi-sub">已收 {{ fmtMoney(detail.panorama.receivedRent) }}</div>
              </div>
            </el-col>
            <el-col :span="4" v-if="detail.costVisible">
              <div class="kpi-box">
                <div class="kpi-label">实际净现金流</div>
                <div class="kpi-val" :style="{ color: (detail.panorama.actualNetCashflow ?? 0) >= 0 ? '#16a34a' : '#d97706' }">
                  {{ fmtMoney(detail.panorama.actualNetCashflow) }}
                </div>
                <div class="kpi-sub">实收 − 实付</div>
              </div>
            </el-col>
            <el-col :span="4" v-if="detail.costVisible">
              <div class="kpi-box">
                <div class="kpi-label">全期预计毛利</div>
                <div class="kpi-val" style="color: #16a34a">{{ fmtMoney(detail.panorama.expectedGrossProfit) }}</div>
                <div class="kpi-sub">毛利率 {{ fmtPct(detail.panorama.expectedGrossMargin) }}</div>
              </div>
            </el-col>
            <el-col :span="4" v-if="detail.costVisible">
              <div class="kpi-box">
                <div class="kpi-label">税后净利(估)</div>
                <div class="kpi-val">{{ fmtMoney(detail.panorama.estimatedAfterTaxProfit) }}</div>
                <div class="kpi-sub">销项3%−进项13%</div>
              </div>
            </el-col>
            <el-col :span="4">
              <div class="kpi-box">
                <div class="kpi-label">回本进度</div>
                <div class="kpi-val">{{ fmtPct(detail.panorama.paybackProgress) }}</div>
                <div class="kpi-sub">已结 {{ detail.panorama.settledPeriods }}/{{ detail.panorama.totalPeriods }} 期</div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <!-- 现场交付、过磅、安装、三方验收与资料留档（Step 2 & 3 核心） -->
        <el-card shadow="never" class="sec-card">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center">
              <b>现场交付、过磅称重与三方验收工作台（保存后自动重算采购月结账期 + 5年质保 + 工程套装台账）</b>
              <div v-if="detail.weighDiffPct != null">
                <el-tag :type="detail.weighWithinTolerance ? 'success' : 'danger'" size="small">
                  过磅偏差 {{ detail.weighDiffPct > 0 ? '+' : '' }}{{ detail.weighDiffPct }}%
                  （{{ detail.weighWithinTolerance ? '±3% 允差内 ✓' : '超 ±3% 允差，需核对！' }}）
                </el-tag>
              </div>
            </div>
          </template>
          <el-form :model="deliveryForm" label-width="110px" size="small" :disabled="!canWrite">
            <el-row :gutter="12">
              <el-col :span="6">
                <el-form-item label="排产开始日">
                  <el-date-picker v-model="deliveryForm.productionStartDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="工厂发货日">
                  <el-date-picker v-model="deliveryForm.shippedDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="安装完工日">
                  <el-date-picker v-model="deliveryForm.installDoneDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="载荷检测日">
                  <el-date-picker v-model="deliveryForm.loadTestDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="三方验收合格">
                  <el-date-picker v-model="deliveryForm.acceptanceDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="结算审核通过">
                  <el-date-picker v-model="deliveryForm.auditPassDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="实际过磅(吨)">
                  <el-input-number v-model="deliveryForm.actualWeighTons" :min="0" :precision="2" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="实装货位数">
                  <el-input-number v-model="deliveryForm.palletSlots" :min="0" :precision="0" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="质保年限(年)">
                  <el-input-number v-model="deliveryForm.warrantyYears" :min="0" :max="20" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="质保到期日">
                  <el-input :model-value="detail.warrantyEndDate || '验收后自动推算'" disabled />
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="安装库区">
                  <el-input v-model="deliveryForm.warehouseZone" placeholder="如 B栋二楼东区" />
                </el-form-item>
              </el-col>
              <el-col :span="6">
                <el-form-item label="现场联系人">
                  <el-input v-model="deliveryForm.deliveryContact" />
                </el-form-item>
              </el-col>
            </el-row>
            <div style="text-align: right; margin-top: 4px" v-if="canWrite">
              <el-button type="primary" size="small" :loading="deliverySaving" @click="submitDelivery">
                💾 保存交付验收并自动级联账期与质保
              </el-button>
            </div>
          </el-form>

          <!-- 验收资料凭证上传（过磅单/送货单/安装现场照/载荷检测报告/三方验收单） -->
          <el-divider content-position="left">现场交付与验收资料留档（过磅单 / 送货单 / 安装照 / 载荷报告 / 三方验收单）</el-divider>
          <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 10px" v-if="canWrite">
            <label class="upload-btn">
              <span>{{ fileUploading ? '上传中...' : '📎 上传交付/验收资料（图片/PDF/Office，≤20MB）' }}</span>
              <input type="file" style="display: none" :disabled="fileUploading" @change="handleDeliveryUpload" />
            </label>
          </div>
          <el-table :data="deliveryFiles" size="small" border v-if="deliveryFiles.length">
            <el-table-column prop="fileName" label="文件名" min-width="240" />
            <el-table-column prop="uploaderName" label="上传人" width="100" />
            <el-table-column prop="createTime" label="上传时间" width="160" />
            <el-table-column label="操作" width="140">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="downloadBizFile(row.id, row.fileName)">下载</el-button>
                <el-button v-if="canWrite" link type="danger" size="small" @click="handleDeleteFile(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-else class="sub-line">暂无验收资料，建议上传过磅单、安装现场照与三方竣工验收单。</div>
        </el-card>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.project-page { padding: 4px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; }
.toolbar-left { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.toolbar-right { display: flex; gap: 8px; }
.sub-line { font-size: 12px; color: #6b7280; margin-top: 2px; }
.drawer-body { padding: 0 4px; }
.action-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.sec-card { margin-bottom: 14px; }
.kpi-grid { margin-top: 4px; }
.kpi-box { background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 6px; padding: 10px 12px; }
.kpi-label { font-size: 12px; color: #6b7280; }
.kpi-val { font-size: 17px; font-weight: 700; margin: 4px 0 2px; color: #111827; }
.kpi-sub { font-size: 11px; color: #6b7280; }
.upload-btn {
  display: inline-block; padding: 6px 12px; background: #eff6ff; color: #1d4ed8;
  border: 1px dashed #93c5fd; border-radius: 6px; cursor: pointer; font-size: 12px;
}
.upload-btn:hover { background: #dbeafe; }
</style>
