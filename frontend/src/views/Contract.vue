<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchContracts, fetchContractDetail, signContract, voidContract, renewContract, changeContract,
  type ContractListItem, type ContractDetail,
} from '@/api/contract'

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

// ---- 列表 ----
const filters = reactive<{ status: string; keyword: string }>({ status: '', keyword: '' })
const statuses = ['草稿', '生效', '到期转让', '关闭', '已作废']
const list = ref<ContractListItem[]>([])
const total = ref(0)
const loading = ref(false)
async function loadList() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 50 }
    if (filters.status) params.status = filters.status
    if (filters.keyword) params.keyword = filters.keyword
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
}

// ---- 签约 ----
const signVisible = ref(false)
const signForm = reactive<Record<string, any>>({
  no: '', customerId: undefined, termMonths: undefined, monthRent: undefined,
  endTransferPrice: undefined, targetIrr: undefined, startDate: '', remark: '',
  assets: [{ assetId: undefined, allocRent: undefined }],
})
function addAssetRow() { signForm.assets.push({ assetId: undefined, allocRent: undefined }) }
function removeAssetRow(i: number) { signForm.assets.splice(i, 1) }
async function submitSign() {
  const assets = signForm.assets.filter((a: any) => a.assetId)
  if (!signForm.no || !signForm.customerId || !assets.length) {
    ElMessage.warning('合同编号/客户/至少1台设备必填'); return
  }
  const body: Record<string, any> = { no: signForm.no, customerId: signForm.customerId, assets }
  for (const k of ['termMonths', 'monthRent', 'endTransferPrice', 'targetIrr', 'startDate', 'remark']) {
    if (signForm[k] !== undefined && signForm[k] !== '') body[k] = signForm[k]
  }
  await signContract(body)
  ElMessage.success('签约成功 · 已自动生成 N 期租金计划')
  signVisible.value = false
  signForm.assets = [{ assetId: undefined, allocRent: undefined }]
  loadList()
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

onMounted(loadList)
</script>

<template>
  <div class="contract-page">
    <div class="toolbar">
      <el-radio-group :model-value="curIdentity" @change="switchIdentity" size="small">
        <el-radio-button v-for="i in identities" :key="i.name" :value="i.name">{{ i.name }}</el-radio-button>
      </el-radio-group>
      <span class="hint">投资人(GP/LP)看不到单笔P&L/每期构成/集采成本 🔒</span>
      <el-button type="primary" size="small" style="margin-left:auto" @click="signVisible = true">+ 签约</el-button>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-select v-model="filters.status" placeholder="状态" clearable size="small" style="width:130px" @change="loadList">
        <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
      </el-select>
      <el-input v-model="filters.keyword" placeholder="合同编号" clearable size="small" style="width:180px;margin-left:8px" @keyup.enter="loadList" />
      <el-button size="small" style="margin-left:8px" @click="loadList">查询</el-button>
      <span class="total">共 {{ total }} 份</span>
    </el-card>

    <el-table :data="list" v-loading="loading" size="small" @row-click="(r:any) => openDetail(r.id)" style="cursor:pointer">
      <el-table-column prop="no" label="合同编号" width="150" />
      <el-table-column prop="customerName" label="客户" min-width="130" />
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

        <!-- 逆向操作 -->
        <div style="margin-bottom:12px">
          <el-button size="small" :disabled="detail.status !== '生效'" @click="doRenew">续租</el-button>
          <el-button size="small" :disabled="detail.status !== '生效'" @click="doChange">提前结清</el-button>
          <el-button size="small" type="danger" :disabled="detail.status === '已作废' || detail.status === '关闭'" @click="doVoid">作废(红冲)</el-button>
        </div>

        <!-- 要点 -->
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="客户">{{ detail.customerName }}</el-descriptions-item>
          <el-descriptions-item label="性质">{{ detail.nature }}</el-descriptions-item>
          <el-descriptions-item label="目标IRR">{{ detail.targetIrr != null ? (detail.targetIrr * 100).toFixed(0) + '%' : '—' }}</el-descriptions-item>
          <el-descriptions-item label="租期">{{ detail.termMonths }} 月</el-descriptions-item>
          <el-descriptions-item label="月租">{{ money(detail.monthRent) }}</el-descriptions-item>
          <el-descriptions-item label="期末转让价">{{ money(detail.endTransferPrice) }}</el-descriptions-item>
          <el-descriptions-item label="押金">{{ money(detail.deposit) }}</el-descriptions-item>
          <el-descriptions-item label="起租日">{{ detail.startDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="签约日">{{ detail.signDate || '—' }}</el-descriptions-item>
        </el-descriptions>

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
        <div class="block-title">挂载设备(单台分摊)</div>
        <el-table :data="detail.assets" size="small" border>
          <el-table-column prop="serialNo" label="序列号" width="130" />
          <el-table-column prop="category" label="品类" width="90" />
          <el-table-column prop="assetStatus" label="设备状态" width="100" />
          <el-table-column label="单台月租分摊"><template #default="{ row }">{{ money(row.allocRent) }}</template></el-table-column>
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
    <el-dialog v-model="signVisible" title="签约(自动生成 N 期租金计划)" width="640px">
      <el-form :model="signForm" label-width="120px" size="small">
        <el-form-item label="合同编号" required><el-input v-model="signForm.no" placeholder="HT-2026-0004" /></el-form-item>
        <el-form-item label="客户ID" required><el-input-number v-model="signForm.customerId" :min="1" style="width:100%" /></el-form-item>
        <el-form-item label="租期(月)"><el-input-number v-model="signForm.termMonths" :min="1" placeholder="缺省按品类" style="width:100%" /></el-form-item>
        <el-form-item label="月租合计(元)"><el-input-number v-model="signForm.monthRent" :min="0" :step="500" placeholder="缺省=Σ单台分摊" style="width:100%" /></el-form-item>
        <el-form-item label="期末转让价(元)"><el-input-number v-model="signForm.endTransferPrice" :min="0" :step="1000" style="width:100%" /></el-form-item>
        <el-form-item label="目标IRR(0-1)"><el-input-number v-model="signForm.targetIrr" :min="0" :max="1" :step="0.01" style="width:100%" /></el-form-item>
        <el-form-item label="起租日"><el-date-picker v-model="signForm.startDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="挂载设备">
          <div style="width:100%">
            <div v-for="(a, i) in signForm.assets" :key="i" style="display:flex;gap:8px;margin-bottom:6px">
              <el-input-number v-model="a.assetId" :min="1" placeholder="设备ID" style="width:130px" />
              <el-input-number v-model="a.allocRent" :min="0" :step="500" placeholder="单台分摊(可空)" style="width:170px" />
              <el-button size="small" @click="removeAssetRow(i)" :disabled="signForm.assets.length === 1">删</el-button>
            </div>
            <el-button size="small" @click="addAssetRow">+ 加一台</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="signVisible = false">取消</el-button><el-button type="primary" @click="submitSign">签约</el-button></template>
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
.recon { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 10px 12px; background: #fef0f0; border-radius: 6px; font-size: 13px; }
.recon.ok { background: #f0f9eb; }
.recon .eq { font-weight: 600; }
.recon .deposit { color: #909399; font-size: 12px; margin-left: auto; }
.sub { font-size: 12px; color: #666; margin-top: 6px; }
</style>
