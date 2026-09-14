<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchCustomerPool, fetchCustomerDetail, fetchPipeline, addFollowup, runAdmission,
  createCustomer, updateCustomer, exportCustomers, CUSTOMER_SCOPES,
  type CustomerPoolItem, type CustomerDetail, type Pipeline,
} from '@/api/customer'

const router = useRouter()

const activeTab = ref('pool')

// ---- 当前登录身份(占位头切换,验证 P0-E 隔离) ----
const identities = [
  { name: '老板', role: '老板' },
  { name: '财务', role: '财务' },
  { name: '业务', role: '业务' },
  { name: '投资人', role: 'LP' },
]
const curIdentity = ref(localStorage.getItem('rent_user_name') || '老板')
function switchIdentity(name: string) {
  const id = identities.find((i) => i.name === name)!
  localStorage.setItem('rent_user_name', id.name)
  localStorage.setItem('rent_user_role', id.role)
  curIdentity.value = name
  loadPool(); loadPipeline()
  if (detail.value) openDetail(detail.value.id)
  ElMessage.success(`已切换为 ${id.name}（${id.role}）· 观察行级/字段级隔离`)
}

// ---- 客户池 ----
const filters = reactive<{ phase: string; rating: string; keyword: string }>({ phase: '', rating: '', keyword: '' })
const phases = ['线索', '跟进', '商机', '成交', '在租', '流失']
const pool = ref<CustomerPoolItem[]>([])
const total = ref(0)
const loading = ref(false)

async function loadPool() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 50 }
    if (filters.phase) params.phase = filters.phase
    if (filters.rating) params.rating = filters.rating
    if (filters.keyword) params.keyword = filters.keyword
    const res = await fetchCustomerPool(params)
    pool.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

const phaseType: Record<string, string> = {
  线索: 'info', 跟进: 'primary', 商机: 'warning', 成交: 'success', 在租: 'success', 流失: 'info',
}
const followType: Record<string, string> = { 逾期: 'danger', 今天: 'warning', 明天: 'warning', 正常: 'info', 无: 'info' }
function money(v?: number | null) {
  if (v === null || v === undefined) return '🔒'
  return v >= 10000 ? (v / 10000).toFixed(1) + '万' : '¥' + v.toLocaleString()
}
function pct(v?: number | null) {
  return v === null || v === undefined ? '🔒' : (v * 100).toFixed(1) + '%'
}

// ---- 销售管道 ----
const pipeline = ref<Pipeline | null>(null)
async function loadPipeline() { pipeline.value = await fetchPipeline() }
const tagType: Record<string, string> = { 战略: 'danger', 普通: 'primary', 观察: 'info' }

// ---- 客户详情 ----
const detail = ref<CustomerDetail | null>(null)
const creditDims = [
  { key: 'profit', label: '盈利能力' }, { key: 'cashflow', label: '现金流' },
  { key: 'stability', label: '经营稳定' }, { key: 'history', label: '历史履约' }, { key: 'industry', label: '行业风险' },
]
async function openDetail(id: number) {
  try {
    detail.value = await fetchCustomerDetail(id)
    activeTab.value = 'detail'
  } catch { /* 403 越权已由拦截器提示 */ }
}
function scoreColor(v: number) {
  return v >= 75 ? '#2f9e44' : v >= 60 ? '#2e6da4' : v >= 50 ? '#e8a33d' : '#e0533d'
}
function ratingType(r?: string) {
  return r === 'A' ? 'success' : r === 'B' ? 'warning' : r === 'C' ? 'danger' : 'info'
}

async function onFollowup() {
  if (!detail.value) return
  try {
    const { value } = await ElMessageBox.prompt('跟进内容', `记一次跟进 · ${detail.value.name}`, {
      confirmButtonText: '保存', cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim() ? true : '内容必填'),
    })
    await addFollowup(detail.value.id, { method: '电话', content: value })
    ElMessage.success('跟进已记录')
    openDetail(detail.value.id)
  } catch { /* 取消 */ }
}
async function onAdmission() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm('按评级出授信/押金/目标IRR建议并落定（拒绝也留痕）', '风控准入', {
      confirmButtonText: '准入通过', cancelButtonText: '取消',
    })
    const ad = await runAdmission(detail.value.id, { approved: true })
    ElMessage.success(`准入完成：授信 ${money(ad.approvedCreditLimit)} / 押金 ${ad.approvedDepositMonths}月 / IRR ${pct(ad.approvedTargetIrr)}`)
    openDetail(detail.value.id)
  } catch { /* 取消 */ }
}

// ---- 一键导出 ----
const exporting = ref(false)
async function onExport() {
  exporting.value = true
  try {
    const params: Record<string, any> = {}
    if (filters.phase) params.phase = filters.phase
    if (filters.rating) params.rating = filters.rating
    if (filters.keyword) params.keyword = filters.keyword
    await exportCustomers(params)
    ElMessage.success('客户信息已导出（按当前筛选条件）')
  } finally {
    exporting.value = false
  }
}

// ---- 新增 / 编辑客户(工商信息) ----
const formVisible = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const saving = ref(false)
const form = reactive<Record<string, any>>({})

function capitalWan(v?: number | null) {
  return v === null || v === undefined ? '—' : (v / 10000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' 万元'
}

function openCreate() {
  formMode.value = 'create'
  Object.keys(form).forEach((k) => delete form[k])
  Object.assign(form, {
    id: undefined, name: '', legalPerson: '', registeredCapitalWan: undefined, businessScope: [],
    contact: '', phone: '', industry: '', phase: '线索',
  })
  formVisible.value = true
}

function openEdit() {
  if (!detail.value) return
  const d = detail.value
  formMode.value = 'edit'
  Object.keys(form).forEach((k) => delete form[k])
  Object.assign(form, {
    id: d.id,
    name: d.name,
    legalPerson: d.legalPerson || '',
    registeredCapitalWan: d.registeredCapital == null ? undefined : d.registeredCapital / 10000,
    businessScope: [...(d.businessScope || [])],
    contact: d.contact || '',
    phone: d.phone || '',
    industry: d.industry || '',
    phase: d.phase,
    // 编辑时原样回传,避免负责人/价值分层/信用画像被清掉
    ownerUser: d.ownerUser ?? null,
    valueTier: d.valueTier ?? null,
    scoreProfit: d.creditProfile?.profit ?? null,
    scoreCashflow: d.creditProfile?.cashflow ?? null,
    scoreStability: d.creditProfile?.stability ?? null,
    scoreHistory: d.creditProfile?.history ?? null,
    scoreIndustry: d.creditProfile?.industry ?? null,
  })
  formVisible.value = true
}

async function submitForm() {
  if (!String(form.name || '').trim()) {
    ElMessage.warning('公司名称必填')
    return
  }
  const { id, registeredCapitalWan, ...rest } = form
  const body = {
    ...rest,
    name: String(form.name).trim(),
    legalPerson: String(form.legalPerson || '').trim() || null,
    registeredCapital: registeredCapitalWan == null ? null : Math.round(Number(registeredCapitalWan) * 10000 * 100) / 100,
    contact: String(form.contact || '').trim() || null,
    phone: String(form.phone || '').trim() || null,
    industry: String(form.industry || '').trim() || null,
  }
  saving.value = true
  try {
    if (formMode.value === 'edit' && id) {
      await updateCustomer(id, body)
      ElMessage.success('客户信息已更新')
      await openDetail(id)
    } else {
      const newId = await createCustomer(body)
      ElMessage.success('客户已新增')
      await openDetail(newId)
    }
    formVisible.value = false
    loadPool(); loadPipeline()
  } finally {
    saving.value = false
  }
}

// ---- 关联合同 / 设备租赁台账 ----
const contractStatusType: Record<string, string> = { 生效: 'success', 草稿: 'info', 到期转让: 'warning', 关闭: 'info', 已作废: 'danger' }
const assetStatusType: Record<string, string> = {
  采购: 'info', 投放: 'warning', 在租: 'success', 待转让: 'primary', 已转让: 'info', 收回待处置: 'danger', 报废: 'info',
}
function goContract(id: number) {
  router.push({ path: '/contract', query: { id: String(id) } })
}
function goAsset(id: number) {
  router.push({ path: '/asset', query: { id: String(id) } })
}

onMounted(() => { loadPool(); loadPipeline() })
</script>

<template>
  <div class="page">
    <div class="topbar">
      <div class="sub">潜在客户 → 长期跟进 → 商机 → 成交 → 在租 → 续租/流失 全生命周期。业务(BD)养客、运营接单。支撑 100+ 客户。</div>
      <div class="ident">
        <span>当前身份(验隔离)：</span>
        <el-radio-group :model-value="curIdentity" size="small" @change="switchIdentity">
          <el-radio-button v-for="i in identities" :key="i.name" :value="i.name">{{ i.name }}</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <el-tabs v-model="activeTab">
      <!-- ============ 客户池 ============ -->
      <el-tab-pane label="客户池（列表）" name="pool">
        <div class="filterbar">
          <el-input v-model="filters.keyword" placeholder="🔍 搜客户名/联系人/电话" style="width: 220px" clearable @keyup.enter="loadPool" @clear="loadPool" />
          <el-select v-model="filters.phase" placeholder="阶段" clearable style="width: 110px" @change="loadPool">
            <el-option v-for="p in phases" :key="p" :label="p" :value="p" />
          </el-select>
          <el-select v-model="filters.rating" placeholder="评级" clearable style="width: 100px" @change="loadPool">
            <el-option v-for="r in ['A','B','C']" :key="r" :label="r" :value="r" />
          </el-select>
          <el-button type="primary" @click="loadPool">筛选</el-button>
          <span class="cnt">共 {{ total }} 家</span>
          <el-button :loading="exporting" @click="onExport">⬇ 一键导出</el-button>
          <el-button type="primary" @click="openCreate">+ 新增客户</el-button>
        </div>

        <el-table :data="pool" v-loading="loading" stripe border size="small">
          <el-table-column label="阶段" width="80">
            <template #default="{ row }"><el-tag :type="(phaseType[row.phase] as any)" size="small">{{ row.phase }}</el-tag></template>
          </el-table-column>
          <el-table-column label="公司名称" min-width="160">
            <template #default="{ row }">
              <a class="lnk" @click="openDetail(row.id)">{{ row.name }}</a>
              <el-tag v-if="row.inPublicPool" size="small" type="info" effect="plain" style="margin-left:4px">公海</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="法人" width="80"><template #default="{ row }">{{ row.legalPerson || '—' }}</template></el-table-column>
          <el-table-column label="业务范围" width="150">
            <template #default="{ row }">
              <el-tag v-for="s in row.businessScope" :key="s" size="small" class="scope-tag">{{ s }}</el-tag>
              <span v-if="!row.businessScope?.length">—</span>
            </template>
          </el-table-column>
          <el-table-column label="主要联系人" width="130">
            <template #default="{ row }">{{ row.contact || '—' }}<div class="mini-line">{{ row.phone || '' }}</div></template>
          </el-table-column>
          <el-table-column label="可在租合同" width="100" align="center">
            <template #default="{ row }">
              <b :class="{ up: row.activeContractCount > 0 }">{{ row.activeContractCount }}</b>
              <span class="mini-inline"> / {{ row.contractTotal }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="ownerName" label="负责业务" width="100" />
          <el-table-column label="评级" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.rating" :type="(ratingType(row.rating) as any)" size="small">{{ row.ratingPredicted ? '预' : '' }}{{ row.rating }}</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="在租/商机额" width="120" align="right">
            <template #default="{ row }">{{ row.exposureOrOppAmount != null ? money(row.exposureOrOppAmount) : (row.sensitiveMasked ? '🔒' : '—') }}</template>
          </el-table-column>
          <el-table-column label="逾期应收" width="110" align="right">
            <template #default="{ row }">
              <span :class="{ down: row.receivableOverdue }">{{ row.receivableOverdue ? money(row.receivableOverdue) : (row.sensitiveMasked ? '🔒' : '—') }}</span>
            </template>
          </el-table-column>
          <el-table-column label="下次跟进" width="130">
            <template #default="{ row }">
              <template v-if="row.nextFollowDate">
                {{ row.nextFollowDate }}
                <el-tag v-if="row.followStatus && row.followStatus !== '正常'" :type="(followType[row.followStatus] as any)" size="small">{{ row.followStatus }}</el-tag>
              </template>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="mini">🔒 客户池含"公海"(未分配)可认领；业务只看自己名下+公海，成本价/别人客户服务端隔离(切"业务"身份看行数变少、切"投资人"看🔒打码)</div>
      </el-tab-pane>

      <!-- ============ 销售管道 ============ -->
      <el-tab-pane label="销售管道（看板）" name="pipe">
        <div class="mini" style="margin-bottom:10px">
          加权预测本季新签 ≈ <b class="up">{{ money(pipeline?.weightedForecast) }}</b>（Σ 商机额×成交概率）
        </div>
        <div class="kanban">
          <div v-for="col in pipeline?.columns || []" :key="col.phase" class="kcol">
            <h4>{{ col.phase }} <span>{{ col.count }}</span></h4>
            <div v-for="card in col.cards" :key="card.customerId" class="kcard" @click="openDetail(card.customerId)">
              <b>{{ card.name }}</b>
              <div class="km">{{ card.ownerName }} · {{ card.amount != null ? money(card.amount) : '—' }}</div>
              <el-tag v-if="card.tag" :type="(tagType[card.tag] as any) || 'info'" size="small">{{ card.tag }}</el-tag>
            </div>
            <el-empty v-if="!col.cards.length" description="" :image-size="0" />
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ 客户详情 ============ -->
      <el-tab-pane label="客户详情" name="detail">
        <el-empty v-if="!detail" description="从客户池或管道点客户名进入详情" />
        <template v-else>
          <h3 class="dt-title">
            {{ detail.name }}（{{ detail.industry }}）
            <el-tag :type="(phaseType[detail.phase] as any)" size="small">{{ detail.phase }}</el-tag>
            <el-tag v-if="detail.creditProfile?.rating" :type="(ratingType(detail.creditProfile.rating) as any)" size="small">评级{{ detail.creditProfile.rating }}</el-tag>
            <el-tag v-if="detail.valueTier" :type="(tagType[detail.valueTier] as any)" size="small">{{ detail.valueTier }}价值</el-tag>
            <el-tag v-if="detail.sensitiveMasked" type="info" size="small">敏感字段已按角色打码</el-tag>
            <span class="owner">负责业务：{{ detail.ownerName }}</span>
          </h3>

          <div class="panel">
            <h4>公司信息
              <el-button link type="primary" size="small" style="float:right" @click="openEdit">编辑</el-button>
            </h4>
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="公司名称">{{ detail.name }}</el-descriptions-item>
              <el-descriptions-item label="法人">{{ detail.legalPerson || '—' }}</el-descriptions-item>
              <el-descriptions-item label="注册资本">{{ capitalWan(detail.registeredCapital) }}</el-descriptions-item>
              <el-descriptions-item label="业务范围">{{ detail.businessScope?.join(' / ') || '—' }}</el-descriptions-item>
              <el-descriptions-item label="主要联系人">{{ detail.contact || '—' }}</el-descriptions-item>
              <el-descriptions-item label="联系方式">{{ detail.phone || '—' }}</el-descriptions-item>
            </el-descriptions>
          </div>

          <div class="panel">
            <h4>关联合同 · 设备租赁台账</h4>
            <div class="contract-kpi">
              <div><span>可在租合同</span><b class="up">{{ detail.contracts.activeCount }}</b><em>份</em></div>
              <div><span>合同总数（不含作废）</span><b>{{ detail.contracts.total }}</b><em>份</em></div>
              <div><span>在租设备</span><b>{{ detail.contracts.activeAssetCount }}</b><em>台</em></div>
            </div>
            <el-table v-if="detail.contracts.rows.length" :data="detail.contracts.rows" size="small" border row-key="id">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <el-table :data="row.assets" size="small" class="asset-sub">
                    <el-table-column label="设备序列号" min-width="130">
                      <template #default="{ row: a }"><a class="lnk" @click="goAsset(a.id)">{{ a.serialNo || ('#' + a.id) }}</a></template>
                    </el-table-column>
                    <el-table-column prop="category" label="品类" width="90" />
                    <el-table-column prop="model" label="型号" min-width="140" show-overflow-tooltip />
                    <el-table-column label="台账状态" width="110">
                      <template #default="{ row: a }"><el-tag v-if="a.status" :type="(assetStatusType[a.status] as any) || 'info'" size="small">{{ a.status }}</el-tag></template>
                    </el-table-column>
                    <el-table-column label="单台月租" width="110" align="right">
                      <template #default="{ row: a }">{{ a.allocRent != null ? money(a.allocRent) : '—' }}</template>
                    </el-table-column>
                  </el-table>
                  <div v-if="!row.assets.length" class="mini" style="padding-left:12px">该合同未挂设备</div>
                </template>
              </el-table-column>
              <el-table-column label="合同编号" min-width="140">
                <template #default="{ row }"><a class="lnk" @click="goContract(row.id)">{{ row.no }}</a></template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }"><el-tag :type="(contractStatusType[row.status] as any) || 'info'" size="small">{{ row.status }}</el-tag></template>
              </el-table-column>
              <el-table-column label="设备" width="70" align="center"><template #default="{ row }">{{ row.assets.length }} 台</template></el-table-column>
              <el-table-column label="月租" width="100" align="right"><template #default="{ row }">{{ row.monthRent != null ? money(row.monthRent) : '—' }}</template></el-table-column>
              <el-table-column label="租期" width="80" align="center"><template #default="{ row }">{{ row.termMonths ? row.termMonths + ' 月' : '—' }}</template></el-table-column>
              <el-table-column label="起租 → 到期" width="190"><template #default="{ row }">{{ row.startDate || '—' }} → {{ row.endDate || '—' }}</template></el-table-column>
            </el-table>
            <el-empty v-else description="该客户暂无合同" :image-size="60" />
            <div class="mini">可在租合同 = 状态为「生效」的合同；展开合同可看挂的设备及其在设备租赁台账中的当前状态。</div>
          </div>

          <div class="grid2">
            <div class="panel">
              <h4>信用画像（多维）</h4>
              <div v-if="detail.creditProfile">
                <div v-for="d in creditDims" :key="d.key" class="scr">
                  <div class="sl"><span>{{ d.label }}</span><b :style="{ color: scoreColor((detail.creditProfile as any)[d.key]) }">{{ (detail.creditProfile as any)[d.key] }}</b></div>
                  <el-progress :percentage="(detail.creditProfile as any)[d.key]" :color="scoreColor((detail.creditProfile as any)[d.key])" :show-text="false" :stroke-width="10" />
                </div>
                <div class="scr">
                  <div class="sl"><span><b>加权信用分 → 评级</b></span><b>{{ detail.creditProfile.compositeScore }} → {{ detail.creditProfile.rating }}</b></div>
                </div>
                <div class="mini">评级由 rule_config 权重+阶梯即时算</div>
              </div>
              <el-empty v-else description="尚未评分" :image-size="60" />

              <template v-if="detail.admission">
                <div class="kv"><span>准入建议(授信/押金/目标IRR)</span>
                  <b>{{ money(detail.admission.suggestCreditLimit) }} / {{ detail.admission.suggestDepositMonths ?? '—' }}月 / {{ pct(detail.admission.suggestTargetIrr) }}</b>
                </div>
                <div class="kv" v-if="detail.admission.approvedCreditLimit != null || detail.admission.note"><span>已落定 / 结论</span>
                  <b class="up">{{ detail.admission.approvedCreditLimit != null ? money(detail.admission.approvedCreditLimit) : '—' }} · {{ detail.admission.note || '未准入' }}</b>
                </div>
              </template>
            </div>

            <div class="panel">
              <h4>客户价值 & 风险敞口</h4>
              <div class="kv"><span>累计合同 / 累计收租</span><b>{{ detail.valueExposure.contractCount }} 份 / {{ money(detail.valueExposure.cumulativeRent) }}</b></div>
              <div class="kv"><span>累计利润(LTV)</span><b class="up">{{ money(detail.valueExposure.cumulativeProfit) }}</b></div>
              <div class="kv"><span>续租率</span><b>{{ detail.valueExposure.renewRate != null ? (detail.valueExposure.renewRate * 100).toFixed(0) + '%' : '—' }}</b></div>
              <div class="kv"><span>在租敞口 / 逾期应收</span><b>{{ money(detail.valueExposure.exposureAmount) }} / <span class="down">{{ detail.valueExposure.receivableOverdue ? money(detail.valueExposure.receivableOverdue) : '—' }}</span></b></div>
              <div class="kv"><span>占总应收集中度</span><b class="warn">{{ (detail.valueExposure.concentration * 100).toFixed(2) }}%</b></div>
              <div style="margin-top:12px">
                <el-button size="small" type="primary" @click="onAdmission">风控准入结论</el-button>
              </div>
            </div>
          </div>

          <div class="panel">
            <h4>跟进时间线（业务长期跟进）
              <el-button link type="primary" size="small" style="float:right" @click="onFollowup">＋ 记一次跟进</el-button>
            </h4>
            <el-timeline>
              <el-timeline-item
                v-for="(ev, idx) in detail.timeline" :key="idx"
                :timestamp="(ev.followTime || '').replace('T', ' ').slice(0, 16)" placement="top">
                <b>{{ ev.content }}</b>
                <span class="tl-meta">
                  {{ ev.method }} · {{ ev.userName }}
                  <template v-if="ev.result"> · {{ ev.result }}</template>
                  <template v-if="ev.nextFollowDate"> · 下次 {{ ev.nextFollowDate }}</template>
                </span>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-if="!detail.timeline.length" description="暂无跟进记录" :image-size="60" />
          </div>
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- 新增 / 编辑客户 -->
    <el-dialog v-model="formVisible" :title="formMode === 'edit' ? '编辑客户信息' : '新增客户'" width="600px" :close-on-click-modal="false">
      <el-form :model="form" label-width="110px" size="small" :disabled="saving">
        <el-form-item label="公司名称" required>
          <el-input v-model="form.name" maxlength="128" placeholder="工商注册全称" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="法人"><el-input v-model="form.legalPerson" maxlength="64" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="注册资本(万元)">
              <el-input-number v-model="form.registeredCapitalWan" :min="0" :precision="2" :step="100" controls-position="right" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="业务范围">
          <el-checkbox-group v-model="form.businessScope">
            <el-checkbox v-for="s in CUSTOMER_SCOPES" :key="s" :value="s">{{ s }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="主要联系人"><el-input v-model="form.contact" maxlength="64" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="联系方式"><el-input v-model="form.phone" maxlength="32" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="行业"><el-input v-model="form.industry" maxlength="32" placeholder="仓储/电商/物流…" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="阶段">
              <el-select v-model="form.phase" style="width:100%">
                <el-option v-for="p in phases" :key="p" :label="p" :value="p" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 4px; }
.topbar { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.sub { color: #666; font-size: 13px; max-width: 640px; }
.ident { font-size: 12px; color: #999; display: flex; align-items: center; gap: 6px; }
.filterbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.cnt { color: #999; font-size: 12px; margin-left: auto; }
.lnk { color: #2e6da4; cursor: pointer; font-weight: 600; }
.lnk:hover { text-decoration: underline; }
.mini { color: #999; font-size: 12px; margin-top: 8px; }
.down { color: #e0533d; } .up { color: #2f9e44; } .warn { color: #e8a33d; }
.dt-title { font-size: 16px; margin: 4px 0 12px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.dt-title .owner { color: #999; font-size: 12px; margin-left: auto; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 14px; }
.panel { border: 1px solid #eee; border-radius: 8px; padding: 14px; background: #fff; margin-bottom: 14px; }
.panel h4 { margin: 0 0 12px; font-size: 14px; }
.scr { margin-bottom: 10px; }
.sl { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 4px; }
.kv { display: flex; justify-content: space-between; font-size: 13px; padding: 6px 0; border-top: 1px dashed #eee; }
.tl-meta { color: #999; font-size: 12px; margin-left: 6px; }
.scope-tag { margin: 0 4px 2px 0; }
.mini-line { color: #999; font-size: 12px; }
.mini-inline { color: #999; font-size: 12px; }
.contract-kpi { display: flex; gap: 14px; margin-bottom: 12px; flex-wrap: wrap; }
.contract-kpi > div { flex: 1; min-width: 150px; background: #f6f8fa; border-radius: 6px; padding: 10px 12px; display: flex; align-items: baseline; gap: 6px; }
.contract-kpi span { color: #666; font-size: 12px; margin-right: auto; }
.contract-kpi b { font-size: 22px; }
.contract-kpi em { color: #999; font-size: 12px; font-style: normal; }
.asset-sub { margin: 0 12px; width: calc(100% - 24px); }
/* 看板 */
.kanban { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; }
.kcol { background: #f6f8fa; border-radius: 8px; padding: 8px; min-height: 120px; }
.kcol h4 { margin: 0 0 8px; font-size: 13px; display: flex; justify-content: space-between; }
.kcol h4 span { background: #dde3ea; border-radius: 10px; padding: 0 8px; font-size: 12px; }
.kcard { background: #fff; border: 1px solid #e6e6e6; border-radius: 6px; padding: 8px; margin-bottom: 8px; cursor: pointer; }
.kcard:hover { border-color: #2e6da4; }
.kcard b { font-size: 13px; }
.kcard .km { color: #888; font-size: 12px; margin: 4px 0 6px; }
@media (max-width: 1100px) { .kanban { grid-template-columns: repeat(3, 1fr); } .grid2 { grid-template-columns: 1fr; } }
</style>
