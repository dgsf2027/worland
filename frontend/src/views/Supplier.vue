<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchSupplierPool, fetchSupplierDetail, fetchDependencyAlert, retireSupplier, deleteSupplier,
  updateSupplierScores, updateSupplierPrice, addSupply, updateSupply, deleteSupply,
  exportSuppliers, importSuppliers,
  type SupplierPoolItem, type SupplierDetail, type DependencyAlert, type SupplyRow, type SupplierImportResult,
} from '@/api/supplier'
import { checkUploadFile } from '@/api/asset'

const activeTab = ref('pool')

// ---- 供应商池 ----
const filters = reactive<{ keyword: string; category: string; status: string; minScore?: number }>({
  keyword: '', category: '', status: '',
})
const categories = ['播种墙', '货架', '阁楼', '配件']
const pool = ref<SupplierPoolItem[]>([])
const total = ref(0)
const alert = ref<DependencyAlert | null>(null)
const loading = ref(false)

async function loadPool() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 200 }
    if (filters.keyword) params.keyword = filters.keyword
    if (filters.category) params.category = filters.category
    if (filters.status) params.status = filters.status
    if (filters.minScore) params.minScore = filters.minScore
    const res = await fetchSupplierPool(params)
    pool.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}
async function loadAlert() {
  alert.value = await fetchDependencyAlert()
}

const statusType: Record<string, string> = {
  主供: 'success', 备供: 'primary', 入库: 'info', 试样: 'warning', 接触: 'info', 淘汰: 'danger',
}
const assetStatusType: Record<string, string> = {
  采购: 'info', 投放: 'warning', 在租: 'success', 待转让: 'primary', 已转让: 'info', 收回待处置: 'danger', 报废: 'info',
}

// ---- 导入 / 导出 ----
const exporting = ref(false)
async function onExport(template = false) {
  exporting.value = true
  try {
    await exportSuppliers(template)
    ElMessage.success(template ? '模板已下载' : '已导出，可修改后直接导入回系统')
  } finally {
    exporting.value = false
  }
}

const importing = ref(false)
const importResult = ref<SupplierImportResult | null>(null)
const importResultVisible = ref(false)
function beforeImport(file: File) {
  const err = checkUploadFile(file, ['xls', 'xlsx'], 10)
  if (err) {
    ElMessage.warning(err)
    return false
  }
  return true
}
async function importRequest(options: any) {
  const file = options.file as File
  try {
    await ElMessageBox.confirm(
      `将导入「${file.name}」：「供应商」表按名称新增或更新，「供货矩阵」表按「供应商 + 供何物」新增或更新。表格里没有的供应商和供货项不会删除。确认导入？`,
      '导入供应商', { type: 'warning', confirmButtonText: '确认导入' },
    )
  } catch {
    return
  }
  importing.value = true
  try {
    importResult.value = await importSuppliers(file)
    importResultVisible.value = true
    await Promise.all([loadPool(), loadAlert()])
    if (detail.value) await openDetail(detail.value.id)
  } finally {
    importing.value = false
  }
}

// ---- 供应商详情 ----
const detail = ref<SupplierDetail | null>(null)
const scoreDims = [
  { key: 'quality', label: '品质(故障率)' },
  { key: 'delivery', label: '交期(准时率)' },
  { key: 'service', label: '服务(响应)' },
  { key: 'price', label: '价格(vs市场)' },
  { key: 'term', label: '账期(首付低)' },
]
async function openDetail(id: number) {
  detail.value = await fetchSupplierDetail(id)
  activeTab.value = 'detail'
}
function scoreColor(v?: number | null) {
  if (v == null) return '#c0c4cc'
  return v >= 85 ? '#2f9e44' : v >= 75 ? '#2e6da4' : '#e8a33d'
}
function money(v?: number | null) {
  if (v === null || v === undefined) return '—'
  return v >= 10000 ? (v / 10000).toFixed(1) + '万' : '¥' + v.toLocaleString()
}
function pct(v?: number | null) {
  return v === null || v === undefined ? '—' : (v * 100).toFixed(0) + '%'
}
function radarValue(key: string): number | null {
  const r = detail.value?.scoreRadar as any
  return r && r[key] != null ? r[key] : null
}

async function onRetire(row: SupplierPoolItem) {
  try {
    const { value } = await ElMessageBox.prompt('淘汰/停用原因(留痕)', `淘汰供应商 ${row.name}`, {
      confirmButtonText: '确认淘汰', cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim() ? true : '原因必填'),
    })
    await retireSupplier(row.id, value)
    ElMessage.success('已淘汰,留痕完成')
    loadPool(); loadAlert()
  } catch { /* 取消 */ }
}

async function onDelete(row: { id: number; name: string }) {
  try {
    await ElMessageBox.confirm(
      `确认删除供应商「${row.name}」？连同它的供货矩阵一起删除。已被设备、采购、维保或考察记录引用的供应商不能删除，请改用「淘汰」。`,
      '删除供应商', { type: 'warning', confirmButtonText: '确认删除' },
    )
  } catch {
    return
  }
  await deleteSupplier(row.id)
  ElMessage.success(`已删除「${row.name}」`)
  if (detail.value?.id === row.id) {
    detail.value = null
    activeTab.value = 'pool'
  }
  loadPool(); loadAlert()
}

// ---- 编辑:履约评分 ----
const scoreVisible = ref(false)
const scoreSaving = ref(false)
const scoreForm = reactive<Record<string, number | null>>({ quality: null, delivery: null, service: null, price: null, term: null })
function openScoreEdit() {
  for (const d of scoreDims) scoreForm[d.key] = radarValue(d.key)
  scoreVisible.value = true
}
const scorePreview = computed(() => {
  const w = detail.value?.scoreWeights || {}
  if (scoreDims.every((d) => scoreForm[d.key] == null)) return null
  return Math.round(scoreDims.reduce((s, d) => s + Number(scoreForm[d.key] || 0) * Number(w[d.key] || 0), 0))
})
async function submitScores() {
  if (!detail.value) return
  scoreSaving.value = true
  try {
    await updateSupplierScores(detail.value.id, { ...scoreForm })
    scoreVisible.value = false
    ElMessage.success('履约评分已保存')
    await openDetail(detail.value.id)
    loadPool()
  } finally {
    scoreSaving.value = false
  }
}

// ---- 编辑:价格构成 ----
const priceVisible = ref(false)
const priceSaving = ref(false)
const priceForm = reactive<Record<string, number | null>>({ material: null, processing: null, profit: null, quote: null, bomEstimate: null })
function openPriceEdit() {
  const pc = detail.value?.priceComposition
  const primary = detail.value?.supplyMatrix.find((r) => r.primary)
  Object.assign(priceForm, {
    material: pc?.material ?? null,
    processing: pc?.processing ?? null,
    profit: pc?.profit ?? null,
    quote: pc?.quote ?? primary?.quotePrice ?? null,
    bomEstimate: pc?.bomEstimate ?? null,
  })
  priceVisible.value = true
}
const priceVerdict = computed(() => {
  if (priceForm.quote == null || priceForm.bomEstimate == null) return ''
  return Number(priceForm.quote) <= Number(priceForm.bomEstimate) * 1.1 ? '合理' : '偏高(疑虚高)'
})
async function submitPrice() {
  if (!detail.value) return
  priceSaving.value = true
  try {
    await updateSupplierPrice(detail.value.id, { ...priceForm })
    priceVisible.value = false
    ElMessage.success('价格构成已保存')
    await openDetail(detail.value.id)
    loadPool()
  } finally {
    priceSaving.value = false
  }
}

// ---- 编辑:供货矩阵 ----
const supplyVisible = ref(false)
const supplySaving = ref(false)
const supplyForm = reactive<Record<string, any>>({})
function openSupplyEdit(row?: SupplyRow) {
  Object.keys(supplyForm).forEach((k) => delete supplyForm[k])
  Object.assign(supplyForm, row
    ? {
        id: row.id, itemType: row.itemType, itemName: row.itemName, category: row.category || '',
        quotePrice: row.quotePrice ?? null,
        firstPayPct: row.firstPayRatio == null ? null : Math.round(row.firstPayRatio * 10000) / 100,
        accountDays: row.accountDays ?? null, noInterest: row.noInterest, canSingleBuy: row.canSingleBuy,
        primary: row.primary, remark: row.remark || '',
      }
    : {
        id: undefined, itemType: '整机', itemName: '', category: detail.value?.mainCategory || '',
        quotePrice: null, firstPayPct: null, accountDays: null, noInterest: true, canSingleBuy: true,
        primary: !detail.value?.supplyMatrix.length, remark: '',
      })
  supplyVisible.value = true
}
async function submitSupply() {
  if (!detail.value) return
  if (!String(supplyForm.itemName || '').trim()) {
    ElMessage.warning('供何物必填')
    return
  }
  const body = {
    itemType: supplyForm.itemType,
    itemName: String(supplyForm.itemName).trim(),
    category: supplyForm.category || null,
    quotePrice: supplyForm.quotePrice ?? null,
    firstPayRatio: supplyForm.firstPayPct == null ? null : Math.round(Number(supplyForm.firstPayPct) * 100) / 10000,
    accountDays: supplyForm.accountDays ?? null,
    noInterest: supplyForm.noInterest,
    canSingleBuy: supplyForm.canSingleBuy,
    primary: supplyForm.primary,
    remark: supplyForm.remark,
  }
  supplySaving.value = true
  try {
    if (supplyForm.id) await updateSupply(supplyForm.id, body)
    else await addSupply(detail.value.id, body)
    supplyVisible.value = false
    ElMessage.success('供货项已保存')
    await openDetail(detail.value.id)
    loadPool(); loadAlert()
  } finally {
    supplySaving.value = false
  }
}
async function removeSupply(row: SupplyRow) {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(`确认删除供货项「${row.itemName}」？${row.primary ? '它是代表供货项，删除后由剩余首行接任，履约评分和价格构成随代表项变化。' : ''}`, '删除供货项', { type: 'warning' })
  } catch {
    return
  }
  await deleteSupply(row.id)
  ElMessage.success('已删除')
  await openDetail(detail.value.id)
  loadPool(); loadAlert()
}

// ---- 跳转 ----
const route = useRoute()
const router = useRouter()
function goInspection() {
  router.push('/supplier-inspection')
}
function goAsset(id: number) {
  router.push({ path: '/asset', query: { id: String(id) } })
}

onMounted(() => {
  loadPool(); loadAlert()
  // 从考察页「关联供应商」跳转过来:直接打开详情
  const id = Number(route.query.id)
  if (id) openDetail(id)
})
</script>

<template>
  <div class="page">
    <div class="sub">接触 → 试样 → 入库 → 主供/备供 →（淘汰）全生命周期；按配件供货、价格构成、履约多维评分。支撑 N 家比价。</div>

    <el-tabs v-model="activeTab">
      <!-- ============ 供应商池 ============ -->
      <el-tab-pane label="供应商池（列表）" name="pool">
        <div class="filterbar">
          <el-input v-model="filters.keyword" placeholder="🔍 搜供应商/配件" style="width: 200px" clearable @keyup.enter="loadPool" @clear="loadPool" />
          <el-select v-model="filters.category" placeholder="品类" clearable style="width: 120px" @change="loadPool">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
          <el-select v-model="filters.status" placeholder="状态" clearable style="width: 120px" @change="loadPool">
            <el-option v-for="s in ['接触','试样','入库','主供','备供','淘汰']" :key="s" :label="s" :value="s" />
          </el-select>
          <el-input-number v-model="filters.minScore" :min="0" :max="100" placeholder="评分≥" controls-position="right" style="width: 130px" @change="loadPool" />
          <el-button type="primary" @click="loadPool">筛选</el-button>
          <span class="cnt">共 {{ total }} 家</span>
          <el-button link type="primary" :disabled="exporting" @click="onExport(true)">下载模板</el-button>
          <el-upload :http-request="importRequest" :before-upload="beforeImport" accept=".xls,.xlsx" :show-file-list="false" :disabled="importing">
            <el-button :loading="importing">⬆ 导入</el-button>
          </el-upload>
          <el-button :loading="exporting" @click="onExport(false)">⬇ 导出</el-button>
        </div>

        <el-alert
          v-for="risk in alert?.risks || []" :key="risk.category"
          type="warning" :closable="false" show-icon class="dep-alert"
          :title="'⚠️ 单一依赖预警：' + risk.message" />

        <el-table :data="pool" v-loading="loading" stripe border size="small">
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="(statusType[row.status] as any) || 'info'" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="供应商" min-width="130">
            <template #default="{ row }">
              <a class="lnk" @click="openDetail(row.id)">{{ row.name }}</a>
              <el-tag v-if="row.inspectionId" type="success" size="small" effect="plain" class="insp-tag">考察合格</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="itemDesc" label="品类/配件" min-width="150" />
          <el-table-column label="集采价" width="110" align="right">
            <template #default="{ row }">{{ row.quotePrice == null ? '按图报价' : money(row.quotePrice) }}</template>
          </el-table-column>
          <el-table-column label="首付" width="80" align="right">
            <template #default="{ row }">{{ pct(row.firstPayRatio) }}</template>
          </el-table-column>
          <el-table-column label="履约分" width="90" align="right">
            <template #default="{ row }">
              <b v-if="row.scoreTotal != null" :style="{ color: scoreColor(row.scoreTotal) }">{{ row.scoreTotal }}</b>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button v-if="row.status !== '淘汰'" link type="warning" size="small" @click="onRetire(row)">淘汰</el-button>
              <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="mini">配件级可比价；不再合作用「淘汰」（留痕）；误建或测试数据可「删除」，已被设备/采购/维保/考察引用的不能删。导入、删除需「供应链」或「老板」角色。</div>
      </el-tab-pane>

      <!-- ============ 供应商详情 ============ -->
      <el-tab-pane label="供应商详情" name="detail">
        <el-empty v-if="!detail" description="从供应商池点供应商名进入详情" />
        <template v-else>
          <h3 class="dt-title">
            {{ detail.name }}（{{ detail.mainCategory || '—' }}）
            <el-tag :type="(statusType[detail.status] as any) || 'info'" size="small">{{ detail.status }}</el-tag>
            <el-tag v-if="detail.scoreRadar" type="primary" size="small">履约分 {{ detail.scoreRadar.total }}</el-tag>
            <el-tag v-if="detail.costMasked" type="info" size="small">成本已按角色打码</el-tag>
          </h3>

          <div class="grid2">
            <div class="panel">
              <h4>履约评分（多维）
                <el-button v-if="!detail.costMasked" link type="primary" size="small" class="h-btn" @click="openScoreEdit">编辑</el-button>
              </h4>
              <div v-for="d in scoreDims" :key="d.key" class="scr">
                <div class="sl"><span>{{ d.label }}</span><b :style="{ color: scoreColor(radarValue(d.key)) }">{{ radarValue(d.key) ?? '—' }}</b></div>
                <el-progress :percentage="radarValue(d.key) ?? 0" :color="scoreColor(radarValue(d.key))" :show-text="false" :stroke-width="10" />
              </div>
              <div class="scr">
                <div class="sl"><span><b>加权总分</b></span><b :style="{ color: scoreColor(detail.scoreRadar?.total) }">{{ detail.scoreRadar?.total ?? '—' }}</b></div>
                <el-progress :percentage="detail.scoreRadar?.total ?? 0" :color="scoreColor(detail.scoreRadar?.total)" :show-text="false" :stroke-width="10" />
              </div>
              <div class="mini">{{ detail.scoreRadar ? '加权总分按 rule_config 权重即时算，非落库字段' : '该供应商暂未评分，点「编辑」手动录入五维评分' }}</div>
            </div>

            <div class="panel">
              <h4>价格构成（vs BOM 识别虚高）
                <el-button v-if="!detail.costMasked" link type="primary" size="small" class="h-btn" @click="openPriceEdit">编辑</el-button>
              </h4>
              <template v-if="detail.priceComposition">
                <div class="wf">
                  <span class="seg" style="background:#2e6da4" :style="{ flex: detail.priceComposition.material || 1 }">材料 {{ money(detail.priceComposition.material) }}</span>
                  <span class="seg" style="background:#5a8fbf" :style="{ flex: detail.priceComposition.processing || 1 }">加工 {{ money(detail.priceComposition.processing) }}</span>
                  <span class="seg" style="background:#b8862f" :style="{ flex: detail.priceComposition.profit || 1 }">利润 {{ money(detail.priceComposition.profit) }}</span>
                </div>
                <div class="kv">
                  <span>报价 {{ money(detail.priceComposition.quote) }} vs 我方BOM {{ money(detail.priceComposition.bomEstimate) }}</span>
                  <b v-if="detail.priceComposition.verdict" :class="detail.priceComposition.verdict === '合理' ? 'up' : 'warn'">{{ detail.priceComposition.verdict }}</b>
                </div>
              </template>
              <el-empty v-else :description="detail.costMasked ? '当前角色不可见成本' : '暂无价格构成，点「编辑」录入'" :image-size="60" />
            </div>
          </div>

          <div class="panel section">
            <h4>供货设备（设备 · 租赁台账）<span class="h-count">{{ detail.linkedAssets.length }} 台</span></h4>
            <el-table v-if="detail.linkedAssets.length" :data="detail.linkedAssets" border size="small">
              <el-table-column label="设备序列号" min-width="130">
                <template #default="{ row }"><a class="lnk" @click="goAsset(row.id)">{{ row.serialNo }}</a></template>
              </el-table-column>
              <el-table-column prop="category" label="品类" width="90" />
              <el-table-column prop="model" label="型号" min-width="150" show-overflow-tooltip />
              <el-table-column label="台账状态" width="110">
                <template #default="{ row }"><el-tag v-if="row.status" :type="(assetStatusType[row.status] as any) || 'info'" size="small">{{ row.status }}</el-tag></template>
              </el-table-column>
              <el-table-column label="集采价" width="110" align="right">
                <template #default="{ row }">{{ detail.costMasked ? '🔒' : money(row.purchasePrice) }}</template>
              </el-table-column>
              <el-table-column label="承租客户" width="140"><template #default="{ row }">{{ row.currentHolderName || '—' }}</template></el-table-column>
            </el-table>
            <el-empty v-else description="设备租赁台账里还没有供应商为这家的设备" :image-size="50" />
          </div>

          <div v-if="detail.inspection" class="panel section">
            <h4>
              考察记录（合格建档）
              <el-button link type="primary" size="small" class="h-btn" @click="goInspection">查看考察 →</el-button>
            </h4>
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="公司名称">{{ detail.inspection.companyName }}</el-descriptions-item>
              <el-descriptions-item label="法人">{{ detail.inspection.legalPerson || '—' }}</el-descriptions-item>
              <el-descriptions-item label="注册资本">{{ detail.inspection.registeredCapitalWan ? detail.inspection.registeredCapitalWan + ' 万元' : '—' }}</el-descriptions-item>
              <el-descriptions-item label="成立时间">{{ detail.inspection.establishedDate || '—' }}</el-descriptions-item>
              <el-descriptions-item label="公司地址" :span="2">{{ detail.inspection.address || '—' }}</el-descriptions-item>
              <el-descriptions-item label="业务范围">{{ detail.inspection.businessScope.join(' / ') || '—' }}</el-descriptions-item>
              <el-descriptions-item label="主要联系人">{{ detail.inspection.contact || '—' }} {{ detail.inspection.phone || '' }}</el-descriptions-item>
              <el-descriptions-item label="考察记录">{{ detail.inspection.archiveCount }} 个压缩包</el-descriptions-item>
              <el-descriptions-item label="判定">{{ detail.inspection.decidedByName || '—' }} · {{ (detail.inspection.decidedAt || '').replace('T', ' ').slice(0, 16) }}</el-descriptions-item>
              <el-descriptions-item label="结论" :span="2">{{ detail.inspection.conclusion || '—' }}</el-descriptions-item>
            </el-descriptions>
          </div>

          <div class="panel">
            <h4>供货矩阵（整机 + 配件）
              <el-button v-if="!detail.costMasked" link type="primary" size="small" class="h-btn" @click="openSupplyEdit()">+ 新增供货项</el-button>
            </h4>
            <el-table :data="detail.supplyMatrix" border size="small">
              <el-table-column label="类型" width="90">
                <template #default="{ row }">{{ row.itemType }}<el-tag v-if="row.primary" size="small" type="primary" class="insp-tag">代表</el-tag></template>
              </el-table-column>
              <el-table-column prop="itemName" label="供何物" min-width="140" />
              <el-table-column prop="category" label="品类" width="90" />
              <el-table-column label="报价" width="110" align="right">
                <template #default="{ row }">{{ row.quotePrice == null ? '—' : money(row.quotePrice) }}</template>
              </el-table-column>
              <el-table-column label="首付" width="80" align="right">
                <template #default="{ row }">{{ pct(row.firstPayRatio) }}</template>
              </el-table-column>
              <el-table-column label="账期" width="90" align="right">
                <template #default="{ row }">{{ row.accountDays == null ? '—' : row.accountDays + '天' + (row.noInterest ? '' : '·计息') }}</template>
              </el-table-column>
              <el-table-column label="可单采" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.canSingleBuy ? 'success' : 'warning'" size="small">{{ row.canSingleBuy ? '是' : '否' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="履约分" width="80" align="right">
                <template #default="{ row }">{{ row.scoreTotal ?? '—' }}</template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
              <el-table-column v-if="!detail.costMasked" label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openSupplyEdit(row)">编辑</el-button>
                  <el-button link type="danger" size="small" @click="removeSupply(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="mini">履约评分、价格构成取「代表」供货项；供应商池的集采价、首付、履约分也取代表项。</div>
          </div>
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- 编辑履约评分 -->
    <el-dialog v-model="scoreVisible" title="编辑履约评分（多维）" width="480px">
      <el-form label-width="120px" size="small" :disabled="scoreSaving">
        <el-form-item v-for="d in scoreDims" :key="d.key" :label="d.label">
          <el-input-number v-model="scoreForm[d.key]" :min="0" :max="100" :precision="0" controls-position="right" placeholder="0-100" style="width:160px" />
          <span class="weight">权重 {{ Math.round((detail?.scoreWeights?.[d.key] || 0) * 100) }}%</span>
        </el-form-item>
        <el-form-item label="加权总分">
          <b :style="{ color: scoreColor(scorePreview), fontSize: '18px' }">{{ scorePreview ?? '—' }}</b>
          <span class="weight">按权重自动计算</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="scoreSaving" @click="scoreVisible = false">取消</el-button>
        <el-button type="primary" :loading="scoreSaving" @click="submitScores">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编辑价格构成 -->
    <el-dialog v-model="priceVisible" title="编辑价格构成（vs BOM 识别虚高）" width="480px">
      <el-form label-width="120px" size="small" :disabled="priceSaving">
        <el-form-item label="材料(元)"><el-input-number v-model="priceForm.material" :min="0" :precision="2" :step="1000" controls-position="right" style="width:100%" /></el-form-item>
        <el-form-item label="加工(元)"><el-input-number v-model="priceForm.processing" :min="0" :precision="2" :step="1000" controls-position="right" style="width:100%" /></el-form-item>
        <el-form-item label="利润(元)"><el-input-number v-model="priceForm.profit" :precision="2" :step="1000" controls-position="right" style="width:100%" /></el-form-item>
        <el-form-item label="报价(元)"><el-input-number v-model="priceForm.quote" :min="0" :precision="2" :step="1000" controls-position="right" style="width:100%" /></el-form-item>
        <el-form-item label="我方BOM估算(元)"><el-input-number v-model="priceForm.bomEstimate" :min="0" :precision="2" :step="1000" controls-position="right" style="width:100%" /></el-form-item>
        <el-form-item label="结论">
          <b v-if="priceVerdict" :class="priceVerdict === '合理' ? 'up' : 'warn'">{{ priceVerdict }}</b>
          <span v-else class="weight">填写报价和 BOM 估算后自动判断（报价高出 BOM 10% 以上为偏高）</span>
        </el-form-item>
      </el-form>
      <div class="mini">保存到代表供货项；「报价」同时更新代表项的集采报价。</div>
      <template #footer>
        <el-button :disabled="priceSaving" @click="priceVisible = false">取消</el-button>
        <el-button type="primary" :loading="priceSaving" @click="submitPrice">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编辑供货项 -->
    <el-dialog v-model="supplyVisible" :title="supplyForm.id ? '编辑供货项' : '新增供货项'" width="560px">
      <el-form :model="supplyForm" label-width="100px" size="small" :disabled="supplySaving">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="类型">
              <el-radio-group v-model="supplyForm.itemType">
                <el-radio-button value="整机">整机</el-radio-button>
                <el-radio-button value="配件">配件</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="品类">
              <el-select v-model="supplyForm.category" clearable style="width:100%">
                <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="供何物" required><el-input v-model="supplyForm.itemName" maxlength="128" placeholder="如 播种墙 整机 / 电控系统" /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="报价(元)"><el-input-number v-model="supplyForm.quotePrice" :min="0" :precision="2" :step="1000" controls-position="right" placeholder="按图报价留空" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="首付(%)"><el-input-number v-model="supplyForm.firstPayPct" :min="0" :max="100" :precision="2" controls-position="right" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="账期(天)"><el-input-number v-model="supplyForm.accountDays" :min="0" :precision="0" controls-position="right" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="账期无息"><el-switch v-model="supplyForm.noInterest" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="可单采"><el-switch v-model="supplyForm.canSingleBuy" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="设为代表项"><el-switch v-model="supplyForm.primary" :disabled="supplyForm.id && detail?.supplyMatrix.find((r) => r.id === supplyForm.id)?.primary" /></el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注"><el-input v-model="supplyForm.remark" maxlength="255" type="textarea" :rows="2" placeholder="现金折扣/阶梯返利/维保返点" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="supplySaving" @click="supplyVisible = false">取消</el-button>
        <el-button type="primary" :loading="supplySaving" @click="submitSupply">保存</el-button>
      </template>
    </el-dialog>

    <!-- 导入结果 -->
    <el-dialog v-model="importResultVisible" title="导入结果" width="700px">
      <template v-if="importResult">
        <div class="import-kpi">
          <span>供应商 <b>{{ importResult.supplierRows }}</b> 行（新增 {{ importResult.suppliersCreated }} / 更新 {{ importResult.suppliersUpdated }}）</span>
          <span>供货矩阵 <b>{{ importResult.supplyRows }}</b> 行（新增 {{ importResult.suppliesCreated }} / 更新 {{ importResult.suppliesUpdated }}）</span>
          <span>跳过 <b>{{ importResult.skipped }}</b></span>
        </div>
        <el-table v-if="importResult.messages.length" :data="importResult.messages" size="small" border max-height="360">
          <el-table-column prop="sheet" label="表" width="90" />
          <el-table-column prop="row" label="行" width="60" align="center" />
          <el-table-column label="名称" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ row.name || '—' }}</template></el-table-column>
          <el-table-column label="说明" min-width="280">
            <template #default="{ row }"><span :class="row.level === 'warn' ? 'warn' : ''">{{ row.message }}</span></template>
          </el-table-column>
        </el-table>
        <div v-else class="mini">没有需要注意的行。</div>
      </template>
      <template #footer>
        <el-button type="primary" @click="importResultVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 4px; }
.sub { color: #666; font-size: 13px; margin-bottom: 10px; }
.filterbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.cnt { color: #999; font-size: 12px; margin-left: auto; }
.dep-alert { margin-bottom: 10px; }
.lnk { color: #2e6da4; cursor: pointer; font-weight: 600; }
.lnk:hover { text-decoration: underline; }
.mini { color: #999; font-size: 12px; margin-top: 8px; }
.dt-title { font-size: 16px; margin: 4px 0 12px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 14px; }
.panel { border: 1px solid #eee; border-radius: 8px; padding: 14px; background: #fff; }
.panel.section { margin-bottom: 14px; }
.panel h4 { margin: 0 0 12px; font-size: 14px; display: flex; align-items: center; gap: 8px; }
.h-btn { margin-left: auto; }
.h-count { color: #999; font-size: 12px; font-weight: normal; }
.scr { margin-bottom: 10px; }
.sl { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 4px; }
.wf { display: flex; height: 34px; border-radius: 6px; overflow: hidden; margin-bottom: 10px; }
.wf .seg { color: #fff; font-size: 12px; display: flex; align-items: center; justify-content: center; }
.kv { display: flex; justify-content: space-between; font-size: 13px; padding: 6px 0; border-top: 1px dashed #eee; }
.up { color: #2f9e44; } .warn { color: #e8a33d; }
.insp-tag { margin-left: 6px; }
.weight { color: #999; font-size: 12px; margin-left: 10px; }
.import-kpi { display: flex; gap: 18px; flex-wrap: wrap; margin-bottom: 12px; font-size: 13px; }
.import-kpi b { font-size: 16px; }
@media (max-width: 900px) { .grid2 { grid-template-columns: 1fr; } }
</style>
