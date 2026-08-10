<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { biApi, type MatrixResp, type AgingResp, type TrendResp } from '@/api/bi'
import {
  pdcaApi, type BoardResp, type ItemRow, type MetricDefRow, type ItemSaveReq,
} from '@/api/pdca'

// ========================== BI 多维矩阵 ==========================
const occDim = ref('category')
const occ = ref<MatrixResp | null>(null)
const wr = ref<MatrixResp | null>(null)
const wrDim = ref('customer')
const aging = ref<AgingResp | null>(null)
const turn = ref<MatrixResp | null>(null)
const trend = ref<TrendResp | null>(null)

function pctNum(v?: number | null) {
  if (v === null || v === undefined) return '—'
  return v.toFixed(2) + '%'
}
function money(v?: number | null) {
  if (v === null || v === undefined) return '—'
  const a = Math.abs(v)
  return a >= 10000 ? (v / 10000).toFixed(2) + '万' : '¥' + v.toLocaleString()
}

async function loadOcc() { occ.value = await biApi.occupancy(occDim.value) }
async function loadWr() { wr.value = await biApi.weightedReturn(wrDim.value) }
async function loadBi() {
  await Promise.all([
    loadOcc(), loadWr(),
    biApi.receivableAging().then((r) => (aging.value = r)),
    biApi.assetTurnover('category').then((r) => (turn.value = r)),
    biApi.trend('collection', 6).then((r) => (trend.value = r)),
  ])
}
const trendMax = () => Math.max(1, ...(trend.value?.points.map((p) => p.value) || [1]))

// ========================== PDCA 看板 ==========================
const board = ref<BoardResp | null>(null)
const items = ref<ItemRow[]>([])
const metrics = ref<MetricDefRow[]>([])
const loadingBoard = ref(false)

async function loadBoard(force = false) {
  loadingBoard.value = true
  try {
    board.value = await pdcaApi.board(force)
  } finally {
    loadingBoard.value = false
  }
}
async function loadItems() { items.value = await pdcaApi.items() }
async function loadMetrics() { metrics.value = await pdcaApi.metrics() }

function lightColor(l: string) {
  return l === 'green' ? '#67c23a' : l === 'red' ? '#f56c6c' : '#909399'
}
function lightText(l: string) {
  return l === 'green' ? '达标' : l === 'red' ? '未达' : '待判定'
}
function showVal(ind: { value: number | null; unit: string }) {
  if (ind.value === null || ind.value === undefined) return '—'
  if (ind.unit === '%') return (ind.value * 100).toFixed(1) + '%'
  return ind.value.toFixed(1) + ind.unit
}
function showTarget(ind: { target: number | null; unit: string; compareOp: string }) {
  if (ind.target === null || ind.target === undefined) return '—'
  const t = ind.unit === '%' ? (ind.target * 100).toFixed(0) + '%' : ind.target.toFixed(0) + ind.unit
  return ind.compareOp + ' ' + t
}

// ---- 登记改进项 ----
const dialog = ref(false)
const form = ref<ItemSaveReq>({ metricScene: '', issue: '', action: '' })
function openCreate(metricKey?: string, scene?: string) {
  const def = metrics.value.find((m) => m.key === metricKey)
  form.value = {
    metricScene: scene || def?.label || '',
    issue: '',
    action: '',
    metricKey: metricKey,
    targetValue: def?.defaultTarget,
    compareOp: def?.compareOp,
    recheckDate: new Date(Date.now() + 30 * 864e5).toISOString().slice(0, 10),
    ownerRole: '',
  }
  dialog.value = true
}
async function submitCreate() {
  if (!form.value.metricScene || !form.value.issue || !form.value.action) {
    ElMessage.warning('环节/问题/措施必填')
    return
  }
  await pdcaApi.create(form.value)
  ElMessage.success('改进项已登记')
  dialog.value = false
  await Promise.all([loadItems(), loadBoard(false)])
}
async function recheck(row: ItemRow) {
  const r = await pdcaApi.recheck(row.id)
  ElMessage({ type: r.verifyResult === '通过' ? 'success' : r.verifyResult === '未达' ? 'warning' : 'info', message: `回查:${r.verifyResult} → ${r.newStatus}` })
  await loadItems()
}
async function recheckDue() {
  const r = await pdcaApi.recheckDue()
  ElMessage.success(`到期回查 ${r.total} 项:通过 ${r.passed} · 未达升级 ${r.failed} · 人工 ${r.manual}`)
  await loadItems()
}
async function closeItem(row: ItemRow) {
  const { value } = await ElMessageBox.prompt('关闭原因', '手动关闭改进项', { inputType: 'textarea' })
  await pdcaApi.close(row.id, value)
  ElMessage.success('已关闭')
  await loadItems()
}
const statusTag: Record<string, string> = { 进行中: '', 验证通过: 'success', 未见效升级: 'danger', 已关闭: 'info' }

onMounted(() => {
  loadBi()
  loadBoard(false)
  loadItems()
  loadMetrics()
})
</script>

<template>
  <div class="bi-pdca">
    <!-- ============ BI 多维矩阵 ============ -->
    <el-card shadow="never" class="sec">
      <template #header>
        <b>BI · 多维矩阵</b>
        <span class="hint">只读聚合(复用各模块规范列·不重算)</span>
      </template>
      <div class="grid4">
        <!-- 在租率 -->
        <div class="mcard">
          <div class="mtop">
            <span class="mlabel">在租率</span>
            <el-radio-group v-model="occDim" size="small" @change="loadOcc">
              <el-radio-button label="category">品类</el-radio-button>
              <el-radio-button label="supplier">供应商</el-radio-button>
            </el-radio-group>
          </div>
          <div class="mbig">{{ pctNum(occ?.overall) }}</div>
          <div class="mdesc">{{ occ?.overallDesc }}</div>
          <div v-for="r in occ?.rows" :key="r.dimKey" class="mrow">
            <span class="dk">{{ r.dimKey }}</span>
            <el-progress :percentage="Math.min(100, r.value)" :stroke-width="10" :show-text="false" style="flex:1;margin:0 8px" />
            <span class="dv">{{ pctNum(r.value) }} <em>({{ r.numerator }}/{{ r.denominator }})</em></span>
          </div>
        </div>
        <!-- 加权回报 -->
        <div class="mcard">
          <div class="mtop">
            <span class="mlabel">加权回报</span>
            <el-radio-group v-model="wrDim" size="small" @change="loadWr">
              <el-radio-button label="customer">客户</el-radio-button>
              <el-radio-button label="nature">性质</el-radio-button>
            </el-radio-group>
          </div>
          <div class="mbig">{{ pctNum(wr?.overall) }}</div>
          <div class="mdesc">{{ wr?.overallDesc }}</div>
          <div v-for="r in wr?.rows" :key="r.dimKey" class="mrow">
            <span class="dk">{{ r.dimKey }}</span>
            <span class="dv">{{ pctNum(r.value) }} <em>权重{{ money(r.weight) }}</em></span>
          </div>
        </div>
        <!-- 资产周转 -->
        <div class="mcard">
          <div class="mtop"><span class="mlabel">资产周转(投放率)</span></div>
          <div class="mbig">{{ pctNum(turn?.overall) }}</div>
          <div class="mdesc">{{ turn?.overallDesc }}</div>
          <div v-for="r in turn?.rows" :key="r.dimKey" class="mrow">
            <span class="dk">{{ r.dimKey }}</span>
            <el-progress :percentage="Math.min(100, r.value)" :stroke-width="10" :show-text="false" color="#e6a23c" style="flex:1;margin:0 8px" />
            <span class="dv">{{ pctNum(r.value) }} <em>({{ r.numerator }}/{{ r.denominator }})</em></span>
          </div>
        </div>
        <!-- 应收账龄 -->
        <div class="mcard">
          <div class="mtop"><span class="mlabel">应收账龄</span></div>
          <div class="mbig">{{ money(aging?.totalOverdue) }}</div>
          <div class="mdesc">未回款(正常单)按到期账龄分桶</div>
          <div v-for="b in aging?.buckets" :key="b.bucket" class="mrow">
            <span class="dk">{{ b.bucket }} 天</span>
            <span class="dv" :class="{ danger: b.bucket === '90+' && b.amount > 0 }">{{ money(b.amount) }} <em>{{ b.count }} 笔</em></span>
          </div>
        </div>
      </div>
      <!-- 趋势 -->
      <div class="trend" v-if="trend">
        <div class="tlabel">{{ trend.metricLabel }}(近 6 月)</div>
        <div class="bars">
          <div v-for="p in trend.points" :key="p.period" class="bar">
            <div class="col" :style="{ height: (p.value / trendMax() * 80 + 2) + 'px' }" :title="money(p.value)"></div>
            <div class="pd">{{ p.period.slice(5) }}</div>
            <div class="pv">{{ money(p.value) }}</div>
          </div>
        </div>
      </div>
    </el-card>

    <!-- ============ PDCA 看板 ============ -->
    <el-card shadow="never" class="sec">
      <template #header>
        <b>PDCA · 改进循环</b>
        <span class="hint">每指标红绿灯 → 改进措施 → 到期回查</span>
        <div style="float:right">
          <el-tag v-if="board" type="danger" effect="plain">红 {{ board.redCount }}</el-tag>
          <el-tag v-if="board" type="success" effect="plain" style="margin:0 8px">绿 {{ board.greenCount }}</el-tag>
          <el-button size="small" @click="loadBoard(true)">重算综述</el-button>
          <el-button size="small" type="warning" @click="recheckDue">到期回查</el-button>
        </div>
      </template>

      <div class="lights" v-loading="loadingBoard">
        <div v-for="ind in board?.indicators" :key="ind.metricKey" class="light">
          <div class="ldot" :style="{ background: lightColor(ind.light) }"></div>
          <div class="lmain">
            <div class="lname">{{ ind.label }}</div>
            <div class="lval">{{ showVal(ind) }} <span class="lt">目标 {{ showTarget(ind) }}</span></div>
          </div>
          <div class="lstate" :style="{ color: lightColor(ind.light) }">{{ lightText(ind.light) }}</div>
          <el-button v-if="ind.light === 'red'" size="small" type="primary" plain @click="openCreate(ind.metricKey, ind.label)">登记改进</el-button>
        </div>
      </div>

      <!-- AI 综述 -->
      <div class="ai" v-if="board?.aiText">
        <el-tag size="small" type="info">🔬 AI 综述</el-tag>
        <el-tag v-if="board.mock" size="small">{{ board.model }}</el-tag>
        <el-tag v-if="board.cacheHit" size="small" type="warning">缓存命中</el-tag>
        <span class="aitext">{{ board.aiText }}</span>
      </div>

      <!-- 改进项清单 -->
      <div class="items-head">
        <b>改进项清单</b>
        <el-button size="small" type="primary" @click="openCreate()">+ 手动登记</el-button>
      </div>
      <el-table :data="items" size="small" border>
        <el-table-column prop="no" label="改进项号" width="150" />
        <el-table-column prop="metricScene" label="环节" width="90" />
        <el-table-column prop="issue" label="问题" min-width="160" show-overflow-tooltip />
        <el-table-column prop="action" label="改进措施" min-width="160" show-overflow-tooltip />
        <el-table-column label="验证指标" width="150">
          <template #default="{ row }">
            <span v-if="row.metricKey">{{ row.metricKey }} {{ row.compareOp }} {{ row.targetValue }}</span>
            <el-tag v-else size="small" type="info">需人工判定</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="回查日" width="120">
          <template #default="{ row }">
            <span :class="{ due: row.dueOrOverdue }">{{ row.recheckDate }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="ownerRole" label="负责" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag[row.status]">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === '进行中' || row.status === '未见效升级'" size="small" link type="primary" @click="recheck(row)">回查</el-button>
            <el-button v-if="row.status !== '已关闭' && row.status !== '验证通过'" size="small" link type="info" @click="closeItem(row)">关闭</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 登记弹窗 -->
    <el-dialog v-model="dialog" title="登记改进项" width="560px">
      <el-form :model="form" label-width="92px">
        <el-form-item label="来源环节" required>
          <el-input v-model="form.metricScene" placeholder="在租率/加权回报/应收账龄/资产周转/回款率" />
        </el-form-item>
        <el-form-item label="问题描述" required>
          <el-input v-model="form.issue" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="改进措施" required>
          <el-input v-model="form.action" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="验证指标">
          <el-select v-model="form.metricKey" clearable placeholder="留空=需人工判定" style="width:100%"
            @change="() => { const d = metrics.find(m => m.key === form.metricKey); if (d) { form.targetValue = d.defaultTarget; form.compareOp = d.compareOp } }">
            <el-option v-for="m in metrics" :key="m.key" :label="`${m.label}(当前 ${m.currentValue ?? '—'})`" :value="m.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标值" v-if="form.metricKey">
          <el-input v-model.number="form.targetValue" style="width:140px" />
          <span class="hint">方向 {{ form.compareOp }}</span>
        </el-form-item>
        <el-form-item label="回查日">
          <el-date-picker v-model="form.recheckDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="负责角色">
          <el-select v-model="form.ownerRole" clearable style="width:160px">
            <el-option label="老板" value="老板" />
            <el-option label="财务" value="财务" />
            <el-option label="供应链" value="供应链" />
            <el-option label="业务" value="业务" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bi-pdca { display: flex; flex-direction: column; gap: 16px; }
.sec .hint { color: #909399; font-size: 12px; margin-left: 10px; }
.grid4 { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; }
.mcard { border: 1px solid #ebeef5; border-radius: 8px; padding: 12px 14px; }
.mtop { display: flex; justify-content: space-between; align-items: center; }
.mlabel { font-weight: 600; }
.mbig { font-size: 30px; font-weight: 700; color: #303133; margin: 6px 0 2px; }
.mdesc { color: #909399; font-size: 12px; margin-bottom: 8px; }
.mrow { display: flex; align-items: center; font-size: 13px; padding: 3px 0; }
.dk { width: 84px; color: #606266; }
.dv { margin-left: auto; }
.dv em { color: #c0c4cc; font-style: normal; font-size: 11px; }
.dv.danger { color: #f56c6c; font-weight: 600; }
.trend { margin-top: 14px; border-top: 1px dashed #ebeef5; padding-top: 10px; }
.tlabel { font-size: 13px; color: #606266; margin-bottom: 6px; }
.bars { display: flex; gap: 18px; align-items: flex-end; height: 120px; padding: 0 10px; }
.bar { display: flex; flex-direction: column; align-items: center; }
.col { width: 34px; background: linear-gradient(#66b1ff, #409eff); border-radius: 4px 4px 0 0; }
.pd { font-size: 12px; color: #909399; margin-top: 4px; }
.pv { font-size: 11px; color: #c0c4cc; }
.lights { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; }
.light { display: flex; align-items: center; gap: 10px; border: 1px solid #ebeef5; border-radius: 8px; padding: 10px 12px; }
.ldot { width: 14px; height: 14px; border-radius: 50%; flex-shrink: 0; }
.lmain { flex: 1; }
.lname { font-weight: 600; }
.lval { font-size: 13px; color: #606266; }
.lt { color: #c0c4cc; margin-left: 6px; }
.lstate { font-weight: 700; }
.ai { margin: 14px 0; padding: 10px 12px; background: #f4f4f5; border-radius: 8px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.aitext { color: #606266; font-size: 13px; }
.items-head { display: flex; justify-content: space-between; align-items: center; margin: 14px 0 8px; }
.due { color: #f56c6c; font-weight: 600; }
</style>
