<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchInvItems, fetchInvDamages, settleInvDamage, fetchCompPrices, saveCompPrices, fetchInvCompany, saveInvCompany,
  fetchInvOverview, currentRole, SETTLE_STATUSES, OPERATE_ROLES, SETTLE_ROLES,
  type InvItem, type InvDamage, type PriceRow, type CompanyInfo,
} from '@/api/inventory'
import {
  fetchMaintenances, createMaintenance, assignMaintenance, handleMaintenance, fetchSpareAlert,
  type MaintenanceItem, type SparePartAlertResponse,
} from '@/api/maintenance'
import { fetchAssets, type AssetListItem } from '@/api/asset'
import ProofUploader from '@/components/payproof/ProofUploader.vue'
import ProofViewer from '@/components/payproof/ProofViewer.vue'

/**
 * 服务模块(10-07 纪要):维保工单 / 损坏与赔偿 / 设置 从「资产管理」移出。
 * 资产管理只管资产本身(台账/出租/出入库/提醒);售后与规则配置在这里。后端接口路径不变。
 */
const route = useRoute()
const router = useRouter()
const role = currentRole()
const canOperate = OPERATE_ROLES.includes(role)
const canSettle = SETTLE_ROLES.includes(role)
const canCompany = ['老板', '供应链'].includes(role)
const allItems = ref<InvItem[]>([])

const tab = ref<string>((route.query.tab as string) || 'maintenance')

function fmtTime(v?: string) { return v ? v.replace('T', ' ').slice(0, 16) : '—' }
const money = (v?: number) => '¥' + Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const settleTag: Record<string, string> = { 待收取: 'danger', 已收取: 'success', 已减免: 'info' }

// ============ 损坏与赔偿 ============
const damageFilters = reactive({ settleStatus: '', keyword: '' })
const damageRows = ref<InvDamage[]>([])
const damagesLoading = ref(false)
async function loadDamages() {
  damagesLoading.value = true
  try {
    damageRows.value = (await fetchInvDamages({ page: 1, size: 5000, ...damageFilters })).records
  } finally {
    damagesLoading.value = false
  }
}
const damageSum = computed(() => damageRows.value.reduce((s, d) => s + Number(d.amount || 0), 0))
const pendingSum = ref(0)
async function loadPending() { pendingSum.value = (await fetchInvOverview()).pendingCompensation }

// 赔偿处理:已收取要先传收款回单;已减免必须写原因
const settleDlg = reactive<{ show: boolean; row?: InvDamage; proofFileIds: number[]; saving: boolean }>({ show: false, proofFileIds: [], saving: false })
async function settle(d: InvDamage, status: string) {
  if (status === d.settleStatus) return
  if (status === '已收取') {
    Object.assign(settleDlg, { show: true, row: d, proofFileIds: [], saving: false })
    return
  }
  if (status === '已减免') {
    const { value } = await ElMessageBox.prompt(`${d.partName} ${money(d.amount)} 减免原因`, '减免赔偿', {
      inputValidator: (v) => (v && v.trim() ? true : '请写明减免原因'),
    })
    await settleInvDamage(d.id, { settleStatus: status, remark: value.trim() })
  } else {
    await ElMessageBox.confirm(`${d.partName} ${money(d.amount)} 改回「${status}」？`, '赔偿处理', { type: 'info' })
    await settleInvDamage(d.id, { settleStatus: status })
  }
  ElMessage.success('已更新')
  await loadDamages()
  loadPending()
}
async function submitSettle() {
  settleDlg.saving = true
  try {
    await settleInvDamage(settleDlg.row!.id, { settleStatus: '已收取', proofFileIds: settleDlg.proofFileIds })
    ElMessage.success('已标记已收取')
    settleDlg.show = false
    await loadDamages()
    loadPending()
  } finally {
    settleDlg.saving = false
  }
}
const damageProof = reactive<{ show: boolean; id: number; title: string }>({ show: false, id: 0, title: '' })
function openDamageProof(row: InvDamage) { Object.assign(damageProof, { show: true, id: row.id, title: '赔偿回单 · ' + row.partName }) }

// ============ 设置 ============
const priceRows = ref<PriceRow[]>([])
const companyForm = reactive<CompanyInfo>({ companyName: '', phone: '', address: '', website: '', notice: '' })
const settingsLoading = ref(false)
async function loadSettings() {
  settingsLoading.value = true
  try {
    const [p, c] = await Promise.all([fetchCompPrices(), fetchInvCompany()])
    priceRows.value = p
    Object.assign(companyForm, c)
  } finally {
    settingsLoading.value = false
  }
}
const priceSaving = ref(false)
async function savePrices() {
  priceSaving.value = true
  try {
    await saveCompPrices(priceRows.value)
    ElMessage.success('赔偿价目已保存（之后登记的归还按新单价计算）')
    await loadSettings()
  } finally {
    priceSaving.value = false
  }
}
const companySaving = ref(false)
async function saveCompany() {
  companySaving.value = true
  try {
    await saveInvCompany({ ...companyForm })
    ElMessage.success('企业信息已保存，重新打印的标签生效')
  } finally {
    companySaving.value = false
  }
}

// ============ 维保工单(设备台账 + 仓库物品两类对象) ============
const mtTag: Record<string, string> = { 待派工: 'warning', 处理中: 'primary', 已完成: 'success', 已关闭: 'info' }
const mtTypeTag: Record<string, string> = { 报修: 'danger', 预防: 'primary', 巡检: 'info' }
const mtList = ref<MaintenanceItem[]>([])
const mtTotal = ref(0)
const mtLoading = ref(false)
const mtFilters = reactive<{ status: string; type: string; targetType: string }>({ status: '', type: '', targetType: '' })
const spare = ref<SparePartAlertResponse | null>(null)
/** 报修时可选的台账设备(仓库物品用 allItems) */
const mtAssets = ref<AssetListItem[]>([])

async function loadMaintenances() {
  mtLoading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 100 }
    if (mtFilters.status) params.status = mtFilters.status
    if (mtFilters.type) params.type = mtFilters.type
    if (mtFilters.targetType) params.targetType = mtFilters.targetType
    const res = await fetchMaintenances(params)
    mtList.value = res.records
    mtTotal.value = res.total
  } finally {
    mtLoading.value = false
  }
}
async function loadMaintenanceRefs() {
  if (!mtAssets.value.length) {
    mtAssets.value = (await fetchAssets({ page: 1, size: 500 })).records
  }
  if (!allItems.value.length) {
    allItems.value = (await fetchInvItems({ page: 1, size: 5000 })).records
  }
  spare.value = await fetchSpareAlert()
}

// ---- 报修建单 ----
const mtCreateDlg = ref(false)
const mtForm = reactive<{
  targetType: 'asset' | 'inv_item'; assetId?: number; invItemId?: number; qty: number
  bomId?: number; type: string; faultDesc: string
}>({ targetType: 'asset', qty: 1, type: '报修', faultDesc: '' })
const mtPickedItem = computed(() => allItems.value.find((i) => i.id === mtForm.invItemId) || null)
const mtAssetLabel = (a: AssetListItem) =>
  `${a.category}${a.model ? ' · ' + a.model : ''} · ${a.serialNo}（${a.status}）`
function openMtCreate() {
  Object.assign(mtForm, { targetType: 'asset', assetId: undefined, invItemId: undefined, qty: 1, bomId: undefined, type: '报修', faultDesc: '' })
  mtCreateDlg.value = true
  loadMaintenanceRefs()
}
async function submitMtCreate() {
  if (mtForm.targetType === 'asset' && !mtForm.assetId) { ElMessage.warning('请选择台账设备'); return }
  if (mtForm.targetType === 'inv_item' && !mtForm.invItemId) { ElMessage.warning('请选择仓库物品'); return }
  const body: Record<string, any> = { targetType: mtForm.targetType, type: mtForm.type }
  if (mtForm.faultDesc) body.faultDesc = mtForm.faultDesc
  if (mtForm.targetType === 'asset') {
    body.assetId = mtForm.assetId
    if (mtForm.bomId) body.bomId = mtForm.bomId
  } else {
    body.invItemId = mtForm.invItemId
    body.qty = mtForm.qty
  }
  await createMaintenance(body)
  ElMessage.success(mtForm.targetType === 'inv_item'
    ? `工单已创建（待派工）· 已把 ${mtForm.qty} 件从库存转入维修中`
    : '工单已创建（待派工）')
  mtCreateDlg.value = false
  allItems.value = []
  loadMaintenances()
}

// ---- 派工 ----
async function onMtAssign(row: MaintenanceItem) {
  try {
    const { value } = await ElMessageBox.prompt('责任方(我方/供应商)', `派工 ${row.no}`, {
      confirmButtonText: '派工', cancelButtonText: '取消', inputValue: '我方',
      inputValidator: (v) => (['我方', '供应商'].includes((v || '').trim()) ? true : '填 我方 或 供应商'),
    })
    await assignMaintenance(row.id, { responsibleParty: value.trim() })
    ElMessage.success('已派工(处理中)')
    loadMaintenances()
  } catch { /* 取消 */ }
}

// ---- 完工回写 ----
const mtHandleDlg = reactive<{
  show: boolean; id?: number; no?: string; targetType?: string; qty?: number
  cost: number; inWarranty: boolean; handleNote: string; recordFault: boolean; scrapped: boolean
}>({ show: false, cost: 0, inWarranty: false, handleNote: '', recordFault: true, scrapped: false })
function openMtHandle(row: MaintenanceItem) {
  Object.assign(mtHandleDlg, {
    show: true, id: row.id, no: row.no, targetType: row.targetType, qty: row.qty,
    cost: 0, inWarranty: false, handleNote: '', recordFault: row.type === '报修', scrapped: false,
  })
}
async function submitMtHandle() {
  await handleMaintenance(mtHandleDlg.id!, {
    cost: mtHandleDlg.cost, inWarranty: mtHandleDlg.inWarranty,
    handleNote: mtHandleDlg.handleNote, recordFault: mtHandleDlg.recordFault,
    scrapped: mtHandleDlg.scrapped,
  })
  const tail = mtHandleDlg.targetType === 'inv_item'
    ? (mtHandleDlg.scrapped ? `· ${mtHandleDlg.qty} 件已转报废` : `· ${mtHandleDlg.qty} 件已回库存`)
    : (mtHandleDlg.inWarranty ? '(质保内→转供应商·费用不计我方)' : '')
  ElMessage.success('已完工 ' + tail)
  mtHandleDlg.show = false
  allItems.value = []
  loadMaintenances()
}

function loadTab(t: string) {
  if (t === 'damages') { loadPending(); return loadDamages() }
  if (t === 'maintenance') { loadMaintenanceRefs(); return loadMaintenances() }
  if (t === 'settings') return loadSettings()
}
watch(tab, (t) => {
  router.replace({ query: { ...route.query, tab: t } })
  loadTab(t)
})
onMounted(() => loadTab(tab.value))
</script>

<template>
  <div class="svc">
    <el-tabs v-model="tab">
      <!-- ============ 维保工单 ============ -->
      <el-tab-pane label="维保工单" name="maintenance">
        <div class="bar">
          <el-select v-model="mtFilters.targetType" placeholder="对象" clearable size="small" style="width:130px" @change="loadMaintenances">
            <el-option label="台账设备" value="asset" />
            <el-option label="仓库物品" value="inv_item" />
          </el-select>
          <el-select v-model="mtFilters.status" placeholder="状态" clearable size="small" style="width:120px" @change="loadMaintenances">
            <el-option v-for="st in ['待派工','处理中','已完成','已关闭']" :key="st" :label="st" :value="st" />
          </el-select>
          <el-select v-model="mtFilters.type" placeholder="类型" clearable size="small" style="width:110px" @change="loadMaintenances">
            <el-option v-for="t in ['报修','预防','巡检']" :key="t" :label="t" :value="t" />
          </el-select>
          <el-button size="small" @click="loadMaintenances">查询</el-button>
          <el-button v-if="canOperate" size="small" type="primary" @click="openMtCreate">＋ 报修</el-button>
          <span class="muted">共 {{ mtTotal }} 单</span>
        </div>
        <el-table :data="mtList" v-loading="mtLoading" size="small" border>
          <el-table-column prop="no" label="工单号" min-width="130" />
          <el-table-column label="对象" min-width="180">
            <template #default="{ row }">
              <el-tag size="small" :type="row.targetType === 'inv_item' ? 'warning' : 'info'" effect="plain">
                {{ row.targetType === 'inv_item' ? '仓库物品' : '台账设备' }}
              </el-tag>
              <span style="margin-left:6px">{{ row.targetLabel || '—' }}</span>
              <span v-if="row.targetType === 'inv_item' && row.qty" class="muted"> × {{ row.qty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="70">
            <template #default="{ row }"><el-tag size="small" :type="mtTypeTag[row.type] || 'info'">{{ row.type }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="bomName" label="故障配件" width="110"><template #default="{ row }">{{ row.bomName || '—' }}</template></el-table-column>
          <el-table-column prop="faultDesc" label="故障描述" min-width="150" show-overflow-tooltip />
          <el-table-column label="责任方" width="100">
            <template #default="{ row }">
              {{ row.responsibleParty }}<el-tag v-if="row.inWarranty" type="success" size="small">质保</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="我方成本" width="110" align="right"><template #default="{ row }">{{ money(row.ourCost) }}</template></el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }"><el-tag size="small" :type="mtTag[row.status] || 'info'">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button v-if="row.status === '待派工' && canOperate" link type="primary" size="small" @click="onMtAssign(row)">派工</el-button>
              <el-button v-if="row.status === '处理中' && canOperate" link type="success" size="small" @click="openMtHandle(row)">完工</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="muted" style="margin-top:8px">
          我方成本 = 责任方我方 且 非质保内 才计入；质保内自动转供应商，费用记录但我方成本为 0。
          仓库物品报修会把数量从「库存」转入「维修中」，完工按结果回库存或转报废——数量只由资产管理「出入库记录」一处记账。
        </div>

        <el-card v-if="spare && spare.alertCount" shadow="never" style="margin-top:12px">
          <template #header>高故障配件备件提示（故障次数 &gt; {{ spare.threshold }}）· 共 {{ spare.alertCount }} 项</template>
          <el-table :data="spare.items" size="small" border>
            <el-table-column prop="serialNo" label="设备" width="140" />
            <el-table-column prop="bomName" label="配件" min-width="130" />
            <el-table-column label="故障次数" width="90" align="right">
              <template #default="{ row }"><b class="danger-text">{{ row.faultCount }}</b></template>
            </el-table-column>
            <el-table-column label="可维修" width="90">
              <template #default="{ row }"><el-tag size="small" :type="row.repairable ? 'success' : 'danger'">{{ row.repairable ? '可修' : '不可修' }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="suggestion" label="建议" min-width="200" />
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ============ 损坏与赔偿 ============ -->
      <el-tab-pane label="损坏与赔偿" name="damages">
        <div class="bar">
          <el-input v-model="damageFilters.keyword" placeholder="检查项/客户/出租单/资产" clearable style="width:220px" @keyup.enter="loadDamages" @clear="loadDamages" />
          <el-select v-model="damageFilters.settleStatus" placeholder="处理状态" clearable style="width:110px" @change="loadDamages">
            <el-option v-for="s in SETTLE_STATUSES" :key="s" :label="s" :value="s" />
          </el-select>
          <el-button @click="loadDamages">查询</el-button>
          <span class="grow" />
          <span class="tip">待收取合计 <b class="bad">{{ money(pendingSum) }}</b></span>
          <span>本页合计 <b class="bad">{{ money(damageSum) }}</b></span>
        </div>
        <el-table :data="damageRows" v-loading="damagesLoading" size="small" border>
          <el-table-column label="登记时间" width="140"><template #default="{ row }">{{ fmtTime(row.createTime) }}</template></el-table-column>
          <el-table-column label="客户 / 出租单" min-width="140"><template #default="{ row }">{{ row.customerName || '—' }}<div class="tip">{{ row.rentalNo }}</div></template></el-table-column>
          <el-table-column label="资产" min-width="120"><template #default="{ row }">{{ row.itemName }}<div class="tip">{{ row.itemCode }}</div></template></el-table-column>
          <el-table-column prop="partName" label="检查项" width="100" />
          <el-table-column label="损坏 × 单价" width="130"><template #default="{ row }">{{ row.damagedQty }} × {{ money(row.damagePrice) }}</template></el-table-column>
          <el-table-column label="缺失 × 单价" width="130"><template #default="{ row }">{{ row.missingQty }} × {{ money(row.missingPrice) }}</template></el-table-column>
          <el-table-column label="赔偿金额" width="110" align="right"><template #default="{ row }"><b>{{ money(row.amount) }}</b></template></el-table-column>
          <el-table-column label="处理" width="190">
            <template #default="{ row }">
              <el-select v-if="canSettle" :model-value="row.settleStatus" size="small" style="width:100px" @change="(v: string) => settle(row, v)">
                <el-option v-for="s in SETTLE_STATUSES" :key="s" :label="s" :value="s" />
              </el-select>
              <el-tag v-else :type="settleTag[row.settleStatus]" size="small">{{ row.settleStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="回单" width="80">
            <template #default="{ row }">
              <el-link v-if="row.proofCount" type="primary" @click="openDamageProof(row)">📎 {{ row.proofCount }}</el-link>
              <el-tag v-else-if="row.settleStatus === '已收取'" type="warning" size="small" effect="plain" style="cursor:pointer" @click="openDamageProof(row)">无回单</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="说明" min-width="120" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <!-- ============ 设置 ============ -->
      <el-tab-pane label="设置" name="settings">
        <div v-loading="settingsLoading" class="settings">
          <el-card shadow="never">
            <template #header>损坏缺件赔偿价目（归还时自动计算：损坏数 × 损坏单价 + 缺失数 × 缺失单价）</template>
            <el-table :data="priceRows" size="small" border>
              <el-table-column label="检查项" min-width="120">
                <template #default="{ row }"><el-input v-model="row.partName" size="small" maxlength="32" :disabled="!canSettle" /></template>
              </el-table-column>
              <el-table-column label="单位" width="90">
                <template #default="{ row }"><el-input v-model="row.unit" size="small" maxlength="8" :disabled="!canSettle" /></template>
              </el-table-column>
              <el-table-column label="损坏单价(元)" width="140">
                <template #default="{ row }"><el-input-number v-model="row.damagePrice" size="small" :min="0" :precision="2" :controls="false" style="width:100%" :disabled="!canSettle" /></template>
              </el-table-column>
              <el-table-column label="缺失单价(元)" width="140">
                <template #default="{ row }"><el-input-number v-model="row.missingPrice" size="small" :min="0" :precision="2" :controls="false" style="width:100%" :disabled="!canSettle" /></template>
              </el-table-column>
              <el-table-column label="" width="60">
                <template #default="{ $index }"><el-button link type="danger" size="small" :disabled="!canSettle" @click="priceRows.splice($index, 1)">删</el-button></template>
              </el-table-column>
            </el-table>
            <div class="bar mt6">
              <el-button size="small" :disabled="!canSettle" @click="priceRows.push({ partName: '', unit: '个', damagePrice: 0, missingPrice: 0 })">＋ 加一项</el-button>
              <el-button size="small" type="primary" :disabled="!canSettle" :loading="priceSaving" @click="savePrices">保存价目</el-button>
              <span class="tip">单价为 0 的检查项登记时金额按 0 计。改价不影响已登记的赔偿。{{ canSettle ? '' : '（老板/财务/供应链可修改）' }}</span>
            </div>
          </el-card>

          <el-card shadow="never" class="mt12">
            <template #header>二维码标签企业信息（贴在每套播种墙、每组货架上，扫码页顶部展示）</template>
            <el-form label-width="90px" style="max-width:520px">
              <el-form-item label="企业名称" required><el-input v-model="companyForm.companyName" maxlength="128" :disabled="!canCompany" /></el-form-item>
              <el-form-item label="服务电话"><el-input v-model="companyForm.phone" maxlength="32" :disabled="!canCompany" /></el-form-item>
              <el-form-item label="企业地址"><el-input v-model="companyForm.address" maxlength="255" :disabled="!canCompany" /></el-form-item>
              <el-form-item label="网址"><el-input v-model="companyForm.website" maxlength="128" :disabled="!canCompany" /></el-form-item>
              <el-form-item label="标签提示语"><el-input v-model="companyForm.notice" maxlength="255" :disabled="!canCompany" /></el-form-item>
              <el-form-item><el-button type="primary" :disabled="!canCompany" :loading="companySaving" @click="saveCompany">保存企业信息</el-button></el-form-item>
            </el-form>
          </el-card>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 维保报修建单 -->
    <el-dialog v-model="mtCreateDlg" title="报修 / 预防 / 巡检 建单" width="620px">
      <el-form label-width="96px">
        <el-form-item label="报修对象">
          <el-radio-group v-model="mtForm.targetType">
            <el-radio-button label="asset">台账设备</el-radio-button>
            <el-radio-button label="inv_item">仓库物品</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <template v-if="mtForm.targetType === 'asset'">
          <el-form-item label="设备">
            <el-select v-model="mtForm.assetId" filterable clearable style="width:100%" placeholder="从设备 · 租赁台账里选">
              <el-option v-for="a in mtAssets" :key="a.id" :label="mtAssetLabel(a)" :value="a.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="故障配件">
            <el-input-number v-model="mtForm.bomId" :min="1" placeholder="配件 BOM 的序号(可空)" controls-position="right" style="width:220px" />
            <span class="muted" style="margin-left:8px">填了会在完工时回写该配件的故障次数</span>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="物品">
            <el-select v-model="mtForm.invItemId" filterable clearable style="width:100%" placeholder="从资产台账里选">
              <el-option v-for="i in allItems" :key="i.id"
                :label="`${i.name} · ${i.code}（库存 ${i.stockQty} ${i.unit}）`" :value="i.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="送修数量">
            <el-input-number v-model="mtForm.qty" :min="1" :max="mtPickedItem?.stockQty || 1" controls-position="right" style="width:160px" />
            <span v-if="mtPickedItem" class="muted" style="margin-left:8px">
              当前库存 {{ mtPickedItem.stockQty }} {{ mtPickedItem.unit }}；建单后这 {{ mtForm.qty }} 件转入「维修中」
            </span>
          </el-form-item>
        </template>
        <el-form-item label="工单类型">
          <el-select v-model="mtForm.type" style="width:140px">
            <el-option v-for="t in ['报修','预防','巡检']" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="故障描述">
          <el-input v-model="mtForm.faultDesc" type="textarea" :rows="2" maxlength="512" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mtCreateDlg = false">取消</el-button>
        <el-button type="primary" @click="submitMtCreate">提交建单</el-button>
      </template>
    </el-dialog>

    <!-- 维保完工回写 -->
    <el-dialog v-model="mtHandleDlg.show" :title="'处理完工 ' + (mtHandleDlg.no || '')" width="520px">
      <el-form label-width="110px">
        <el-form-item label="维修费用">
          <el-input-number v-model="mtHandleDlg.cost" :min="0" controls-position="right" style="width:180px" />
        </el-form-item>
        <el-form-item label="质保内">
          <el-switch v-model="mtHandleDlg.inWarranty" />
          <span class="muted" style="margin-left:8px">质保内 → 转供应商，费用不计我方</span>
        </el-form-item>
        <el-form-item v-if="mtHandleDlg.targetType === 'inv_item'" label="处理结果">
          <el-switch v-model="mtHandleDlg.scrapped" active-text="修不好，报废" inactive-text="修好，回库存" />
          <div class="muted">
            {{ mtHandleDlg.scrapped
              ? `${mtHandleDlg.qty} 件从「维修中」转「已报废」`
              : `${mtHandleDlg.qty} 件从「维修中」回「库存」` }}
          </div>
        </el-form-item>
        <el-form-item v-else label="回写故障计数">
          <el-switch v-model="mtHandleDlg.recordFault" />
          <span class="muted" style="margin-left:8px">给故障配件的故障次数 +1（报修默认开）</span>
        </el-form-item>
        <el-form-item label="处理记录">
          <el-input v-model="mtHandleDlg.handleNote" type="textarea" :rows="2" maxlength="512" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mtHandleDlg.show = false">取消</el-button>
        <el-button type="primary" @click="submitMtHandle">确认完工</el-button>
      </template>
    </el-dialog>

    <!-- 赔偿已收取:先传收款回单 -->
    <el-dialog v-model="settleDlg.show" title="赔偿已收取" width="460px" destroy-on-close>
      <div v-if="settleDlg.row" class="tip mb8">{{ settleDlg.row.customerName || '—' }} · {{ settleDlg.row.partName }} · {{ money(settleDlg.row.amount) }}</div>
      <el-form label-width="90px">
        <el-form-item label="收款回单" required><ProofUploader v-model="settleDlg.proofFileIds" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="settleDlg.show = false">取消</el-button>
        <el-button type="primary" :disabled="!settleDlg.proofFileIds.length" :loading="settleDlg.saving" @click="submitSettle">确认</el-button>
      </template>
    </el-dialog>
    <ProofViewer v-model="damageProof.show" biz-type="inv_damage" :biz-id="damageProof.id" :title="damageProof.title"
      :can-supplement="canSettle" @changed="loadDamages" />
  </div>
</template>

<style scoped>
.svc { padding-bottom: 24px; }
.bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.grow { flex: 1; }
.tip { color: #909399; font-size: 12px; line-height: 1.6; }
.bad { color: #e0533d; font-size: 12px; }
.muted { color: #909399; font-size: 12px; }
.danger-text { color: #e0533d; }
.mt6 { margin-top: 6px; }
.mt12 { margin-top: 12px; }
.mb8 { margin-bottom: 8px; }
</style>
