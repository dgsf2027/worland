<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchSupplierPool, fetchSupplierDetail, fetchDependencyAlert, retireSupplier,
  createSupplier, updateSupplier,
  type SupplierPoolItem, type SupplierDetail, type DependencyAlert,
  type SupplierSaveRequest, type SupplyItemInput,
} from '@/api/supplier'

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
    const params: Record<string, any> = { page: 1, size: 50 }
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
function scoreColor(v: number) {
  return v >= 85 ? '#2f9e44' : v >= 75 ? '#2e6da4' : '#e8a33d'
}
function money(v?: number) {
  if (v === null || v === undefined) return '—'
  return v >= 10000 ? (v / 10000).toFixed(1) + '万' : '¥' + v.toLocaleString()
}
function pct(v?: number) {
  return v === null || v === undefined ? '—' : (v * 100).toFixed(0) + '%'
}

// ---- 新增 / 编辑(建档录入) ----
const invoiceTypes = ['增值税专用发票', '增值税普通发票', '无票']
const statusOptions = ['接触', '试样', '入库', '主供', '备供']
const formVisible = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)

function emptyForm(): SupplierSaveRequest {
  return {
    name: '', fullName: '', taxNo: '', regAddress: '', regPhone: '',
    bankName: '', bankAccount: '', accountName: '', invoiceType: '',
    contact: '', phone: '', mainCategory: '', status: '接触', remark: '',
  }
}
function emptySupply(): SupplyItemInput {
  return {
    itemType: '整机', itemName: '', category: '',
    quotePrice: null, firstPayRatio: null, accountDays: null,
    noInterest: 1, canSingleBuy: 1,
    scoreQuality: null, scoreDelivery: null, scoreService: null, scorePrice: null, scoreTerm: null,
    costMaterial: null, costProcessing: null, profitAmount: null, bomEstimate: null,
    isPrimary: 1, remark: '',
  }
}
const form = reactive<SupplierSaveRequest>(emptyForm())
// 新增时可顺手录一条「代表供货项」(报价/账期/评分的来源;不填则该供应商暂无报价)
const withSupply = ref(false)
const supply = reactive<SupplyItemInput>(emptySupply())

function resetForm() {
  Object.assign(form, emptyForm())
  Object.assign(supply, emptySupply())
  withSupply.value = false
}
function openCreate() {
  editingId.value = null
  resetForm()
  formVisible.value = true
}
async function openEdit(id: number) {
  const d = await fetchSupplierDetail(id)
  editingId.value = id
  resetForm()
  Object.assign(form, {
    name: d.name, fullName: d.fullName || '', taxNo: d.taxNo || '',
    regAddress: d.regAddress || '', regPhone: d.regPhone || '',
    bankName: d.bankName || '', bankAccount: d.bankAccount || '',
    accountName: d.accountName || '', invoiceType: d.invoiceType || '',
    contact: d.contact || '', phone: d.phone || '',
    mainCategory: d.mainCategory || '', status: d.status, remark: d.remark || '',
  })
  formVisible.value = true
}

async function submitForm() {
  if (!form.name || !form.name.trim()) { ElMessage.warning('供应商名称必填'); return }
  const body: SupplierSaveRequest = { ...form }
  if (editingId.value == null) {
    // 新增:勾了代表供货项才带 supplies
    if (withSupply.value) {
      if (!supply.itemName || !supply.itemName.trim()) { ElMessage.warning('供货项名称必填'); return }
      body.supplies = [{ ...supply }]
    }
  }
  // 编辑不传 supplies —— 后端 supplies=null 时保持供货矩阵原样(传了会全量覆盖)
  saving.value = true
  try {
    if (editingId.value == null) {
      await createSupplier(body)
      ElMessage.success('供应商已建档')
    } else {
      await updateSupplier(editingId.value, body)
      ElMessage.success('已保存')
      if (detail.value && detail.value.id === editingId.value) {
        detail.value = await fetchSupplierDetail(editingId.value)
      }
    }
    formVisible.value = false
    loadPool(); loadAlert()
  } finally {
    saving.value = false
  }
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

onMounted(() => { loadPool(); loadAlert() })
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
          <el-button type="success" @click="openCreate">+ 新增供应商</el-button>
          <span class="cnt">共 {{ total }} 家</span>
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
          <el-table-column label="供应商" min-width="180">
            <template #default="{ row }">
              <a class="lnk" @click="openDetail(row.id)">{{ row.name }}</a>
              <div v-if="row.fullName" class="sub-name">{{ row.fullName }}</div>
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
          <el-table-column label="收款资料" width="100">
            <template #default="{ row }">
              <el-tag :type="row.billingComplete ? 'success' : 'danger'" size="small" effect="plain">
                {{ row.billingComplete ? '齐' : '缺' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEdit(row.id)">编辑</el-button>
              <el-button v-if="row.status !== '淘汰'" link type="danger" size="small" @click="onRetire(row)">淘汰</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="mini">配件级可比价；供应商行可「淘汰/停用（留痕）」；每品类需 ≥2 家防单一依赖；「收款资料=缺」的供应商在采购付款环节无账户可打款</div>
      </el-tab-pane>

      <!-- ============ 供应商详情 ============ -->
      <el-tab-pane label="供应商详情" name="detail">
        <el-empty v-if="!detail" description="从供应商池点供应商名进入详情" />
        <template v-else>
          <h3 class="dt-title">
            {{ detail.name }}（{{ detail.mainCategory }}）
            <el-tag :type="(statusType[detail.status] as any) || 'info'" size="small">{{ detail.status }}</el-tag>
            <el-tag v-if="detail.scoreRadar" type="primary" size="small">履约分 {{ detail.scoreRadar.total }}</el-tag>
            <el-tag v-if="detail.costMasked" type="info" size="small">成本已按角色打码</el-tag>
            <el-button link type="primary" size="small" @click="openEdit(detail.id)">编辑档案</el-button>
          </h3>

          <div class="panel bill">
            <h4>工商 · 开票 · 收款信息（采购付款/开票依据）</h4>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="公司全称">
                <span v-if="detail.fullName">{{ detail.fullName }}</span>
                <el-tag v-else type="danger" size="small" effect="plain">未填</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="统一社会信用代码">
                <span v-if="detail.taxNo">{{ detail.taxNo }}</span>
                <el-tag v-else type="danger" size="small" effect="plain">未填</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="注册地址">{{ detail.regAddress || '—' }}</el-descriptions-item>
              <el-descriptions-item label="注册电话">{{ detail.regPhone || '—' }}</el-descriptions-item>
              <el-descriptions-item label="开户行">
                <span v-if="detail.bankName">{{ detail.bankName }}</span>
                <el-tag v-else type="danger" size="small" effect="plain">未填</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="银行账号">
                <el-tag v-if="detail.bankMasked" type="info" size="small" effect="plain">按角色打码</el-tag>
                <span v-else-if="detail.bankAccount">{{ detail.bankAccount }}</span>
                <el-tag v-else type="danger" size="small" effect="plain">未填</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="收款户名">{{ detail.accountName || '—' }}</el-descriptions-item>
              <el-descriptions-item label="发票类型">{{ detail.invoiceType || '—' }}</el-descriptions-item>
              <el-descriptions-item label="联系人">{{ detail.contact || '—' }}</el-descriptions-item>
              <el-descriptions-item label="联系电话">{{ detail.phone || '—' }}</el-descriptions-item>
              <el-descriptions-item label="备注" :span="2">{{ detail.remark || '—' }}</el-descriptions-item>
            </el-descriptions>
            <div class="mini">公司全称 / 开户行 / 银行账号三项缺任一，采购入库·应付环节无收款账户可打款</div>
          </div>

          <div class="grid2">
            <div class="panel">
              <h4>履约评分（多维）</h4>
              <div v-if="detail.scoreRadar">
                <div v-for="d in scoreDims" :key="d.key" class="scr">
                  <div class="sl"><span>{{ d.label }}</span><b>{{ (detail.scoreRadar as any)[d.key] }}</b></div>
                  <el-progress :percentage="(detail.scoreRadar as any)[d.key]" :color="scoreColor((detail.scoreRadar as any)[d.key])" :show-text="false" :stroke-width="10" />
                </div>
                <div class="scr">
                  <div class="sl"><span><b>加权总分</b></span><b :style="{ color: scoreColor(detail.scoreRadar.total) }">{{ detail.scoreRadar.total }}</b></div>
                  <el-progress :percentage="detail.scoreRadar.total" :color="scoreColor(detail.scoreRadar.total)" :show-text="false" :stroke-width="10" />
                </div>
                <div class="mini">加权总分由 rule_config 权重即时算，非落库字段</div>
              </div>
              <el-empty v-else description="该供应商暂未评分" :image-size="60" />
            </div>

            <div class="panel">
              <h4>价格构成（vs BOM 识别虚高）</h4>
              <div v-if="detail.priceComposition" class="wf">
                <span class="seg" style="background:#2e6da4" :style="{ flex: detail.priceComposition.material || 1 }">材料 {{ money(detail.priceComposition.material) }}</span>
                <span class="seg" style="background:#5a8fbf" :style="{ flex: detail.priceComposition.processing || 1 }">加工 {{ money(detail.priceComposition.processing) }}</span>
                <span class="seg" style="background:#b8862f" :style="{ flex: detail.priceComposition.profit || 1 }">利润 {{ money(detail.priceComposition.profit) }}</span>
              </div>
              <div v-if="detail.priceComposition" class="kv">
                <span>报价 {{ money(detail.priceComposition.quote) }} vs 我方BOM {{ money(detail.priceComposition.bomEstimate) }}</span>
                <b :class="detail.priceComposition.verdict === '合理' ? 'up' : 'warn'">{{ detail.priceComposition.verdict }}</b>
              </div>
              <el-empty v-else description="无价格构成数据（或角色不可见成本）" :image-size="60" />
            </div>
          </div>

          <div class="panel">
            <h4>供货矩阵（整机 + 配件）</h4>
            <el-table :data="detail.supplyMatrix" border size="small">
              <el-table-column prop="itemType" label="类型" width="70" />
              <el-table-column prop="itemName" label="供何物" min-width="140" />
              <el-table-column prop="category" label="品类" width="90" />
              <el-table-column label="报价" width="110" align="right">
                <template #default="{ row }">{{ row.quotePrice == null ? '—' : money(row.quotePrice) }}</template>
              </el-table-column>
              <el-table-column label="首付" width="80" align="right">
                <template #default="{ row }">{{ pct(row.firstPayRatio) }}</template>
              </el-table-column>
              <el-table-column label="账期" width="80" align="right">
                <template #default="{ row }">{{ row.accountDays == null ? '—' : row.accountDays + '天' }}</template>
              </el-table-column>
              <el-table-column label="可单采" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.canSingleBuy ? 'success' : 'warning'" size="small">{{ row.canSingleBuy ? '是' : '否' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="履约分" width="80" align="right">
                <template #default="{ row }">{{ row.scoreTotal ?? '—' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 新增 / 编辑供应商档案 ============ -->
    <el-dialog v-model="formVisible" :title="editingId == null ? '新增供应商（建档录入）' : '编辑供应商档案'" width="720px" top="6vh">
      <el-form :model="form" label-width="130px" size="small">
        <div class="fm-sec">基本信息</div>
        <div class="fm-grid">
          <el-form-item label="供应商简称" required>
            <el-input v-model="form.name" placeholder="恒丰自动化（列表/比价显示用）" />
          </el-form-item>
          <el-form-item label="主营品类">
            <el-select v-model="form.mainCategory" clearable style="width:100%">
              <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
          <el-form-item label="关系阶段">
            <el-select v-model="form.status" style="width:100%">
              <el-option v-for="s in statusOptions" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-form-item label="联系人">
            <el-input v-model="form.contact" placeholder="张经理" />
          </el-form-item>
          <el-form-item label="联系电话">
            <el-input v-model="form.phone" placeholder="13800000001" />
          </el-form-item>
        </div>

        <div class="fm-sec">工商 · 开票 · 收款（采购付款必需）</div>
        <el-form-item label="公司全称">
          <el-input v-model="form.fullName" placeholder="苏州恒丰自动化设备有限公司（工商注册名，开票抬头）" />
        </el-form-item>
        <div class="fm-grid">
          <el-form-item label="统一社会信用代码">
            <el-input v-model="form.taxNo" placeholder="91320500MA1XXXXX1A" />
          </el-form-item>
          <el-form-item label="发票类型">
            <el-select v-model="form.invoiceType" clearable style="width:100%">
              <el-option v-for="t in invoiceTypes" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
          <el-form-item label="注册电话">
            <el-input v-model="form.regPhone" placeholder="0512-6600-0001（开票用，区别于联系人手机）" />
          </el-form-item>
          <el-form-item label="开户行">
            <el-input v-model="form.bankName" placeholder="中国建设银行苏州吴中支行（支行全称）" />
          </el-form-item>
          <el-form-item label="银行账号">
            <el-input v-model="form.bankAccount" placeholder="32050166360800000001" />
          </el-form-item>
          <el-form-item label="收款户名">
            <el-input v-model="form.accountName" placeholder="留空默认取公司全称" />
          </el-form-item>
        </div>
        <el-form-item label="注册地址">
          <el-input v-model="form.regAddress" placeholder="江苏省苏州市吴中区木渎镇金枫路 1 号（开票用）" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>

        <!-- 供货项:仅新增时录代表项;编辑时不动供货矩阵,避免全量覆盖丢评分/价格构成 -->
        <template v-if="editingId == null">
          <div class="fm-sec">
            代表供货项（选填）
            <el-checkbox v-model="withSupply" style="margin-left:10px">同时录入一条供货项</el-checkbox>
          </div>
          <template v-if="withSupply">
            <div class="fm-grid">
              <el-form-item label="供货类型">
                <el-select v-model="supply.itemType" style="width:100%">
                  <el-option label="整机" value="整机" />
                  <el-option label="配件" value="配件" />
                </el-select>
              </el-form-item>
              <el-form-item label="供何物" required>
                <el-input v-model="supply.itemName" placeholder="播种墙 整机 / 电控系统" />
              </el-form-item>
              <el-form-item label="所属品类">
                <el-select v-model="supply.category" clearable style="width:100%">
                  <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
                </el-select>
              </el-form-item>
              <el-form-item label="集采报价(元)">
                <el-input-number v-model="supply.quotePrice" :min="0" :step="1000" controls-position="right" style="width:100%" placeholder="按图报价留空" />
              </el-form-item>
              <el-form-item label="首付比例(0-1)">
                <el-input-number v-model="supply.firstPayRatio" :min="0" :max="1" :step="0.05" :precision="2" controls-position="right" style="width:100%" />
              </el-form-item>
              <el-form-item label="账期(天)">
                <el-input-number v-model="supply.accountDays" :min="0" :step="15" controls-position="right" style="width:100%" />
              </el-form-item>
              <el-form-item label="可单采">
                <el-switch v-model="supply.canSingleBuy" :active-value="1" :inactive-value="0" />
              </el-form-item>
              <el-form-item label="账期无息">
                <el-switch v-model="supply.noInterest" :active-value="1" :inactive-value="0" />
              </el-form-item>
            </div>
            <div class="fm-sub">履约五维评分（0-100，选填；填了才有加权总分）</div>
            <div class="fm-grid">
              <el-form-item label="品质(故障率)"><el-input-number v-model="supply.scoreQuality" :min="0" :max="100" controls-position="right" style="width:100%" /></el-form-item>
              <el-form-item label="交期(准时率)"><el-input-number v-model="supply.scoreDelivery" :min="0" :max="100" controls-position="right" style="width:100%" /></el-form-item>
              <el-form-item label="服务(响应)"><el-input-number v-model="supply.scoreService" :min="0" :max="100" controls-position="right" style="width:100%" /></el-form-item>
              <el-form-item label="价格(vs市场)"><el-input-number v-model="supply.scorePrice" :min="0" :max="100" controls-position="right" style="width:100%" /></el-form-item>
              <el-form-item label="账期(首付低)"><el-input-number v-model="supply.scoreTerm" :min="0" :max="100" controls-position="right" style="width:100%" /></el-form-item>
            </div>
            <div class="fm-sub">价格构成（选填；vs 我方 BOM 识别虚高）</div>
            <div class="fm-grid">
              <el-form-item label="材料(元)"><el-input-number v-model="supply.costMaterial" :min="0" :step="1000" controls-position="right" style="width:100%" /></el-form-item>
              <el-form-item label="加工(元)"><el-input-number v-model="supply.costProcessing" :min="0" :step="1000" controls-position="right" style="width:100%" /></el-form-item>
              <el-form-item label="利润(元)"><el-input-number v-model="supply.profitAmount" :min="0" :step="1000" controls-position="right" style="width:100%" /></el-form-item>
              <el-form-item label="我方BOM估算(元)"><el-input-number v-model="supply.bomEstimate" :min="0" :step="1000" controls-position="right" style="width:100%" /></el-form-item>
            </div>
            <el-form-item label="供货项备注"><el-input v-model="supply.remark" placeholder="现金折扣/阶梯返利/维保返点" /></el-form-item>
          </template>
        </template>
        <div v-else class="fm-tip">供货矩阵不在本弹窗编辑，保存不会改动已有报价 / 评分 / 价格构成。</div>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">
          {{ editingId == null ? '建档' : '保存' }}
        </el-button>
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
.panel h4 { margin: 0 0 12px; font-size: 14px; }
.scr { margin-bottom: 10px; }
.sl { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 4px; }
.wf { display: flex; height: 34px; border-radius: 6px; overflow: hidden; margin-bottom: 10px; }
.wf .seg { color: #fff; font-size: 12px; display: flex; align-items: center; justify-content: center; }
.kv { display: flex; justify-content: space-between; font-size: 13px; padding: 6px 0; border-top: 1px dashed #eee; }
.up { color: #2f9e44; } .warn { color: #e8a33d; }
.sub-name { color: #999; font-size: 12px; font-weight: 400; margin-top: 2px; }
.bill { margin-bottom: 14px; }
.fm-sec { font-size: 13px; font-weight: 600; color: #2e6da4; border-left: 3px solid #2e6da4; padding-left: 8px; margin: 4px 0 12px; }
.fm-sub { font-size: 12px; color: #909399; margin: 0 0 10px 8px; }
.fm-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; }
.fm-tip { font-size: 12px; color: #909399; margin-left: 8px; }
@media (max-width: 900px) { .grid2 { grid-template-columns: 1fr; } }
</style>
