<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchTransfers, fetchTransferDetail, createExpiryTransfer, approveTransfer,
  disposeAsset, redeployAsset,
  type TransferItem, type TransferDetail, type TransferResult,
} from '@/api/transfer'
import { fetchAssets, type AssetListItem } from '@/api/asset'
import { fetchContracts, type ContractListItem } from '@/api/contract'

const route = useRoute()
const router = useRouter()
/** 跳设备 · 租赁台账 */
function goAsset(id?: number) {
  if (id) router.push({ path: '/asset', query: { id: String(id) } })
}

const activeTab = ref('list')
const list = ref<TransferItem[]>([])
const total = ref(0)
const loading = ref(false)
const filters = reactive<{ type: string; status: string }>({ type: '', status: '' })

const typeTag: Record<string, string> = { 转让: 'success', 二手: 'primary', 报废: 'danger', 收回: 'warning', 再投放: 'info' }
const statusTag: Record<string, string> = { 已完成: 'success', 待过账: 'info', 待审批: 'warning', 已作废: 'danger' }

async function loadList() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 50 }
    if (filters.type) params.type = filters.type
    if (filters.status) params.status = filters.status
    const res = await fetchTransfers(params)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function money(v?: number) {
  if (v === null || v === undefined) return '—'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// ---- 详情 ----
const detail = ref<TransferDetail | null>(null)
async function openDetail(id: number) {
  detail.value = await fetchTransferDetail(id)
  activeTab.value = 'detail'
}

// ---- 到期转让:从合同列表里选,选中后列出该合同挂载的设备 ----
const expiryForm = reactive<{ contractId?: number; approvalReason: string; remark: string }>({
  approvalReason: '', remark: '',
})
const contracts = ref<ContractListItem[]>([])
const contractsLoading = ref(false)
const contractAssets = ref<AssetListItem[]>([])
const contractAssetsLoading = ref(false)
async function loadContracts() {
  contractsLoading.value = true
  try {
    contracts.value = (await fetchContracts({ page: 1, size: 200 })).records.filter((c) => c.status !== '已作废')
  } finally {
    contractsLoading.value = false
  }
}
async function loadContractAssets(contractId?: number) {
  contractAssets.value = []
  if (!contractId) return
  contractAssetsLoading.value = true
  try {
    contractAssets.value = (await fetchAssets({ contractId, page: 1, size: 500 })).records
  } finally {
    contractAssetsLoading.value = false
  }
}
const lastResult = ref<TransferResult | null>(null)
async function submitExpiry() {
  if (!expiryForm.contractId) { ElMessage.warning('请先选择合同'); return }
  const body: Record<string, any> = { contractId: expiryForm.contractId }
  if (expiryForm.approvalReason) body.approvalReason = expiryForm.approvalReason
  if (expiryForm.remark) body.remark = expiryForm.remark
  const r = await createExpiryTransfer(body)
  lastResult.value = r
  if (r.needApproval) ElMessage.warning('名义价守卫触发 → 待审批')
  else ElMessage.success('到期转让已过账')
  loadList()
}

// ---- 审批 ----
async function onApprove(row: TransferItem) {
  try {
    const { value } = await ElMessageBox.prompt('审批意见(低价转让核准理由)', `审批过账 ${row.no}`, {
      confirmButtonText: '核准过账', cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim() ? true : '意见必填'),
    })
    await approveTransfer(row.id, { reason: value })
    ElMessage.success('审批通过并过账')
    loadList()
    openDetail(row.id)
  } catch { /* 取消 */ }
}

// ---- 复投飞轮:从台账里选设备,选中后显示账面价(判断是否会触发名义价守卫) ----
const disposeForm = reactive<{ assetId?: number; action: string; transferPrice?: number; approvalReason: string }>({
  action: '二手', approvalReason: '',
})
const disposeAssets = ref<AssetListItem[]>([])
const disposeAssetsLoading = ref(false)
async function loadDisposeAssets() {
  disposeAssetsLoading.value = true
  try {
    // 已转让/已报废是终态,不再出现在可处置列表里
    disposeAssets.value = (await fetchAssets({ page: 1, size: 500 })).records
      .filter((a) => a.status !== '已转让' && a.status !== '已报废')
  } finally {
    disposeAssetsLoading.value = false
  }
}
const pickedDisposeAsset = computed(() => disposeAssets.value.find((a) => a.id === disposeForm.assetId) || null)
const assetOptionLabel = (a: AssetListItem) =>
  `${a.category}${a.model ? ' · ' + a.model : ''} · ${a.serialNo}（${a.status}）`
async function submitDispose() {
  if (!disposeForm.assetId) { ElMessage.warning('请先选择设备'); return }
  const body: Record<string, any> = { assetId: disposeForm.assetId, action: disposeForm.action }
  if (disposeForm.transferPrice != null) body.transferPrice = disposeForm.transferPrice
  if (disposeForm.approvalReason) body.approvalReason = disposeForm.approvalReason
  const r = disposeForm.action === '再投放' ? await redeployAsset(body) : await disposeAsset(body)
  lastResult.value = r
  if (r.needApproval) ElMessage.warning('名义价守卫触发 → 待审批')
  else ElMessage.success(disposeForm.action + ' 已完成')
  loadList()
}

onMounted(() => {
  loadList()
  loadContracts()
  loadDisposeAssets()
  // 从设备详情的「转让/处置记录」跳过来(?id=):直接打开该处置单详情
  const id = Number(route.query.id)
  if (id) openDetail(id)
})
</script>

<template>
  <div class="page">
    <div class="sub">一只设备只有一条命：到期转让(逐台残值凭证+资产出账+合同关闭) · 名义价硬阈值守卫(低于账面/市场下限强制审批) · 复投飞轮(收回待处置→再投放/二手/报废)</div>

    <el-tabs v-model="activeTab">
      <!-- ============ 处置单列表 ============ -->
      <el-tab-pane label="转让/处置单" name="list">
        <div class="filterbar">
          <el-select v-model="filters.type" placeholder="类型" clearable style="width: 120px" @change="loadList">
            <el-option v-for="t in ['转让','二手','报废','收回','再投放']" :key="t" :label="t" :value="t" />
          </el-select>
          <el-select v-model="filters.status" placeholder="状态" clearable style="width: 120px" @change="loadList">
            <el-option v-for="s in ['待审批','待过账','已完成','已作废']" :key="s" :label="s" :value="s" />
          </el-select>
          <el-button type="primary" @click="loadList">筛选</el-button>
          <span class="cnt">共 {{ total }} 单</span>
        </div>

        <el-table :data="list" v-loading="loading" stripe border size="small">
          <el-table-column label="单号" min-width="170">
            <template #default="{ row }"><a class="lnk" @click="openDetail(row.id)">{{ row.no }}</a></template>
          </el-table-column>
          <el-table-column label="类型" width="80">
            <template #default="{ row }"><el-tag :type="(typeTag[row.type] as any) || 'info'" size="small">{{ row.type }}</el-tag></template>
          </el-table-column>
          <el-table-column label="合同 / 客户" min-width="180">
            <template #default="{ row }">
              <span v-if="row.contractNo">{{ row.contractNo }}</span><span v-else class="muted">按台处置</span>
              <div v-if="row.customerName" class="mini2">{{ row.customerName }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="assetCount" label="台数" width="60" align="right" />
          <el-table-column label="转让价" width="120" align="right"><template #default="{ row }">{{ money(row.totalPrice) }}</template></el-table-column>
          <el-table-column label="处置损益" width="120" align="right">
            <template #default="{ row }"><span :class="(row.totalGain ?? 0) >= 0 ? 'up' : 'warn'">{{ money(row.totalGain) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="(statusTag[row.status] as any) || 'info'" size="small">{{ row.status }}</el-tag>
              <el-tag v-if="row.needApproval" type="danger" size="small" style="margin-left:3px">名义价</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="row.status === '待审批'" link type="warning" size="small" @click="onApprove(row)">审批过账</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="mini">P0-A 拆头+逐台行；book_value 处置时快照不回写；gain=转让价−账面；残值凭证并入分期收款销售计税(税务账)+经营口径处置损益(经营账)。</div>
      </el-tab-pane>

      <!-- ============ 到期转让 ============ -->
      <el-tab-pane label="到期转让(挂合同)" name="expiry">
        <div class="panel">
          <h4>到期转让：挂合同 N 台 → 逐台残值凭证 + 资产出账(→已转让) → 合同关闭</h4>
          <div class="frm">
            <el-select v-model="expiryForm.contractId" filterable clearable style="width: 340px"
              :loading="contractsLoading" placeholder="选合同（到期转让挂合同）" @change="loadContractAssets">
              <el-option v-for="c in contracts" :key="c.id"
                :label="`${c.no} · ${c.customerName || ''} · ${c.status}`" :value="c.id" />
            </el-select>
            <el-input v-model="expiryForm.approvalReason" placeholder="低价转让理由(触发名义价守卫时必填)" style="width: 320px" />
            <el-input v-model="expiryForm.remark" placeholder="备注" style="width: 200px" />
            <el-button type="primary" @click="submitExpiry">提交到期转让</el-button>
          </div>
          <template v-if="expiryForm.contractId">
            <div class="mini">该合同下的设备（提交后逐台建行）：</div>
            <el-table :data="contractAssets" v-loading="contractAssetsLoading" size="small" border max-height="240" style="margin-top:6px">
              <el-table-column label="设备" min-width="200">
                <template #default="{ row }">
                  <a class="lnk" @click="goAsset(row.id)">{{ row.category }}{{ row.model ? ' · ' + row.model : '' }}</a>
                  <div class="mini2">{{ row.serialNo }}</div>
                </template>
              </el-table-column>
              <el-table-column prop="status" label="台账状态" width="110" />
              <el-table-column label="账面价" width="120" align="right"><template #default="{ row }">{{ money(row.bookValue) }}</template></el-table-column>
              <el-table-column label="残值" width="110" align="right"><template #default="{ row }">{{ money(row.residualValue) }}</template></el-table-column>
              <el-table-column label="处置状态" width="140">
                <template #default="{ row }">{{ row.disposalStatus || '—' }}</template>
              </el-table-column>
            </el-table>
            <div v-if="!contractAssets.length && !contractAssetsLoading" class="mini">该合同下还没有挂载设备。</div>
          </template>
          <div class="mini">默认取合同全部在租设备，转让价按 endTransferPrice 均摊；触发名义价守卫(&lt;账面/市场×下限)则转「待审批」。</div>
        </div>
        <div v-if="lastResult" class="panel result">
          <h4>执行结果 · {{ lastResult.type }} · {{ lastResult.status }}</h4>
          <div v-if="lastResult.needApproval" class="guard">⚠️ 名义价硬阈值守卫触发：转让价低于账面价/市场价下限，已强制升级审批(P1-19)</div>
          <ul v-if="lastResult.nominalGuardHits?.length" class="hits"><li v-for="(x,i) in lastResult.nominalGuardHits" :key="i">{{ x }}</li></ul>
          <ul class="impact"><li v-for="(x,i) in (lastResult.impact || [])" :key="i">{{ x }}</li></ul>
        </div>
      </el-tab-pane>

      <!-- ============ 复投飞轮 ============ -->
      <el-tab-pane label="复投飞轮(处置)" name="dispose">
        <div class="panel">
          <h4>收回待处置 → 再投放(回在租池) / 二手 / 报废</h4>
          <div class="frm">
            <el-select v-model="disposeForm.assetId" filterable clearable style="width: 340px"
              :loading="disposeAssetsLoading" placeholder="选设备（台账里可处置的）">
              <el-option v-for="a in disposeAssets" :key="a.id" :label="assetOptionLabel(a)" :value="a.id" />
            </el-select>
            <el-select v-model="disposeForm.action" style="width: 120px">
              <el-option v-for="a in ['再投放','二手','报废']" :key="a" :label="a" :value="a" />
            </el-select>
            <el-input-number v-if="disposeForm.action === '二手'" v-model="disposeForm.transferPrice" :min="0" placeholder="二手价" controls-position="right" style="width: 140px" />
            <el-input v-if="disposeForm.action === '二手'" v-model="disposeForm.approvalReason" placeholder="低价理由(触发守卫时必填)" style="width: 260px" />
            <el-button type="primary" @click="submitDispose">执行处置</el-button>
          </div>
          <div v-if="pickedDisposeAsset" class="picked">
            <a class="lnk" @click="goAsset(pickedDisposeAsset.id)">{{ pickedDisposeAsset.serialNo }}</a>
            · 台账状态 <b>{{ pickedDisposeAsset.status }}</b>
            · 账面价 <b>{{ money(pickedDisposeAsset.bookValue) }}</b>
            · 残值 <b>{{ money(pickedDisposeAsset.residualValue) }}</b>
            <span v-if="pickedDisposeAsset.disposalStatus" class="mini2">已有处置记录：{{ pickedDisposeAsset.disposalStatus }}</span>
            <div v-if="disposeForm.action === '二手' && disposeForm.transferPrice != null
              && pickedDisposeAsset.bookValue != null && disposeForm.transferPrice < pickedDisposeAsset.bookValue"
              class="guard-hint">⚠️ 二手价低于账面价，提交后会触发名义价守卫转「待审批」，低价理由必填</div>
          </div>
          <div class="mini">再投放需老板；二手/报废需财务+老板。二手低价触发名义价守卫→待审批。</div>
        </div>
        <div v-if="lastResult" class="panel result">
          <h4>执行结果 · {{ lastResult.type }} · {{ lastResult.status }}</h4>
          <div v-if="lastResult.needApproval" class="guard">⚠️ 名义价硬阈值守卫触发：转让价低于账面价/市场价下限，已强制升级审批(P1-19)</div>
          <ul v-if="lastResult.nominalGuardHits?.length" class="hits"><li v-for="(x,i) in lastResult.nominalGuardHits" :key="i">{{ x }}</li></ul>
          <ul class="impact"><li v-for="(x,i) in (lastResult.impact || [])" :key="i">{{ x }}</li></ul>
        </div>
      </el-tab-pane>

      <!-- ============ 详情 ============ -->
      <el-tab-pane label="处置单详情" name="detail">
        <el-empty v-if="!detail" description="从列表点单号进入详情" />
        <template v-else>
          <h3 class="dt-title">
            {{ detail.order.no }}
            <el-tag :type="(typeTag[detail.order.type] as any) || 'info'" size="small">{{ detail.order.type }}</el-tag>
            <el-tag :type="(statusTag[detail.order.status] as any) || 'info'" size="small">{{ detail.order.status }}</el-tag>
            <el-tag v-if="detail.order.needApproval" type="danger" size="small">名义价守卫</el-tag>
            <span v-if="detail.order.contractNo" class="muted">合同 {{ detail.order.contractNo }}</span>
            <span v-if="detail.order.customerName" class="muted">· {{ detail.order.customerName }}</span>
          </h3>
          <div v-if="detail.order.approvalReason" class="kv"><span>审批理由</span><b>{{ detail.order.approvalReason }}</b></div>
          <div v-if="detail.order.approvedByName" class="kv"><span>审批人</span><b>{{ detail.order.approvedByName }} · {{ detail.order.approvedAt }}</b></div>
          <el-table :data="detail.lines" border size="small" style="margin-top:10px">
            <el-table-column label="设备（租赁台账）" min-width="190">
              <template #default="{ row }">
                <a class="lnk" @click="goAsset(row.assetId)">{{ row.assetLabel || row.serialNo || ('#' + row.assetId) }}</a>
                <div class="mini2">
                  {{ row.serialNo || '设备已删除' }}<span v-if="row.assetStatus"> · {{ row.assetStatus }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="市场价" width="110" align="right"><template #default="{ row }">{{ money(row.marketPrice) }}</template></el-table-column>
            <el-table-column label="账面快照" width="110" align="right"><template #default="{ row }">{{ money(row.bookValue) }}</template></el-table-column>
            <el-table-column label="转让价" width="110" align="right"><template #default="{ row }">{{ money(row.transferPrice) }}</template></el-table-column>
            <el-table-column label="逐台损益" width="110" align="right">
              <template #default="{ row }"><span :class="(row.gain ?? 0) >= 0 ? 'up' : 'warn'">{{ money(row.gain) }}</span></template>
            </el-table-column>
            <el-table-column label="名义价" width="70">
              <template #default="{ row }"><el-tag v-if="row.nominalFlag" type="danger" size="small">是</el-tag><span v-else>—</span></template>
            </el-table-column>
            <el-table-column label="凭证" width="80" align="right"><template #default="{ row }">{{ row.voucherId ? '#' + row.voucherId : '—' }}</template></el-table-column>
          </el-table>
        </template>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.page { padding: 4px; }
.sub { color: #666; font-size: 13px; margin-bottom: 10px; }
.filterbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.cnt { color: #999; font-size: 12px; margin-left: auto; }
.lnk { color: #2e6da4; cursor: pointer; font-weight: 600; }
.lnk:hover { text-decoration: underline; }
.mini { color: #999; font-size: 12px; margin-top: 8px; }
.panel { border: 1px solid #eee; border-radius: 8px; padding: 14px; background: #fff; margin-bottom: 12px; }
.panel h4 { margin: 0 0 12px; font-size: 14px; }
.frm { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
.dt-title { font-size: 16px; margin: 4px 0 12px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.muted { color: #999; font-size: 13px; }
.kv { display: flex; gap: 10px; font-size: 13px; padding: 4px 0; }
.kv span { color: #888; min-width: 70px; }
.up { color: #2f9e44; } .warn { color: #d9534f; }
.result .guard { color: #d9534f; font-weight: 600; background: #fff3f3; padding: 8px; border-radius: 6px; margin-bottom: 8px; }
.result .hits, .result .impact { margin: 6px 0; padding-left: 18px; font-size: 13px; }
.result .hits li { color: #d9534f; }
.result .impact li { color: #555; }
.mini2 { color: #909399; font-size: 12px; }
.picked { background: #f6f9ff; border: 1px solid #e3ecff; border-radius: 6px; padding: 8px 10px; font-size: 13px; margin-bottom: 8px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.guard-hint { color: #d9534f; font-size: 12px; width: 100%; }
</style>
