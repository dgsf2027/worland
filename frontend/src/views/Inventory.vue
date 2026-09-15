<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchInvItems, fetchInvItem, createInvItem, updateInvItem, deleteInvItem, adjustInvItem,
  fetchInvRentals, createInvRental, updateInvRental, cancelInvRental,
  fetchInvMovements, fetchInvDamages, settleInvDamage,
  fetchCompPrices, saveCompPrices, fetchInvCompany, saveInvCompany, fetchInvOverview, scanUrl, currentRole,
  INV_CATEGORIES, INV_UNITS, RENTAL_STATUSES, SETTLE_STATUSES, OPERATE_ROLES, SETTLE_ROLES,
  type InvItem, type InvRental, type InvMovement, type InvDamage, type ItemDetail, type PriceRow,
  type CompanyInfo, type Overview, type Reminder,
} from '@/api/inventory'
import { fetchCustomerPool, type CustomerPoolItem } from '@/api/customer'
import { fetchContracts, type ContractListItem } from '@/api/contract'
import PhotoGallery from '@/components/inventory/PhotoGallery.vue'
import MovementForm from '@/components/inventory/MovementForm.vue'
import { printQrLabels, qrDataUrl } from '@/utils/qrLabel'

const route = useRoute()
const router = useRouter()
const role = currentRole()
const canOperate = OPERATE_ROLES.includes(role)
const canSettle = SETTLE_ROLES.includes(role)
const canDelete = ['老板', '供应链'].includes(role)
const canCompany = ['老板', '供应链'].includes(role)

const tab = ref<string>((route.query.tab as string) || 'overview')
watch(tab, (t) => {
  router.replace({ query: { ...route.query, tab: t } })
  loadTab(t)
})

function fmtTime(v?: string) { return v ? v.replace('T', ' ').slice(0, 16) : '—' }
const money = (v?: number) => '¥' + Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const rentalTag: Record<string, string> = { 已预订: 'warning', 出租中: 'primary', 已归还: 'success', 已取消: 'info' }
const moveTag: Record<string, string> = { 出库: 'primary', 归还: 'success', 入库: '', 送修: 'warning', 修好: 'success', 报废: 'danger' }
const settleTag: Record<string, string> = { 待收取: 'danger', 已收取: 'success', 已减免: 'info' }

// ============ 提醒与报表 ============
const overview = ref<Overview | null>(null)
const allItems = ref<InvItem[]>([])
const overviewLoading = ref(false)
async function loadOverview() {
  overviewLoading.value = true
  try {
    const [o, items] = await Promise.all([fetchInvOverview(), fetchInvItems({ page: 1, size: 5000 })])
    overview.value = o
    allItems.value = items.records
  } finally {
    overviewLoading.value = false
  }
}
/** 按类别统计:库存(闲置)/已预订/出租/维修/报废 */
const categoryReport = computed(() => {
  const map = new Map<string, { category: string; itemCount: number; total: number; stock: number; reserved: number; rented: number; repair: number; scrapped: number }>()
  for (const it of allItems.value) {
    const k = it.category || '未分类'
    const r = map.get(k) || { category: k, itemCount: 0, total: 0, stock: 0, reserved: 0, rented: 0, repair: 0, scrapped: 0 }
    r.itemCount++
    r.total += it.totalQty
    r.stock += it.stockQty
    r.reserved += it.reservedQty
    r.rented += it.rentedQty
    r.repair += it.repairQty
    r.scrapped += it.scrappedQty
    map.set(k, r)
  }
  return [...map.values()]
})
const rentRate = (rented: number, total: number, scrapped: number) => {
  const base = total - scrapped
  return base > 0 ? Math.round((rented / base) * 1000) / 10 + '%' : '—'
}
function onReminder(r: Reminder) {
  if (r.rentalId) {
    rentalFilters.keyword = ''
    rentalFilters.status = ''
    tab.value = 'rentals'
    highlightRentalId.value = r.rentalId
  } else if (r.itemId) {
    openDetail(r.itemId)
  } else if (r.type === '赔偿待收') {
    damageFilters.settleStatus = '待收取'
    tab.value = 'damages'
  }
}

// ============ 资产台账 ============
const itemFilters = reactive({ keyword: '', category: '' })
const items = ref<InvItem[]>([])
const itemsLoading = ref(false)
const selectedItems = ref<InvItem[]>([])
async function loadItems() {
  itemsLoading.value = true
  try {
    items.value = (await fetchInvItems({ page: 1, size: 5000, ...itemFilters })).records
  } finally {
    itemsLoading.value = false
  }
}

const itemDialog = ref(false)
const itemSaving = ref(false)
const editingItemId = ref<number | null>(null)
const itemForm = reactive({ code: '', name: '', spec: '', category: '播种墙', unit: '套', location: '', remark: '', initialQty: 0 })
function openItemForm(row?: InvItem) {
  editingItemId.value = row?.id ?? null
  Object.assign(itemForm, row
    ? { code: row.code, name: row.name, spec: row.spec || '', category: row.category || '', unit: row.unit, location: row.location || '', remark: row.remark || '', initialQty: 0 }
    : { code: '', name: '', spec: '', category: '播种墙', unit: '套', location: '', remark: '', initialQty: 0 })
  itemDialog.value = true
}
async function saveItem() {
  if (!itemForm.name.trim()) { ElMessage.warning('请填写名称'); return }
  itemSaving.value = true
  try {
    if (editingItemId.value) {
      await updateInvItem(editingItemId.value, itemForm)
      ElMessage.success('已保存')
    } else {
      const id = await createInvItem(itemForm)
      ElMessage.success('资产已建档，可在详情里上传照片、打印二维码标签')
      itemDialog.value = false
      await loadItems()
      openDetail(id)
      return
    }
    itemDialog.value = false
    await refreshAfterChange()
  } finally {
    itemSaving.value = false
  }
}
async function removeItem(row: InvItem) {
  await ElMessageBox.confirm(`删除资产「${row.name} ${row.code}」？出入库记录保留。`, '删除资产', { type: 'warning' })
  await deleteInvItem(row.id)
  ElMessage.success('已删除')
  if (detail.value?.item.id === row.id) detailVisible.value = false
  await refreshAfterChange()
}

// ---- 状态调整 ----
const adjustDialog = ref(false)
const adjustItem = ref<InvItem | null>(null)
const adjustForm = reactive({ type: '入库', qty: 1, fromStatus: '库存', conditionDesc: '', remark: '' })
const adjustTypes = [
  { value: '入库', tip: '新增数量 → 库存' },
  { value: '送修', tip: '库存 → 维修中' },
  { value: '修好', tip: '维修中 → 库存' },
  { value: '报废', tip: '库存/维修中 → 已报废' },
]
function openAdjust(row: InvItem, type = '入库') {
  adjustItem.value = row
  Object.assign(adjustForm, { type, qty: 1, fromStatus: type === '报废' && row.stockQty === 0 ? '维修中' : '库存', conditionDesc: '', remark: '' })
  adjustDialog.value = true
}
const adjustMax = computed(() => {
  const it = adjustItem.value
  if (!it) return 1
  if (adjustForm.type === '入库') return 100000
  if (adjustForm.type === '送修') return it.stockQty
  if (adjustForm.type === '修好') return it.repairQty
  return adjustForm.fromStatus === '维修中' ? it.repairQty : it.stockQty
})
const adjustSaving = ref(false)
async function saveAdjust() {
  if (!adjustItem.value) return
  if (adjustForm.qty <= 0 || adjustForm.qty > adjustMax.value) { ElMessage.warning(`数量须在 1 ~ ${adjustMax.value} 之间`); return }
  adjustSaving.value = true
  try {
    await adjustInvItem(adjustItem.value.id, { ...adjustForm })
    ElMessage.success(`${adjustForm.type} ${adjustForm.qty} 已登记`)
    adjustDialog.value = false
    await refreshAfterChange()
  } finally {
    adjustSaving.value = false
  }
}

// ---- 详情 ----
const detailVisible = ref(false)
const detail = ref<ItemDetail | null>(null)
const detailLoading = ref(false)
async function openDetail(id: number) {
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await fetchInvItem(id)
    router.replace({ query: { ...route.query, id: String(id) } })
  } finally {
    detailLoading.value = false
  }
}
watch(detailVisible, (v) => {
  if (!v && route.query.id) {
    const q = { ...route.query }
    delete q.id
    router.replace({ query: q })
  }
})

// ---- 二维码标签 ----
const company = ref<CompanyInfo>({ companyName: '曜石科技' })
const labelDialog = ref(false)
const labelItems = ref<InvItem[]>([])
const labelCopies = ref(1)
const labelPreview = ref('')
async function openLabels(rows: InvItem[]) {
  if (!rows.length) { ElMessage.warning('请先勾选资产'); return }
  labelItems.value = rows
  labelCopies.value = rows.length === 1 ? Math.max(1, rows[0].totalQty - rows[0].scrappedQty) : 1
  company.value = await fetchInvCompany()
  labelPreview.value = await qrDataUrl(rows[0].qrToken, 240)
  labelDialog.value = true
}
async function doPrint() {
  const ok = await printQrLabels(labelItems.value, company.value, labelCopies.value)
  if (!ok) ElMessage.warning('浏览器拦截了打印窗口，请允许本站弹出窗口后重试')
}
async function copyScanLink(it: InvItem) {
  try {
    await navigator.clipboard.writeText(scanUrl(it.qrToken))
    ElMessage.success('扫码链接已复制')
  } catch {
    ElMessageBox.alert(scanUrl(it.qrToken), '扫码链接')
  }
}

// ============ 出租管理 ============
const rentalFilters = reactive({ keyword: '', status: '' })
const rentals = ref<InvRental[]>([])
const rentalsLoading = ref(false)
const highlightRentalId = ref<number | null>(null)
async function loadRentals() {
  rentalsLoading.value = true
  try {
    rentals.value = (await fetchInvRentals({ page: 1, size: 5000, ...rentalFilters })).records
  } finally {
    rentalsLoading.value = false
  }
}
const rentalRowClass = ({ row }: { row: InvRental }) => (row.id === highlightRentalId.value ? 'row-hl' : row.overdueDays ? 'row-overdue' : '')

const customers = ref<CustomerPoolItem[]>([])
const contracts = ref<ContractListItem[]>([])
async function loadCustomers() {
  if (!customers.value.length) customers.value = (await fetchCustomerPool({ page: 1, size: 1000 })).records
}
async function loadContracts(customerId?: number | null) {
  contracts.value = customerId ? (await fetchContracts({ customerId, page: 1, size: 200 })).records : []
}

const rentalDialog = ref(false)
const rentalSaving = ref(false)
const editingRental = ref<InvRental | null>(null)
const rentalForm = reactive({
  itemId: null as number | null,
  customerMode: 'crm' as 'crm' | 'text',
  customerId: null as number | null,
  customerName: '',
  contractId: null as number | null,
  installAddress: '',
  contact: '',
  phone: '',
  qty: 1,
  dates: [] as string[],
  remark: '',
})
async function openRentalForm(opts: { item?: InvItem; rental?: InvRental }) {
  await Promise.all([loadCustomers(), allItems.value.length ? Promise.resolve() : fetchInvItems({ page: 1, size: 5000 }).then((r) => { allItems.value = r.records })])
  const r = opts.rental
  editingRental.value = r ?? null
  Object.assign(rentalForm, r
    ? {
      itemId: r.itemId, customerMode: r.customerId ? 'crm' : 'text', customerId: r.customerId ?? null, customerName: r.customerName,
      contractId: r.contractId ?? null, installAddress: r.installAddress || '', contact: r.contact || '', phone: r.phone || '',
      qty: r.qty, dates: [r.startDate, r.expectedReturnDate], remark: r.remark || '',
    }
    : {
      itemId: opts.item?.id ?? null, customerMode: 'crm', customerId: null, customerName: '', contractId: null,
      installAddress: '', contact: '', phone: '', qty: 1, dates: [], remark: '',
    })
  await loadContracts(rentalForm.customerId)
  rentalDialog.value = true
}
watch(() => rentalForm.customerId, (id, old) => {
  if (!rentalDialog.value || id === old) return
  rentalForm.contractId = null
  loadContracts(id)
})
const rentalItem = computed(() => allItems.value.find((i) => i.id === rentalForm.itemId))
const rentalMaxQty = computed(() => {
  const it = rentalItem.value
  if (!it) return 1
  return editingRental.value ? editingRental.value.qty + it.stockQty : it.stockQty
})
async function saveRental() {
  const f = rentalForm
  if (!f.itemId) { ElMessage.warning('请选择资产'); return }
  if (f.customerMode === 'crm' ? !f.customerId : !f.customerName.trim()) { ElMessage.warning('请选择或填写客户'); return }
  if (f.dates.length !== 2) { ElMessage.warning('请选择开始时间和预计归还时间'); return }
  const body = {
    itemId: f.itemId,
    customerId: f.customerMode === 'crm' ? f.customerId : null,
    customerName: f.customerMode === 'text' ? f.customerName : '',
    contractId: f.customerMode === 'crm' ? f.contractId : null,
    installAddress: f.installAddress, contact: f.contact, phone: f.phone,
    qty: f.qty, startDate: f.dates[0], expectedReturnDate: f.dates[1], remark: f.remark,
  }
  rentalSaving.value = true
  try {
    if (editingRental.value) {
      await updateInvRental(editingRental.value.id, body)
      ElMessage.success('出租单已保存')
    } else {
      await createInvRental(body)
      ElMessage.success('已预订：库存已锁定，出库时在「出租管理」或扫码登记')
    }
    rentalDialog.value = false
    await refreshAfterChange()
  } finally {
    rentalSaving.value = false
  }
}
async function cancelRental(r: InvRental) {
  const msg = r.outQty === 0
    ? `取消出租单 ${r.rentalNo}，预订的 ${r.qty} 退回库存？`
    : `出租单 ${r.rentalNo} 已出库 ${r.outQty}，释放未出库的 ${r.pendingOutQty} 退回库存，出租数量改为 ${r.outQty}？`
  await ElMessageBox.confirm(msg, r.outQty === 0 ? '取消出租' : '释放预订', { type: 'warning' })
  await cancelInvRental(r.id)
  ElMessage.success('已处理')
  await refreshAfterChange()
}

// ---- 出库 / 归还 ----
const moveDialog = ref(false)
const moveMode = ref<'out' | 'return'>('out')
const moveRental = ref<InvRental | null>(null)
const prices = ref<PriceRow[]>([])
async function openMove(r: InvRental, mode: 'out' | 'return') {
  prices.value = await fetchCompPrices()
  moveRental.value = r
  moveMode.value = mode
  moveDialog.value = true
}
async function onMoveDone() {
  moveDialog.value = false
  await refreshAfterChange()
}

// ============ 出入库记录 ============
const moveFilters = reactive({ type: '' })
const movements = ref<InvMovement[]>([])
const movementsLoading = ref(false)
async function loadMovements() {
  movementsLoading.value = true
  try {
    movements.value = (await fetchInvMovements({ page: 1, size: 1000, ...moveFilters })).records
  } finally {
    movementsLoading.value = false
  }
}

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
async function settle(d: InvDamage, status: string) {
  if (status === d.settleStatus) return
  await ElMessageBox.confirm(`${d.partName} ${money(d.amount)} 标记为「${status}」？`, '赔偿处理', { type: 'info' })
  await settleInvDamage(d.id, { settleStatus: status })
  ElMessage.success('已更新')
  await loadDamages()
}

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

// ============ 公共 ============
function loadTab(t: string) {
  if (t === 'overview') return loadOverview()
  if (t === 'items') return loadItems()
  if (t === 'rentals') return loadRentals()
  if (t === 'movements') return loadMovements()
  if (t === 'damages') return loadDamages()
  if (t === 'settings') return loadSettings()
}
async function refreshAfterChange() {
  await loadTab(tab.value)
  if (tab.value !== 'overview') fetchInvItems({ page: 1, size: 5000 }).then((r) => { allItems.value = r.records })
  if (detailVisible.value && detail.value) detail.value = await fetchInvItem(detail.value.item.id)
}

onMounted(() => {
  loadTab(tab.value)
  const id = Number(route.query.id)
  if (id) openDetail(id)
})
</script>

<template>
  <div class="inv">
    <el-tabs v-model="tab">
      <!-- ============ 提醒与报表 ============ -->
      <el-tab-pane label="提醒与报表" name="overview">
        <div v-loading="overviewLoading">
          <div v-if="overview" class="stats">
            <div class="stat"><div class="v">{{ overview.totalQty }}</div><div class="l">总数量（{{ overview.itemCount }} 批）</div></div>
            <div class="stat"><div class="v">{{ overview.inStoreQty }}</div><div class="l">库存数量（在库 = 闲置 + 已预订）</div></div>
            <div class="stat ok"><div class="v">{{ overview.idleQty }}</div><div class="l">闲置数量（在库未预订）</div></div>
            <div class="stat warn"><div class="v">{{ overview.reservedQty }}</div><div class="l">已预订</div></div>
            <div class="stat blue"><div class="v">{{ overview.rentedQty }}</div><div class="l">出租数量</div></div>
            <div class="stat warn"><div class="v">{{ overview.repairQty }}</div><div class="l">维修中</div></div>
            <div class="stat grey"><div class="v">{{ overview.scrappedQty }}</div><div class="l">已报废</div></div>
            <div class="stat"><div class="v">{{ overview.activeRentalCount }}</div><div class="l">在租/预订单</div></div>
            <div class="stat bad"><div class="v">{{ overview.overdueRentalCount }}</div><div class="l">逾期未还单</div></div>
            <div class="stat bad"><div class="v">{{ money(overview.pendingCompensation) }}</div><div class="l">赔偿待收取</div></div>
          </div>

          <el-card shadow="never" class="mt12">
            <template #header>提醒（合同到期 30 天内 · 预计归还 7 天内 · 逾期 · 需要维修 · 赔偿待收）</template>
            <el-empty v-if="overview && !overview.reminders.length" description="暂无提醒" :image-size="60" />
            <div v-for="(r, i) in overview?.reminders || []" :key="i" class="remind" @click="onReminder(r)">
              <el-tag :type="r.level" size="small" effect="dark">{{ r.type }}</el-tag>
              <span class="rt">{{ r.title }}</span>
              <span class="tip">{{ r.detail }}</span>
            </div>
          </el-card>

          <el-card shadow="never" class="mt12">
            <template #header>按类别统计</template>
            <el-table :data="categoryReport" size="small" border show-summary>
              <el-table-column prop="category" label="类别" min-width="100" />
              <el-table-column prop="itemCount" label="批数" width="70" align="right" />
              <el-table-column prop="total" label="总数量" width="90" align="right" />
              <el-table-column label="库存(在库)" width="100" align="right">
                <template #default="{ row }">{{ row.stock + row.reserved }}</template>
              </el-table-column>
              <el-table-column prop="stock" label="闲置" width="80" align="right" />
              <el-table-column prop="reserved" label="已预订" width="80" align="right" />
              <el-table-column prop="rented" label="出租" width="80" align="right" />
              <el-table-column prop="repair" label="维修中" width="80" align="right" />
              <el-table-column prop="scrapped" label="已报废" width="80" align="right" />
              <el-table-column label="出租率" width="90" align="right">
                <template #default="{ row }">{{ rentRate(row.rented, row.total, row.scrapped) }}</template>
              </el-table-column>
            </el-table>
            <div class="tip mt6">出租率 = 出租数量 ÷（总数量 − 已报废）。</div>
          </el-card>
        </div>
      </el-tab-pane>

      <!-- ============ 资产台账 ============ -->
      <el-tab-pane label="资产台账" name="items">
        <div class="bar">
          <el-input v-model="itemFilters.keyword" placeholder="编号/名称/规格/存放位置" clearable style="width:220px" @keyup.enter="loadItems" @clear="loadItems" />
          <el-select v-model="itemFilters.category" placeholder="类别" clearable style="width:120px" @change="loadItems">
            <el-option v-for="c in INV_CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
          <el-button @click="loadItems">查询</el-button>
          <span class="grow" />
          <el-button :disabled="!selectedItems.length" @click="openLabels(selectedItems)">🏷️ 打印二维码标签（{{ selectedItems.length }}）</el-button>
          <el-button v-if="canOperate" type="primary" @click="openItemForm()">＋ 新建资产</el-button>
        </div>
        <el-table :data="items" v-loading="itemsLoading" size="small" border @selection-change="(v: InvItem[]) => (selectedItems = v)">
          <el-table-column type="selection" width="36" />
          <el-table-column label="编号" min-width="130">
            <template #default="{ row }"><el-link type="primary" @click="openDetail(row.id)">{{ row.code }}</el-link></template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="100" />
          <el-table-column prop="spec" label="规格" min-width="120" show-overflow-tooltip />
          <el-table-column prop="category" label="类别" width="80" />
          <el-table-column prop="location" label="存放位置" min-width="120" show-overflow-tooltip />
          <el-table-column label="总数" width="70" align="right">
            <template #default="{ row }">{{ row.totalQty }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column prop="stockQty" label="库存" width="60" align="right" />
          <el-table-column prop="reservedQty" label="已预订" width="66" align="right" />
          <el-table-column prop="rentedQty" label="出租中" width="66" align="right" />
          <el-table-column prop="repairQty" label="维修中" width="66" align="right" />
          <el-table-column prop="scrappedQty" label="已报废" width="66" align="right" />
          <el-table-column label="照片" width="56" align="center">
            <template #default="{ row }">{{ row.photoCount || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="290" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(row.id)">详情</el-button>
              <el-button v-if="canOperate" link type="primary" size="small" :disabled="!row.stockQty" @click="openRentalForm({ item: row })">出租</el-button>
              <el-button v-if="canOperate" link type="primary" size="small" @click="openAdjust(row)">状态调整</el-button>
              <el-button link type="primary" size="small" @click="openLabels([row])">二维码</el-button>
              <el-button v-if="canOperate" link type="primary" size="small" @click="openItemForm(row)">编辑</el-button>
              <el-button v-if="canDelete" link type="danger" size="small" @click="removeItem(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="tip mt6">一条资产 = 一批同规格实物（如某规格播种墙 10 套），按数量管理：总数 = 库存 + 已预订 + 出租中 + 维修中 + 已报废。</div>
      </el-tab-pane>

      <!-- ============ 出租管理 ============ -->
      <el-tab-pane label="出租管理" name="rentals">
        <div class="bar">
          <el-input v-model="rentalFilters.keyword" placeholder="出租单号/客户/安装地址" clearable style="width:220px" @keyup.enter="loadRentals" @clear="loadRentals" />
          <el-select v-model="rentalFilters.status" placeholder="状态" clearable style="width:110px" @change="loadRentals">
            <el-option v-for="s in RENTAL_STATUSES" :key="s" :label="s" :value="s" />
          </el-select>
          <el-button @click="loadRentals">查询</el-button>
          <span class="grow" />
          <el-button v-if="canOperate" type="primary" @click="openRentalForm({})">＋ 新建出租</el-button>
        </div>
        <el-table :data="rentals" v-loading="rentalsLoading" size="small" border :row-class-name="rentalRowClass">
          <el-table-column prop="rentalNo" label="出租单号" width="130" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="rentalTag[row.status]" size="small">{{ row.status }}</el-tag>
              <div v-if="row.overdueDays" class="bad">逾期{{ row.overdueDays }}天</div>
            </template>
          </el-table-column>
          <el-table-column label="客户" min-width="130">
            <template #default="{ row }">
              <el-link v-if="row.customerId" type="primary" @click="router.push({ path: '/customer', query: { id: row.customerId } })">{{ row.customerName }}</el-link>
              <span v-else>{{ row.customerName }}</span>
              <div v-if="row.contractNo" class="tip">合同 {{ row.contractNo }}<template v-if="row.contractEndDate"> · 到期 {{ row.contractEndDate }}</template></div>
            </template>
          </el-table-column>
          <el-table-column label="资产" min-width="140">
            <template #default="{ row }">
              <el-link @click="openDetail(row.itemId)">{{ row.itemName }}</el-link>
              <div class="tip">{{ row.itemSpec }} {{ row.itemCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="安装地址" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.installAddress || '—' }}
              <div v-if="row.contact || row.phone" class="tip">{{ row.contact }} {{ row.phone }}</div>
            </template>
          </el-table-column>
          <el-table-column label="数量 出租/出库/归还" width="130" align="center">
            <template #default="{ row }">{{ row.qty }} / {{ row.outQty }} / {{ row.returnedQty }}</template>
          </el-table-column>
          <el-table-column label="开始 → 预计归还" width="190">
            <template #default="{ row }">
              {{ row.startDate }} → {{ row.expectedReturnDate }}
              <div v-if="row.actualReturnDate" class="tip">实际归还 {{ row.actualReturnDate }}</div>
            </template>
          </el-table-column>
          <el-table-column label="赔偿" width="90" align="right">
            <template #default="{ row }">{{ row.compensationTotal ? money(row.compensationTotal) : '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <template v-if="canOperate && (row.status === '已预订' || row.status === '出租中')">
                <el-button link type="primary" size="small" :disabled="!row.pendingOutQty" @click="openMove(row, 'out')">出库</el-button>
                <el-button link type="success" size="small" :disabled="!row.onSiteQty" @click="openMove(row, 'return')">归还</el-button>
                <el-button link type="primary" size="small" @click="openRentalForm({ rental: row })">编辑</el-button>
                <el-button link type="danger" size="small" :disabled="!row.pendingOutQty" @click="cancelRental(row)">{{ row.outQty ? '释放预订' : '取消' }}</el-button>
              </template>
              <span v-else class="tip">—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ 出入库记录 ============ -->
      <el-tab-pane label="出入库记录" name="movements">
        <div class="bar">
          <el-select v-model="moveFilters.type" placeholder="类型" clearable style="width:110px" @change="loadMovements">
            <el-option v-for="t in ['出库', '归还', '入库', '送修', '修好', '报废']" :key="t" :label="t" :value="t" />
          </el-select>
          <el-button @click="loadMovements">刷新</el-button>
        </div>
        <el-table :data="movements" v-loading="movementsLoading" size="small" border row-key="id">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="expand">
                <div v-if="row.damages.length">
                  <b>损坏与缺件</b>
                  <el-table :data="row.damages" size="small" border class="mt6">
                    <el-table-column prop="partName" label="检查项" width="110" />
                    <el-table-column label="损坏×单价" width="130"><template #default="{ row: d }">{{ d.damagedQty }} × {{ money(d.damagePrice) }}</template></el-table-column>
                    <el-table-column label="缺失×单价" width="130"><template #default="{ row: d }">{{ d.missingQty }} × {{ money(d.missingPrice) }}</template></el-table-column>
                    <el-table-column label="赔偿" width="110" align="right"><template #default="{ row: d }">{{ money(d.amount) }}</template></el-table-column>
                    <el-table-column label="处理" width="90"><template #default="{ row: d }"><el-tag :type="settleTag[d.settleStatus]" size="small">{{ d.settleStatus }}</el-tag></template></el-table-column>
                  </el-table>
                </div>
                <div class="mt6"><b>现场照片</b></div>
                <PhotoGallery class="mt6" biz-type="inv_movement" :biz-id="row.id" :editable="canOperate" @changed="(n: number) => (row.photoCount = n)" />
              </div>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="140"><template #default="{ row }">{{ fmtTime(row.opTime) }}</template></el-table-column>
          <el-table-column label="类型" width="70"><template #default="{ row }"><el-tag :type="moveTag[row.type]" size="small">{{ row.type }}</el-tag></template></el-table-column>
          <el-table-column label="资产" min-width="130">
            <template #default="{ row }"><el-link @click="openDetail(row.itemId)">{{ row.itemName }}</el-link> <span class="tip">{{ row.itemCode }}</span></template>
          </el-table-column>
          <el-table-column label="数量" width="130">
            <template #default="{ row }">
              {{ row.qty }}
              <div v-if="row.type === '归还'" class="tip">完好{{ row.goodQty }}/维修{{ row.repairQty }}/报废{{ row.scrapQty }}</div>
            </template>
          </el-table-column>
          <el-table-column label="客户 / 出租单" min-width="140">
            <template #default="{ row }">{{ row.customerName || '—' }}<div class="tip">{{ row.rentalNo }}</div></template>
          </el-table-column>
          <el-table-column prop="accessories" label="配件" min-width="130" show-overflow-tooltip />
          <el-table-column label="设备状况" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.conditionLevel || '' }} {{ row.conditionDesc || '' }}</template>
          </el-table-column>
          <el-table-column label="赔偿" width="90" align="right">
            <template #default="{ row }">{{ row.compensationTotal ? money(row.compensationTotal) : '—' }}</template>
          </el-table-column>
          <el-table-column label="照片" width="56" align="center"><template #default="{ row }">{{ row.photoCount || '—' }}</template></el-table-column>
          <el-table-column prop="operatorName" label="经办" width="80" />
        </el-table>
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
          <span>合计 <b class="bad">{{ money(damageSum) }}</b></span>
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

    <!-- 新建/编辑资产 -->
    <el-dialog v-model="itemDialog" :title="editingItemId ? '编辑资产' : '新建资产'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="编号"><el-input v-model="itemForm.code" maxlength="32" placeholder="留空自动生成 ZC+日期+序号" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="itemForm.name" maxlength="64" placeholder="如 播种墙 / 重型货架" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="itemForm.spec" maxlength="128" placeholder="如 120 格 / 2000×600×2400" /></el-form-item>
        <el-form-item label="类别">
          <el-select v-model="itemForm.category" clearable style="width:100%"><el-option v-for="c in INV_CATEGORIES" :key="c" :label="c" :value="c" /></el-select>
        </el-form-item>
        <el-form-item label="单位">
          <el-select v-model="itemForm.unit" style="width:100%" filterable allow-create><el-option v-for="u in INV_UNITS" :key="u" :label="u" :value="u" /></el-select>
        </el-form-item>
        <el-form-item v-if="!editingItemId" label="数量">
          <el-input-number v-model="itemForm.initialQty" :min="0" :precision="0" />
          <span class="tip ml8">记入库存；之后增加用「状态调整 › 入库」</span>
        </el-form-item>
        <el-form-item label="存放位置"><el-input v-model="itemForm.location" maxlength="128" placeholder="如 嘉兴仓 A 区 3 排" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="itemForm.remark" maxlength="255" /></el-form-item>
        <div v-if="!editingItemId" class="tip">照片在保存后的详情里上传。</div>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog = false">取消</el-button>
        <el-button type="primary" :loading="itemSaving" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>

    <!-- 状态调整 -->
    <el-dialog v-model="adjustDialog" title="状态调整" width="460px">
      <div v-if="adjustItem" class="tip mb8">
        {{ adjustItem.name }} {{ adjustItem.code }} · 库存 {{ adjustItem.stockQty }} / 维修中 {{ adjustItem.repairQty }} / 已报废 {{ adjustItem.scrappedQty }}
      </div>
      <el-form label-width="80px">
        <el-form-item label="类型">
          <el-radio-group v-model="adjustForm.type">
            <el-radio-button v-for="t in adjustTypes" :key="t.value" :value="t.value">{{ t.value }}</el-radio-button>
          </el-radio-group>
          <div class="tip w100">{{ adjustTypes.find((t) => t.value === adjustForm.type)?.tip }}</div>
        </el-form-item>
        <el-form-item v-if="adjustForm.type === '报废'" label="报废来源">
          <el-radio-group v-model="adjustForm.fromStatus">
            <el-radio value="库存">库存</el-radio>
            <el-radio value="维修中">维修中</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="数量"><el-input-number v-model="adjustForm.qty" :min="1" :max="Math.max(1, adjustMax)" :precision="0" /><span class="tip ml8">最多 {{ adjustMax }}</span></el-form-item>
        <el-form-item label="状况说明"><el-input v-model="adjustForm.conditionDesc" maxlength="500" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="adjustForm.remark" maxlength="255" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustDialog = false">取消</el-button>
        <el-button type="primary" :loading="adjustSaving" :disabled="adjustMax <= 0" @click="saveAdjust">确认</el-button>
      </template>
    </el-dialog>

    <!-- 新建/编辑出租 -->
    <el-dialog v-model="rentalDialog" :title="editingRental ? `编辑出租单 ${editingRental.rentalNo}` : '新建出租（预订）'" width="580px">
      <el-form label-width="100px">
        <el-form-item label="资产" required>
          <el-select v-model="rentalForm.itemId" filterable style="width:100%" :disabled="!!editingRental" placeholder="选择资产">
            <el-option v-for="it in allItems" :key="it.id" :label="`${it.name} ${it.spec || ''} · ${it.code}（库存 ${it.stockQty}）`" :value="it.id" :disabled="!editingRental && !it.stockQty" />
          </el-select>
        </el-form-item>
        <el-form-item label="客户" required>
          <el-radio-group v-model="rentalForm.customerMode" size="small" class="mb8">
            <el-radio-button value="crm">从客户 CRM 选</el-radio-button>
            <el-radio-button value="text">手填</el-radio-button>
          </el-radio-group>
          <el-select v-if="rentalForm.customerMode === 'crm'" v-model="rentalForm.customerId" filterable clearable style="width:100%" placeholder="搜索客户">
            <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-input v-else v-model="rentalForm.customerName" maxlength="128" placeholder="客户名称" />
        </el-form-item>
        <el-form-item v-if="rentalForm.customerMode === 'crm'" label="关联合同">
          <el-select v-model="rentalForm.contractId" clearable style="width:100%" :placeholder="rentalForm.customerId ? (contracts.length ? '选填，用于合同到期提醒' : '该客户暂无合同') : '先选客户'">
            <el-option v-for="c in contracts" :key="c.id" :label="`${c.no} · ${c.status}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="安装地址"><el-input v-model="rentalForm.installAddress" maxlength="255" /></el-form-item>
        <el-form-item label="现场联系人">
          <div class="row2">
            <el-input v-model="rentalForm.contact" maxlength="64" placeholder="联系人" />
            <el-input v-model="rentalForm.phone" maxlength="32" placeholder="电话" />
          </div>
        </el-form-item>
        <el-form-item label="出租数量" required>
          <el-input-number v-model="rentalForm.qty" :min="Math.max(1, editingRental?.outQty || 0)" :max="Math.max(1, rentalMaxQty)" :precision="0" />
          <span class="tip ml8">最多 {{ rentalMaxQty }} {{ rentalItem?.unit || '' }}<template v-if="editingRental?.outQty">，不少于已出库 {{ editingRental.outQty }}</template></span>
        </el-form-item>
        <el-form-item label="开始/预计归还" required>
          <el-date-picker v-model="rentalForm.dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始时间" end-placeholder="预计归还时间" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="rentalForm.remark" maxlength="255" /></el-form-item>
        <div v-if="!editingRental" class="tip">保存后数量从「库存」转为「已预订」；实际发货时再登记出库。</div>
      </el-form>
      <template #footer>
        <el-button @click="rentalDialog = false">取消</el-button>
        <el-button type="primary" :loading="rentalSaving" @click="saveRental">保存</el-button>
      </template>
    </el-dialog>

    <!-- 出库/归还 -->
    <el-dialog v-model="moveDialog" :title="moveMode === 'out' ? '出库登记' : '归还登记'" width="620px" destroy-on-close>
      <MovementForm v-if="moveRental" :mode="moveMode" :rental="moveRental" :prices="prices" @done="onMoveDone" @cancel="moveDialog = false" />
    </el-dialog>

    <!-- 二维码标签 -->
    <el-dialog v-model="labelDialog" title="二维码标签" width="480px">
      <div class="label-preview">
        <div class="lp-co">{{ company.companyName }}</div>
        <div class="lp-body">
          <img :src="labelPreview" alt="" />
          <div>
            <div class="lp-name">{{ labelItems[0]?.name }}</div>
            <div v-if="labelItems[0]?.spec">规格：{{ labelItems[0]?.spec }}</div>
            <div>编号：{{ labelItems[0]?.code }}</div>
            <div v-if="company.phone">电话：{{ company.phone }}</div>
          </div>
        </div>
        <div v-if="company.notice" class="tip">{{ company.notice }}</div>
      </div>
      <el-form label-width="120px" class="mt12">
        <el-form-item label="资产">{{ labelItems.length === 1 ? labelItems[0].code : `已选 ${labelItems.length} 批` }}</el-form-item>
        <el-form-item label="每批打印张数">
          <el-input-number v-model="labelCopies" :min="1" :max="500" :precision="0" />
        </el-form-item>
        <div class="tip">每套播种墙、每组货架各贴一张；同一批的标签二维码相同，扫码后选择出租单出库/归还。企业信息在「设置」里修改。</div>
      </el-form>
      <template #footer>
        <el-button v-if="labelItems.length === 1" @click="copyScanLink(labelItems[0])">复制扫码链接</el-button>
        <el-button type="primary" @click="doPrint">打印标签</el-button>
      </template>
    </el-dialog>

    <!-- 资产详情 -->
    <el-drawer v-model="detailVisible" size="760px" :title="detail ? `${detail.item.name} · ${detail.item.code}` : '资产详情'">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="编号">{{ detail.item.code }}</el-descriptions-item>
            <el-descriptions-item label="名称">{{ detail.item.name }}</el-descriptions-item>
            <el-descriptions-item label="规格">{{ detail.item.spec || '—' }}</el-descriptions-item>
            <el-descriptions-item label="类别">{{ detail.item.category || '—' }}</el-descriptions-item>
            <el-descriptions-item label="存放位置">{{ detail.item.location || '—' }}</el-descriptions-item>
            <el-descriptions-item label="总数量">{{ detail.item.totalQty }} {{ detail.item.unit }}</el-descriptions-item>
            <el-descriptions-item label="备注" :span="2">{{ detail.item.remark || '—' }}</el-descriptions-item>
          </el-descriptions>
          <div class="qty-tags mt12">
            <el-tag type="success">库存 {{ detail.item.stockQty }}</el-tag>
            <el-tag type="warning">已预订 {{ detail.item.reservedQty }}</el-tag>
            <el-tag>出租中 {{ detail.item.rentedQty }}</el-tag>
            <el-tag type="warning">维修中 {{ detail.item.repairQty }}</el-tag>
            <el-tag type="info">已报废 {{ detail.item.scrappedQty }}</el-tag>
          </div>
          <div class="bar mt12">
            <el-button v-if="canOperate" size="small" type="primary" :disabled="!detail.item.stockQty" @click="openRentalForm({ item: detail.item })">出租</el-button>
            <el-button v-if="canOperate" size="small" @click="openAdjust(detail.item)">状态调整</el-button>
            <el-button size="small" @click="openLabels([detail.item])">二维码标签</el-button>
            <el-button v-if="canOperate" size="small" @click="openItemForm(detail.item)">编辑</el-button>
          </div>

          <h4>照片</h4>
          <PhotoGallery biz-type="inv_item" :biz-id="detail.item.id" :editable="canOperate" />

          <h4>出租单</h4>
          <el-table :data="detail.rentals" size="small" border>
            <el-table-column prop="rentalNo" label="单号" width="125" />
            <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag :type="rentalTag[row.status]" size="small">{{ row.status }}</el-tag></template></el-table-column>
            <el-table-column prop="customerName" label="客户" min-width="110" show-overflow-tooltip />
            <el-table-column label="出租/出库/归还" width="110" align="center"><template #default="{ row }">{{ row.qty }}/{{ row.outQty }}/{{ row.returnedQty }}</template></el-table-column>
            <el-table-column label="预计归还" width="100"><template #default="{ row }">{{ row.expectedReturnDate }}<div v-if="row.overdueDays" class="bad">逾期{{ row.overdueDays }}天</div></template></el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <template v-if="canOperate && (row.status === '已预订' || row.status === '出租中')">
                  <el-button link type="primary" size="small" :disabled="!row.pendingOutQty" @click="openMove(row, 'out')">出库</el-button>
                  <el-button link type="success" size="small" :disabled="!row.onSiteQty" @click="openMove(row, 'return')">归还</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>

          <h4>出入库记录</h4>
          <el-table :data="detail.movements" size="small" border>
            <el-table-column label="时间" width="130"><template #default="{ row }">{{ fmtTime(row.opTime) }}</template></el-table-column>
            <el-table-column label="类型" width="64"><template #default="{ row }"><el-tag :type="moveTag[row.type]" size="small">{{ row.type }}</el-tag></template></el-table-column>
            <el-table-column prop="qty" label="数量" width="56" align="right" />
            <el-table-column prop="customerName" label="客户" min-width="100" show-overflow-tooltip />
            <el-table-column label="状况" min-width="100" show-overflow-tooltip><template #default="{ row }">{{ row.conditionLevel }} {{ row.conditionDesc }}</template></el-table-column>
            <el-table-column label="赔偿" width="90" align="right"><template #default="{ row }">{{ row.compensationTotal ? money(row.compensationTotal) : '—' }}</template></el-table-column>
            <el-table-column prop="operatorName" label="经办" width="70" />
          </el-table>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.inv { padding-bottom: 24px; }
.bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.grow { flex: 1; }
.tip { color: #909399; font-size: 12px; line-height: 1.6; }
.bad { color: #e0533d; font-size: 12px; }
.mt6 { margin-top: 6px; }
.mt12 { margin-top: 12px; }
.mb8 { margin-bottom: 8px; }
.ml8 { margin-left: 8px; }
.w100 { width: 100%; }
.stats { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; }
.stat { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 12px 14px; }
.stat .v { font-size: 22px; font-weight: 700; color: #303133; }
.stat .l { font-size: 12px; color: #909399; margin-top: 2px; }
.stat.ok .v { color: #2f9e44; }
.stat.warn .v { color: #e6a23c; }
.stat.blue .v { color: #409eff; }
.stat.bad .v { color: #e0533d; }
.stat.grey .v { color: #909399; }
.remind { display: flex; align-items: center; gap: 10px; padding: 7px 4px; border-bottom: 1px dashed #ebeef5; cursor: pointer; flex-wrap: wrap; }
.remind:hover { background: #f5f7fa; }
.rt { font-size: 13px; }
.expand { padding: 4px 16px; }
.row2 { display: flex; gap: 8px; width: 100%; }
.qty-tags { display: flex; gap: 8px; flex-wrap: wrap; }
h4 { margin: 18px 0 8px; }
.label-preview { border: 1px solid #333; border-radius: 6px; padding: 10px; max-width: 360px; }
.lp-co { font-weight: 700; border-bottom: 1px solid #333; padding-bottom: 4px; margin-bottom: 6px; }
.lp-body { display: flex; gap: 10px; align-items: center; font-size: 12px; line-height: 1.6; }
.lp-body img { width: 110px; height: 110px; }
.lp-name { font-size: 15px; font-weight: 700; }
:deep(.row-hl) { background: #ecf5ff !important; }
:deep(.row-overdue) { background: #fef0f0; }
</style>
