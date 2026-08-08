<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchBills, matchBill, batchMatch, reverseBill, refundBill, runGen, fetchCashCheck,
  fetchOverdue, overdueExtend, overduePenalty, overdueLock, overdueRepossess, overdueRepay, runScan,
  type BillItem, type CashCheck, type OverdueItem,
} from '@/api/rent'

const tab = ref<'bills' | 'overdue'>('bills')

function money(v?: number | null) {
  if (v === null || v === undefined) return '—'
  return v >= 10000 || v <= -10000 ? (v / 10000).toFixed(2) + '万' : '¥' + v.toLocaleString()
}
const statusType: Record<string, string> = { 待收: 'info', 已核销: 'success', 逾期: 'danger', 红冲: 'warning' }
const kindType: Record<string, string> = { 正常: '', 红冲: 'warning', 退款: 'info', 罚息: 'danger' }
const stepType: Record<string, string> = { 延期: 'info', 罚息: 'warning', 锁机: 'danger', 收回: 'danger', 关闭: 'success' }

// ---- 收租单列表 ----
const filters = reactive<{ status: string; billKind: string; contractId?: number }>({ status: '', billKind: '' })
const statuses = ['待收', '已核销', '逾期', '红冲']
const kinds = ['正常', '红冲', '退款', '罚息']
const bills = ref<BillItem[]>([])
const total = ref(0)
const loading = ref(false)
const selected = ref<BillItem[]>([])
async function loadBills() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 100 }
    if (filters.status) params.status = filters.status
    if (filters.billKind) params.billKind = filters.billKind
    if (filters.contractId) params.contractId = filters.contractId
    const res = await fetchBills(params)
    bills.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}
function onSelect(rows: BillItem[]) { selected.value = rows }
const selectableCount = computed(() => selected.value.filter((b) => b.status === '待收' || b.status === '逾期').length)
const selectedSum = computed(() =>
  selected.value.filter((b) => b.status === '待收' || b.status === '逾期').reduce((s, b) => s + (b.amount || 0), 0))

// ---- 生成收租单 ----
async function doGen() {
  const r = await runGen()
  ElMessage.success(`生成 ${r.generated} 张(窗口<= ${r.horizon})· 跳过 ${r.skipped}`)
  loadBills(); loadCashCheck()
}

// ---- 核销 ----
async function doMatch(row: BillItem) {
  await ElMessageBox.confirm(`确认到账核销 ${row.billNo} · 应收 ${money(row.amount)}?`, '到账核销', { type: 'success' })
  await matchBill(row.id)
  ElMessage.success('已核销 · 写简化收入流水(凭证 M3 接入)')
  loadBills(); loadCashCheck()
}
async function doBatchMatch() {
  const targets = selected.value.filter((b) => b.status === '待收' || b.status === '逾期')
  if (!targets.length) { ElMessage.warning('请勾选待收/逾期收租单'); return }
  await ElMessageBox.confirm(`批量核销 ${targets.length} 张 · 合计 ${money(selectedSum.value)}(按各单应收全额)?`, '批量核销', { type: 'success' })
  await batchMatch(targets.map((b) => b.id))
  ElMessage.success(`已批量核销 ${targets.length} 张`)
  loadBills(); loadCashCheck()
}

// ---- 红冲 / 退款 ----
async function doReverse(row: BillItem) {
  const { value } = await ElMessageBox.prompt(`红冲 ${row.billNo}(P0-F:原子+幂等+锁账守卫)· 原因`, '红冲', { inputType: 'textarea' })
  const impact = await reverseBill(row.id, { reason: value })
  ElMessageBox.alert(impact.items.map((i) => '· ' + i).join('<br/>'), `红冲影响清单(记账期 ${impact.accountPeriod})`, { dangerouslyUseHTMLString: true })
  loadBills(); loadCashCheck()
}
async function doRefund(row: BillItem) {
  const { value } = await ElMessageBox.prompt(`退款 ${row.billNo} · 退款金额(缺省=已收 ${money(row.receivedAmount)})`, '退款', { inputValue: String(row.receivedAmount || row.amount) })
  await refundBill(row.id, { amount: Number(value), reason: '退款' })
  ElMessage.success('已生成退款单')
  loadBills(); loadCashCheck()
}

// ---- 钱该动没动稽核 ----
const cashCheck = ref<CashCheck | null>(null)
async function loadCashCheck() { cashCheck.value = await fetchCashCheck() }

// ---- 逾期案 ----
const overdue = ref<OverdueItem[]>([])
const overdueLoading = ref(false)
const overdueStatus = ref('')
async function loadOverdue() {
  overdueLoading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 100 }
    if (overdueStatus.value) params.status = overdueStatus.value
    const res = await fetchOverdue(params)
    overdue.value = res.records
  } finally {
    overdueLoading.value = false
  }
}
async function doScan() {
  const r = await runScan()
  ElMessage.success(`逾期检测:标逾期 ${r.marked} 单 · 开案 ${r.opened} 个`)
  loadOverdue(); loadBills(); loadCashCheck()
}
async function askOwnerDeadline(title: string): Promise<{ owner: string; deadline?: string }> {
  const { value } = await ElMessageBox.prompt(`${title} · 裁决人(必填)`, title, { inputValue: '财务' })
  return { owner: value }
}
async function stepExtend(c: OverdueItem) {
  const { owner } = await askOwnerDeadline('延期')
  const { value } = await ElMessageBox.prompt('展期天数', '延期', { inputValue: '10', inputPattern: /^\d+$/ })
  await overdueExtend(c.id, { owner, days: Number(value), reason: '协商展期' })
  ElMessage.success('已延期'); loadOverdue()
}
async function stepPenalty(c: OverdueItem) {
  const { owner } = await askOwnerDeadline('罚息')
  await overduePenalty(c.id, { owner })
  ElMessage.success('已计罚息单'); loadOverdue(); loadBills()
}
async function stepLock(c: OverdueItem) {
  const { owner } = await askOwnerDeadline('锁机')
  await overdueLock(c.id, { owner, reason: '物权主张' })
  ElMessage.success('已锁机(物权在我方)'); loadOverdue()
}
async function stepRepossess(c: OverdueItem) {
  await ElMessageBox.confirm('收回:生成收回单 + 设备转「收回待处置」+ 结案?', '收回', { type: 'warning' })
  const { owner } = await askOwnerDeadline('收回')
  await overdueRepossess(c.id, { owner, reason: '长期逾期收回' })
  ElMessage.success('已收回 · 设备转收回待处置'); loadOverdue(); loadBills()
}
async function stepRepay(c: OverdueItem) {
  await ElMessageBox.confirm(`还款恢复:核销逾期单 ${c.billNo} 并关闭本案?`, '还款恢复', { type: 'success' })
  const r = await overdueRepay(c.id)
  ElMessage.success(`已关案(${r.caseStatus})· 承租关系${r.assetsRestored ? '存续' : '已收回'}`)
  loadOverdue(); loadBills(); loadCashCheck()
}

onMounted(() => { loadBills(); loadCashCheck(); loadOverdue() })
</script>

<template>
  <div class="rent-page">
    <!-- 钱该动没动稽核带 -->
    <el-card shadow="never" class="audit-bar" v-if="cashCheck">
      <span class="audit-title">💰 钱该动没动稽核</span>
      <el-tag :type="cashCheck.billNotGenerated ? 'danger' : 'success'" size="small">到期未生成单 {{ cashCheck.billNotGenerated }}</el-tag>
      <el-tag :type="cashCheck.receivedNotMatched ? 'danger' : 'success'" size="small">已到账未核销 {{ cashCheck.receivedNotMatched }}</el-tag>
      <el-tag :type="cashCheck.matchedNoVoucher ? 'warning' : 'success'" size="small">已核销缺凭证 {{ cashCheck.matchedNoVoucher }}</el-tag>
      <el-tag :type="cashCheck.allClear ? 'success' : 'info'" size="small">{{ cashCheck.allClear ? '✓ 全清' : '有待办' }}</el-tag>
      <el-popover v-if="!cashCheck.allClear" trigger="hover" width="420" placement="bottom">
        <template #reference><el-button link type="primary" size="small">明细</el-button></template>
        <div class="audit-detail">
          <div v-if="cashCheck.billNotGeneratedDetail.length"><b>到期未生成单</b><div v-for="(d,i) in cashCheck.billNotGeneratedDetail" :key="'a'+i">· {{ d }}</div></div>
          <div v-if="cashCheck.receivedNotMatchedDetail.length"><b>已到账未核销</b><div v-for="(d,i) in cashCheck.receivedNotMatchedDetail" :key="'b'+i">· {{ d }}</div></div>
          <div v-if="cashCheck.matchedNoVoucherDetail.length"><b>已核销缺凭证(待 M3)</b><div v-for="(d,i) in cashCheck.matchedNoVoucherDetail.slice(0,8)" :key="'c'+i">· {{ d }}</div></div>
        </div>
      </el-popover>
    </el-card>

    <el-tabs v-model="tab">
      <!-- ============ 收租单 · 批量核销 ============ -->
      <el-tab-pane label="收租单 · 批量核销" name="bills">
        <div class="toolbar">
          <el-select v-model="filters.status" placeholder="状态" clearable size="small" style="width:110px" @change="loadBills">
            <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
          </el-select>
          <el-select v-model="filters.billKind" placeholder="性质" clearable size="small" style="width:110px" @change="loadBills">
            <el-option v-for="k in kinds" :key="k" :label="k" :value="k" />
          </el-select>
          <el-button size="small" @click="loadBills">查询</el-button>
          <el-button size="small" type="primary" @click="doGen">⏱ 生成到期收租单(T-3)</el-button>
          <el-button size="small" type="success" :disabled="!selectableCount" @click="doBatchMatch">
            ✓ 批量核销{{ selectableCount ? `(${selectableCount}张 · ${money(selectedSum)})` : '' }}
          </el-button>
          <span class="total">共 {{ total }} 张</span>
        </div>

        <el-table :data="bills" v-loading="loading" size="small" border @selection-change="onSelect">
          <el-table-column type="selection" width="42" :selectable="(r:any) => r.status === '待收' || r.status === '逾期'" />
          <el-table-column prop="billNo" label="收租单号" min-width="180" show-overflow-tooltip />
          <el-table-column prop="contractNo" label="合同" width="140" show-overflow-tooltip />
          <el-table-column prop="customerName" label="客户" width="110" show-overflow-tooltip />
          <el-table-column prop="periodNo" label="期" width="55" />
          <el-table-column prop="dueDate" label="到期日" width="110" />
          <el-table-column label="应收" width="100"><template #default="{ row }"><span :class="{ neg: row.amount < 0 }">{{ money(row.amount) }}</span></template></el-table-column>
          <el-table-column label="已收" width="90"><template #default="{ row }">{{ money(row.receivedAmount) }}</template></el-table-column>
          <el-table-column label="状态" width="82"><template #default="{ row }">
            <el-tag :type="statusType[row.status] || 'info'" size="small">{{ row.status }}</el-tag>
            <el-tag v-if="row.overdue" type="danger" size="small" effect="plain" style="margin-left:2px">逾{{ row.overdueDays }}d</el-tag>
          </template></el-table-column>
          <el-table-column label="性质" width="70"><template #default="{ row }"><el-tag :type="kindType[row.billKind]" size="small" effect="plain">{{ row.billKind }}</el-tag></template></el-table-column>
          <el-table-column label="凭证" width="60"><template #default="{ row }">
            <el-tag v-if="row.status === '已核销' && !row.voucherId" type="warning" size="small" effect="plain">缺</el-tag>
            <span v-else>—</span>
          </template></el-table-column>
          <el-table-column label="操作" width="180" fixed="right"><template #default="{ row }">
            <el-button v-if="row.status === '待收' || row.status === '逾期'" link type="success" size="small" @click="doMatch(row)">核销</el-button>
            <el-button v-if="row.status !== '红冲' && row.billKind !== '红冲' && row.billKind !== '退款'" link type="warning" size="small" @click="doReverse(row)">红冲</el-button>
            <el-button v-if="row.status === '已核销' && row.billKind === '正常'" link type="info" size="small" @click="doRefund(row)">退款</el-button>
          </template></el-table-column>
        </el-table>
        <p class="tip">勾选待收/逾期单 → 批量核销;红冲走 P0-F(原子事务 + reverses_id 唯一幂等键 + 锁账守卫),返回影响清单。收入为简化流水,凭证待 M3。</p>
      </el-tab-pane>

      <!-- ============ 逾期 · 三步走 ============ -->
      <el-tab-pane label="逾期案 · 三步走" name="overdue">
        <div class="toolbar">
          <el-select v-model="overdueStatus" placeholder="案态" clearable size="small" style="width:110px" @change="loadOverdue">
            <el-option label="开启" value="开启" /><el-option label="关闭" value="关闭" />
          </el-select>
          <el-button size="small" @click="loadOverdue">查询</el-button>
          <el-button size="small" type="danger" @click="doScan">🔍 逾期检测(扫过期未付·自动开案)</el-button>
        </div>
        <el-table :data="overdue" v-loading="overdueLoading" size="small" border>
          <el-table-column prop="billNo" label="逾期收租单" min-width="170" show-overflow-tooltip />
          <el-table-column prop="contractNo" label="合同" width="140" show-overflow-tooltip />
          <el-table-column prop="customerName" label="客户" width="100" show-overflow-tooltip />
          <el-table-column label="当前步" width="80"><template #default="{ row }"><el-tag :type="stepType[row.step]" size="small">{{ row.step }}</el-tag></template></el-table-column>
          <el-table-column label="案态" width="70"><template #default="{ row }"><el-tag :type="row.status === '开启' ? 'danger' : 'success'" size="small">{{ row.status }}</el-tag></template></el-table-column>
          <el-table-column label="累计罚息" width="90"><template #default="{ row }">{{ money(row.penaltyAmount) }}</template></el-table-column>
          <el-table-column prop="owner" label="裁决人" width="80" />
          <el-table-column prop="deadline" label="期限" width="110" />
          <el-table-column prop="nextAction" label="下一步" min-width="150" show-overflow-tooltip />
          <el-table-column label="三步走处置" width="270" fixed="right"><template #default="{ row }">
            <template v-if="row.status === '开启'">
              <el-button link type="info" size="small" @click="stepExtend(row)">延期</el-button>
              <el-button link type="warning" size="small" @click="stepPenalty(row)">罚息</el-button>
              <el-button link type="danger" size="small" @click="stepLock(row)">锁机</el-button>
              <el-button link type="danger" size="small" @click="stepRepossess(row)">收回</el-button>
              <el-button link type="success" size="small" @click="stepRepay(row)">还款恢复</el-button>
            </template>
            <span v-else class="muted">已结案</span>
          </template></el-table-column>
        </el-table>
        <p class="tip">内外一视同仁,物权在我方。延期→罚息(按 rule 罚息率计罚息单)→锁机(物权主张)→收回(生成收回单+设备转收回待处置)。还款到账即关案恢复;每案必裁决人 + 期限。</p>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.rent-page { padding: 4px; }
.audit-bar { margin-bottom: 10px; }
.audit-bar :deep(.el-card__body) { padding: 8px 12px; display: flex; align-items: center; gap: 10px; }
.audit-title { font-weight: 600; }
.audit-detail { font-size: 12px; line-height: 1.7; }
.audit-detail b { color: #e6a23c; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.toolbar .total { margin-left: auto; font-size: 12px; color: #666; }
.tip { font-size: 12px; color: #999; margin-top: 8px; }
.neg { color: #e6a23c; }
.muted { color: #bbb; font-size: 12px; }
</style>
