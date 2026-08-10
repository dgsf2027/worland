<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchVouchers, fetchVoucherDetail, reverseVoucher, backfillRentIncome,
  fetchTaxThreshold, runDepreciation,
  type VoucherItem, type VoucherDetail, type TaxThreshold,
} from '@/api/voucher'

function money(v?: number | null) {
  if (v === null || v === undefined) return '—'
  return v >= 10000 || v <= -10000 ? (v / 10000).toFixed(2) + '万' : '¥' + v.toLocaleString()
}
const bookLabel: Record<string, string> = { tax: '税务账(分期收款销售)', ops: '经营账(三层回报)' }
const bookTag: Record<string, string> = { tax: 'warning', ops: '' }
const srcLabel: Record<string, string> = {
  rent_bill: '收租', purchase_in: '采购应付', depreciation: '折旧', transfer: '转让残值', manual: '手工',
}
const entryTag: Record<string, string> = { revenue: 'success', cost: 'info', payable: 'warning', other: 'info' }
const levelTag: Record<string, string> = { 正常: 'success', 预警: 'warning', 超限: 'danger' }

// ---- 500万营收红线(税务账) ----
const threshold = ref<TaxThreshold | null>(null)
async function loadThreshold() { threshold.value = await fetchTaxThreshold() }
const usedPct = computed(() => threshold.value ? Math.min(100, Math.round(threshold.value.usedRatio * 10000) / 100) : 0)
const barStatus = computed(() => {
  const lv = threshold.value?.level
  return lv === '超限' ? 'exception' : lv === '预警' ? 'warning' : 'success'
})

// ---- 凭证列表 ----
const filters = reactive<{ book: string; sourceDocType: string; period: string; isReversal?: boolean }>(
  { book: '', sourceDocType: '', period: '' })
const books = ['tax', 'ops']
const sources = ['rent_bill', 'purchase_in', 'depreciation', 'transfer', 'manual']
const vouchers = ref<VoucherItem[]>([])
const total = ref(0)
const loading = ref(false)
async function loadVouchers() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 200 }
    if (filters.book) params.book = filters.book
    if (filters.sourceDocType) params.sourceDocType = filters.sourceDocType
    if (filters.period) params.period = filters.period
    if (filters.isReversal !== undefined && filters.isReversal !== null) params.isReversal = filters.isReversal
    const res = await fetchVouchers(params)
    vouchers.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

// ---- 详情(双账口径对照 + 分录借贷) ----
const detailVisible = ref(false)
const detail = ref<VoucherDetail | null>(null)
async function openDetail(id: number) {
  detail.value = await fetchVoucherDetail(id)
  detailVisible.value = true
}

// ---- 通用凭证红冲(P0-F) ----
async function doReverse(row: VoucherItem) {
  const { value } = await ElMessageBox.prompt(
    `红冲凭证 ${row.voucherNo}(P0-F:单事务原子 + reverses_id 唯一幂等 + 锁账守卫)· 原因`,
    '通用凭证红冲', { inputType: 'textarea', inputValue: '错账冲销' })
  const impact = await reverseVoucher(row.id, { reason: value })
  await ElMessageBox.alert(
    impact.items.map((i) => '· ' + i).join('<br/>'),
    `红冲影响清单(${impact.reversalVoucherNo} · ${row.book} · 期 ${impact.period})`,
    { dangerouslyUseHTMLString: true })
  loadVouchers(); loadThreshold()
}

// ---- 回填收入凭证 / 折旧计提 ----
async function doBackfill() {
  await ElMessageBox.confirm('为历史「已核销缺凭证」单补生成双账收入凭证(消稽核 matchedNoVoucher)?', '回填收入凭证', { type: 'warning' })
  const r = await backfillRentIncome()
  ElMessage.success(`扫描 ${r.scanned} 单 · 入账 ${r.posted} 单 · 生成凭证 ${r.voucherCount} 张`)
  loadVouchers(); loadThreshold()
}
async function doDepreciation() {
  await ElMessageBox.confirm('触发本月折旧计提(经营口径·逐台生成折旧行 + ops 折旧凭证·book_value 递减)?', '月度折旧计提', { type: 'warning' })
  const r = await runDepreciation()
  ElMessage.success(`期 ${r.period} · 计提 ${r.linesGenerated} 台 · 折旧总额 ${money(r.totalDepr)} · ops凭证 ${r.vouchersPosted}`)
  loadVouchers()
}

onMounted(() => { loadThreshold(); loadVouchers() })
</script>

<template>
  <div class="voucher-page">
    <!-- ============ 500万营收红线(税务账) ============ -->
    <el-card shadow="never" class="threshold-bar" v-if="threshold">
      <div class="th-head">
        <span class="th-title">🚨 500万营收红线</span>
        <el-tag :type="levelTag[threshold.level]" size="small" effect="dark">{{ threshold.level }}</el-tag>
        <span class="th-note">取<b>税务账</b>(ops≠tax·折旧只落经营账) · {{ threshold.year }} 自然年</span>
      </div>
      <el-progress :percentage="usedPct" :status="barStatus" :stroke-width="16" :text-inside="true" />
      <div class="th-nums">
        <span>已确认(税务)<b>{{ money(threshold.currentRevenue) }}</b></span>
        <span>剩余额度 <b>{{ money(threshold.remaining) }}</b></span>
        <span>阈值 {{ money(threshold.threshold) }}</span>
        <span>预警线 {{ Math.round(threshold.warnRatio * 100) }}%</span>
        <span class="ops">经营账对照 {{ money(threshold.opsRevenue) }}</span>
      </div>
    </el-card>

    <!-- ============ 凭证查询 ============ -->
    <div class="toolbar">
      <el-select v-model="filters.book" placeholder="账套" clearable size="small" style="width:130px" @change="loadVouchers">
        <el-option v-for="b in books" :key="b" :label="bookLabel[b]" :value="b" />
      </el-select>
      <el-select v-model="filters.sourceDocType" placeholder="来源单据" clearable size="small" style="width:120px" @change="loadVouchers">
        <el-option v-for="s in sources" :key="s" :label="srcLabel[s]" :value="s" />
      </el-select>
      <el-input v-model="filters.period" placeholder="记账期 YYYY-MM" clearable size="small" style="width:140px" />
      <el-select v-model="filters.isReversal" placeholder="是否红冲" clearable size="small" style="width:110px" @change="loadVouchers">
        <el-option label="原始凭证" :value="false" /><el-option label="红冲凭证" :value="true" />
      </el-select>
      <el-button size="small" @click="loadVouchers">查询</el-button>
      <el-button size="small" type="primary" @click="doBackfill">📥 回填收入凭证</el-button>
      <el-button size="small" type="warning" @click="doDepreciation">📉 月度折旧计提</el-button>
      <span class="total">共 {{ total }} 张</span>
    </div>

    <el-table :data="vouchers" v-loading="loading" size="small" border>
      <el-table-column prop="voucherNo" label="凭证号" min-width="230" show-overflow-tooltip />
      <el-table-column label="账套" width="90"><template #default="{ row }">
        <el-tag :type="bookTag[row.book]" size="small">{{ row.book === 'tax' ? '税务账' : '经营账' }}</el-tag>
      </template></el-table-column>
      <el-table-column label="来源" width="90"><template #default="{ row }">{{ srcLabel[row.sourceDocType] || row.sourceDocType }}</template></el-table-column>
      <el-table-column label="性质" width="70"><template #default="{ row }"><el-tag :type="entryTag[row.entryType]" size="small" effect="plain">{{ row.entryType }}</el-tag></template></el-table-column>
      <el-table-column prop="period" label="记账期" width="90" />
      <el-table-column label="金额" width="100"><template #default="{ row }"><span :class="{ neg: row.isReversal }">{{ money(row.totalAmount) }}</span></template></el-table-column>
      <el-table-column label="平衡" width="72"><template #default="{ row }">
        <el-tag :type="row.balanced ? 'success' : 'danger'" size="small" effect="plain">{{ row.balanced ? '借=贷' : '不平' }}</el-tag>
      </template></el-table-column>
      <el-table-column label="类型" width="72"><template #default="{ row }">
        <el-tag v-if="row.isReversal" type="warning" size="small">红冲</el-tag>
        <span v-else class="muted">原始</span>
      </template></el-table-column>
      <el-table-column prop="summary" label="摘要" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作" width="130" fixed="right"><template #default="{ row }">
        <el-button link type="primary" size="small" @click="openDetail(row.id)">详情</el-button>
        <el-button v-if="!row.isReversal" link type="warning" size="small" @click="doReverse(row)">红冲</el-button>
      </template></el-table-column>
    </el-table>
    <p class="tip">
      一业务事件按账套拆两张凭证(税务/经营)·每张借贷平衡 Σ借=Σ贷。收租核销自动生成双账收入凭证;折旧只落经营账(ops≠tax)。
      通用红冲走 P0-F:单事务原子 + reverses_id 唯一幂等键 + 锁账守卫,ledger_book 同步写负额冲销,营收红线联动回退。
    </p>

    <!-- ============ 凭证详情(分录借贷 + 双账口径对照) ============ -->
    <el-dialog v-model="detailVisible" title="凭证详情" width="720px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="凭证号">{{ detail.voucher.voucherNo }}</el-descriptions-item>
          <el-descriptions-item label="账套">
            <el-tag :type="bookTag[detail.voucher.book]" size="small">{{ bookLabel[detail.voucher.book] }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="来源">{{ srcLabel[detail.voucher.sourceDocType] || detail.voucher.sourceDocType }} #{{ detail.voucher.sourceDocId }}</el-descriptions-item>
          <el-descriptions-item label="记账期">{{ detail.voucher.period }}</el-descriptions-item>
          <el-descriptions-item label="摘要" :span="2">{{ detail.voucher.summary }}</el-descriptions-item>
        </el-descriptions>

        <el-table :data="detail.lines" size="small" border style="margin-top:10px">
          <el-table-column prop="accountCode" label="科目编码" width="90" />
          <el-table-column prop="accountName" label="会计科目" min-width="200" />
          <el-table-column label="借方" width="120" align="right"><template #default="{ row }">
            <span v-if="row.direction === 'dr'">{{ money(row.amount) }}</span><span v-else class="muted">—</span>
          </template></el-table-column>
          <el-table-column label="贷方" width="120" align="right"><template #default="{ row }">
            <span v-if="row.direction === 'cr'">{{ money(row.amount) }}</span><span v-else class="muted">—</span>
          </template></el-table-column>
        </el-table>
        <div class="balance">
          借方合计 <b>{{ money(detail.debitTotal) }}</b> · 贷方合计 <b>{{ money(detail.creditTotal) }}</b>
          <el-tag :type="detail.balanced ? 'success' : 'danger'" size="small" style="margin-left:8px">
            {{ detail.balanced ? '✓ 借贷平衡' : '✗ 不平衡' }}
          </el-tag>
          <el-tag v-if="detail.reversedByVoucherId" type="warning" size="small" style="margin-left:6px">本凭证已被红冲(#{{ detail.reversedByVoucherId }})</el-tag>
        </div>

        <div v-if="detail.siblingBooks.length" class="siblings">
          <div class="sib-title">🔁 双账口径对照(同源单据的对家账套)</div>
          <div v-for="s in detail.siblingBooks" :key="s.id" class="sib-row">
            <el-tag :type="bookTag[s.book]" size="small">{{ s.book === 'tax' ? '税务账' : '经营账' }}</el-tag>
            <span class="sib-no">{{ s.voucherNo }}</span>
            <span>{{ money(s.totalAmount) }}</span>
            <el-button link type="primary" size="small" @click="openDetail(s.id)">查看</el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.voucher-page { padding: 4px; }
.threshold-bar { margin-bottom: 12px; }
.threshold-bar :deep(.el-card__body) { padding: 12px 16px; }
.th-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.th-title { font-weight: 700; font-size: 15px; }
.th-note { font-size: 12px; color: #999; }
.th-note b { color: #e6a23c; }
.th-nums { display: flex; gap: 18px; font-size: 13px; margin-top: 8px; flex-wrap: wrap; }
.th-nums b { color: #303133; font-size: 14px; }
.th-nums .ops { margin-left: auto; color: #909399; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.toolbar .total { margin-left: auto; font-size: 12px; color: #666; }
.tip { font-size: 12px; color: #999; margin-top: 8px; line-height: 1.6; }
.neg { color: #e6a23c; }
.muted { color: #bbb; }
.balance { margin-top: 10px; font-size: 13px; }
.siblings { margin-top: 14px; border-top: 1px dashed #eee; padding-top: 10px; }
.sib-title { font-weight: 600; font-size: 13px; margin-bottom: 6px; }
.sib-row { display: flex; align-items: center; gap: 10px; font-size: 13px; padding: 3px 0; }
.sib-row .sib-no { font-family: monospace; color: #606266; }
</style>
