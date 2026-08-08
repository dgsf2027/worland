<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchAssets, fetchAssetDetail, createAsset, changeAssetStatus,
  type AssetListItem, type AssetDetail,
} from '@/api/asset'

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

// ---- 新建设备 ----
const createVisible = ref(false)
const form = reactive<Record<string, any>>({
  serialNo: '', category: '播种墙', model: '', marketPrice: undefined,
  purchasePrice: undefined, supplierId: undefined, monthlyLaborValue: undefined, replaceHeadcount: undefined,
})
async function submitCreate() {
  if (!form.serialNo || !form.category) { ElMessage.warning('序列号/品类必填'); return }
  await createAsset({ ...form })
  ElMessage.success('设备已建档(状态=采购)')
  createVisible.value = false
  loadList()
}

onMounted(loadList)
</script>

<template>
  <div class="asset-page">
    <div class="toolbar">
      <el-radio-group :model-value="curIdentity" @change="switchIdentity" size="small">
        <el-radio-button v-for="i in identities" :key="i.name" :value="i.name">{{ i.name }}</el-radio-button>
      </el-radio-group>
      <span class="hint">当前身份决定「集采价/账面价/成本」是否可见（GP/LP 打码 🔒）</span>
      <el-button type="primary" size="small" style="margin-left:auto" @click="createVisible = true">+ 新建设备</el-button>
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
        <div class="block-title">配件树 BOM(成本拆解)</div>
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

    <!-- 新建设备弹窗 -->
    <el-dialog v-model="createVisible" title="新建设备(逐件建档)" width="520px">
      <el-form :model="form" label-width="110px" size="small">
        <el-form-item label="序列号" required><el-input v-model="form.serialNo" placeholder="WL-BZQ-0004" /></el-form-item>
        <el-form-item label="品类" required>
          <el-select v-model="form.category" style="width:100%"><el-option v-for="c in categories" :key="c" :label="c" :value="c" /></el-select>
        </el-form-item>
        <el-form-item label="型号"><el-input v-model="form.model" /></el-form-item>
        <el-form-item label="市场价(元)"><el-input-number v-model="form.marketPrice" :min="0" :step="1000" style="width:100%" /></el-form-item>
        <el-form-item label="集采价(元)"><el-input-number v-model="form.purchasePrice" :min="0" :step="1000" style="width:100%" /></el-form-item>
        <el-form-item label="供应商ID"><el-input-number v-model="form.supplierId" :min="1" style="width:100%" /></el-form-item>
        <el-form-item label="月替代人工(元)"><el-input-number v-model="form.monthlyLaborValue" :min="0" :step="500" style="width:100%" /></el-form-item>
        <el-form-item label="替代人数"><el-input-number v-model="form.replaceHeadcount" :min="0" :step="1" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" @click="submitCreate">建档</el-button></template>
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
</style>
