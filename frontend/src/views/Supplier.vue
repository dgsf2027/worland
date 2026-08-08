<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchSupplierPool, fetchSupplierDetail, fetchDependencyAlert, retireSupplier,
  type SupplierPoolItem, type SupplierDetail, type DependencyAlert,
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
          <el-table-column label="供应商" min-width="130">
            <template #default="{ row }">
              <a class="lnk" @click="openDetail(row.id)">{{ row.name }}</a>
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
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="row.status !== '淘汰'" link type="danger" size="small" @click="onRetire(row)">淘汰</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="mini">配件级可比价；供应商行可「淘汰/停用（留痕）」；每品类需 ≥2 家防单一依赖</div>
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
          </h3>

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
@media (max-width: 900px) { .grid2 { grid-template-columns: 1fr; } }
</style>
