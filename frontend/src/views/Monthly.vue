<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchMonthlyPackage, fetchMonthlyAnalysis, fetchMonthlyCalendar,
  generateMonthlyReport, fetchLlmCall, downloadMonthly,
  type MonthlyPackage, type AnalysisReport, type CalendarBoard, type LlmCallLog,
} from '@/api/monthly'

function money(v?: number | null) {
  if (v === null || v === undefined) return '—'
  const a = Math.abs(v)
  return a >= 10000 ? (v / 10000).toFixed(2) + '万' : '¥' + v.toLocaleString()
}

const period = ref<string | undefined>(undefined)
const periods = ref<string[]>([])
const pkg = ref<MonthlyPackage | null>(null)
const report = ref<AnalysisReport | null>(null)
const calendar = ref<CalendarBoard | null>(null)
const activeTab = ref('rentLedger')
const loading = ref(false)

const calStateType: Record<string, string> = { AUTO_DONE: 'success', PENDING_MANUAL: 'warning' }

async function loadAll() {
  loading.value = true
  try {
    pkg.value = await fetchMonthlyPackage(period.value)
    periods.value = pkg.value.periods
    period.value = pkg.value.period
    calendar.value = await fetchMonthlyCalendar(period.value)
    report.value = await fetchMonthlyAnalysis(period.value)
  } finally {
    loading.value = false
  }
}

async function onPeriodChange() {
  await loadAll()
}

async function regenerate() {
  report.value = await fetchMonthlyAnalysis(period.value, true)
  ElMessage.success('已重新起草综述')
}

async function persist() {
  const r = await generateMonthlyReport(period.value)
  ElMessage.success('报表包快照已落库 #' + r.id + '(' + r.calendarStatus + ')')
  calendar.value = await fetchMonthlyCalendar(period.value)
}

// 🔬 透明四件套
const llmDialog = ref(false)
const llm = ref<LlmCallLog | null>(null)
async function openLlm() {
  if (!report.value?.llmCallId) return
  llm.value = await fetchLlmCall(report.value.llmCallId)
  llmDialog.value = true
}

onMounted(loadAll)
</script>

<template>
  <div v-loading="loading" class="monthly">
    <!-- 顶部:期切换 + 导出 -->
    <el-card shadow="never" class="bar">
      <div class="bar-inner">
        <div>
          <span class="lbl">账期</span>
          <el-select v-model="period" style="width: 140px" @change="onPeriodChange">
            <el-option v-for="p in periods" :key="p" :label="p" :value="p" />
          </el-select>
          <el-tag v-if="pkg?.locked" type="info" style="margin-left: 8px">ops 已锁账</el-tag>
        </div>
        <div class="actions">
          <el-button @click="persist">生成/落库快照</el-button>
          <el-button type="success" @click="downloadMonthly(period, 'xlsx')">导出 Excel(六件套)</el-button>
          <el-button type="primary" @click="downloadMonthly(period, 'docx')">导出 Word(七节报告)</el-button>
        </div>
      </div>
    </el-card>

    <!-- 财务日历看板 -->
    <el-card shadow="never" class="sec">
      <template #header>
        <b>财务月度工作日历</b>
        <el-tag style="margin-left: 8px">{{ calendar?.status }}</el-tag>
        <el-tag v-if="calendar?.pendingCount" type="warning" style="margin-left: 6px">
          {{ calendar?.pendingCount }} 项待人工
        </el-tag>
      </template>
      <div class="calendar">
        <div v-for="d in calendar?.days || []" :key="d.day" class="cal-day">
          <div class="cal-head">
            <span class="cal-num">{{ d.day }} 日</span>
            <el-tag size="small" :type="calStateType[d.state] || 'info'">
              {{ d.state === 'AUTO_DONE' ? '自动完成' : '待人工' }}
            </el-tag>
          </div>
          <div class="cal-fin">{{ d.financeAction }}</div>
          <div class="cal-auto">系统:{{ d.systemAuto }}</div>
          <div class="cal-note">{{ d.note }}</div>
        </div>
      </div>
    </el-card>

    <!-- 六件套 Tab -->
    <el-card shadow="never" class="sec">
      <template #header><b>月度报表包 · 六件套</b></template>
      <el-tabs v-model="activeTab">
        <!-- ① 收租台账 -->
        <el-tab-pane label="① 收租台账" name="rentLedger">
          <div class="kpis" v-if="pkg">
            <div class="kpi"><span>应收</span><b>{{ money(pkg.rentLedger.totalReceivable) }}</b></div>
            <div class="kpi"><span>已收</span><b>{{ money(pkg.rentLedger.totalReceived) }}</b></div>
            <div class="kpi"><span>待收</span><b>{{ money(pkg.rentLedger.totalOutstanding) }}</b></div>
            <div class="kpi"><span>回款率</span><b>{{ pkg.rentLedger.collectionRate }}%</b></div>
            <div class="kpi"><span>逾期</span><b>{{ pkg.rentLedger.overdueCount }} 单</b></div>
          </div>
          <el-table :data="pkg?.rentLedger.rows || []" size="small" border>
            <el-table-column prop="billNo" label="收租单号" width="200" />
            <el-table-column prop="contractNo" label="合同" width="90" />
            <el-table-column prop="periodNo" label="期次" width="60" />
            <el-table-column prop="dueDate" label="到期日" width="110" />
            <el-table-column label="应收"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>
            <el-table-column label="已收"><template #default="s">{{ money(s.row.receivedAmount) }}</template></el-table-column>
            <el-table-column prop="status" label="状态" width="90" />
            <el-table-column label="逾期" width="70">
              <template #default="s"><el-tag v-if="s.row.overdue" type="danger" size="small">逾期</el-tag></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ② 利润表 -->
        <el-tab-pane label="② 利润表(ops)" name="profit">
          <el-table :data="pkg?.profit.rows || []" size="small" border>
            <el-table-column label="项目"><template #default="s">
              <b v-if="s.row.subtotal">{{ s.row.label }}</b><span v-else>{{ s.row.label }}</span>
            </template></el-table-column>
            <el-table-column label="金额(对经营利润贡献)"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>
            <el-table-column prop="note" label="口径说明" />
          </el-table>
        </el-tab-pane>

        <!-- ③ 现金流水 -->
        <el-tab-pane label="③ 现金流水" name="cashflow">
          <div class="kpis" v-if="pkg">
            <div class="kpi"><span>现金流入</span><b>{{ money(pkg.cashflow.inflow) }}</b></div>
            <div class="kpi"><span>现金流出</span><b>{{ money(pkg.cashflow.outflow) }}</b></div>
            <div class="kpi"><span>净流入</span><b>{{ money(pkg.cashflow.net) }}</b></div>
            <div class="kpi"><span>净头寸</span><b>{{ money(pkg.cashflow.netPosition) }}</b></div>
            <div class="kpi"><span>供应商账期</span><b>{{ money(pkg.cashflow.supplierCredit) }}</b></div>
          </div>
        </el-tab-pane>

        <!-- ④ 往来 -->
        <el-tab-pane label="④ 往来" name="due">
          <div class="kpis" v-if="pkg">
            <div class="kpi"><span>应收未收</span><b>{{ money(pkg.dueSheet.receivableTotal) }}</b></div>
            <div class="kpi"><span>应付待付</span><b>{{ money(pkg.dueSheet.payableTotal) }}</b></div>
          </div>
          <el-table :data="pkg?.dueSheet.payables || []" size="small" border>
            <el-table-column prop="stage" label="阶段" width="90" />
            <el-table-column prop="dueDate" label="到期日" width="120" />
            <el-table-column label="金额"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>
            <el-table-column label="逾期" width="70">
              <template #default="s"><el-tag v-if="s.row.overdue" type="danger" size="small">逾期</el-tag></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ⑤ 资产快照 -->
        <el-tab-pane label="⑤ 资产快照" name="asset">
          <div class="kpis" v-if="pkg">
            <div class="kpi"><span>在册</span><b>{{ pkg.asset.total }} 台</b></div>
            <div class="kpi"><span>在租</span><b>{{ pkg.asset.rentedCount }} 台</b></div>
            <div class="kpi"><span>闲置</span><b>{{ pkg.asset.idleCount }} 台</b></div>
            <div class="kpi"><span>待处置</span><b>{{ pkg.asset.pendingDisposal }} 台</b></div>
            <div class="kpi"><span>在租率</span><b>{{ pkg.asset.rentedRatio }}%</b></div>
            <div class="kpi"><span>账面净值</span><b>{{ money(pkg.asset.bookValueTotal) }}</b></div>
          </div>
          <el-table :data="pkg?.asset.byStatus || []" size="small" border>
            <el-table-column prop="status" label="状态" />
            <el-table-column prop="count" label="台数" />
          </el-table>
        </el-tab-pane>

        <!-- ⑥ 分配表 🔒 -->
        <el-tab-pane label="⑥ 分配表 🔒" name="distribution">
          <el-alert v-if="pkg && !pkg.distributionVisible" :title="pkg.distributionMaskNote" type="info" :closable="false" />
          <template v-else-if="pkg?.distribution?.present">
            <div class="kpis">
              <div class="kpi"><span>可分配</span><b>{{ money(pkg.distribution.distributable) }}</b></div>
              <div class="kpi"><span>管理费</span><b>{{ money(pkg.distribution.mgmtFee) }}</b></div>
              <div class="kpi"><span>现金50%</span><b>{{ money(pkg.distribution.cash50) }}</b></div>
              <div class="kpi"><span>滚存50%</span><b>{{ money(pkg.distribution.roll50) }}</b></div>
            </div>
            <el-tag size="small" style="margin-bottom: 8px">{{ pkg.distribution.scopeNote }}</el-tag>
            <el-table :data="pkg.distribution.shares" size="small" border>
              <el-table-column prop="name" label="出资人" />
              <el-table-column prop="role" label="角色" width="70" />
              <el-table-column label="现金份额"><template #default="s">{{ money(s.row.cashShare) }}</template></el-table-column>
              <el-table-column label="滚存份额"><template #default="s">{{ money(s.row.rollShare) }}</template></el-table-column>
              <el-table-column label="管理费"><template #default="s">{{ money(s.row.mgmtFee) }}</template></el-table-column>
              <el-table-column label="本期收益"><template #default="s">{{ money(s.row.totalGain) }}</template></el-table-column>
            </el-table>
          </template>
          <el-empty v-else description="本期暂无生效分配单" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 第七件:经营分析报告七节 -->
    <el-card shadow="never" class="sec">
      <template #header>
        <b>第七件 ·《月度经营分析报告》七节</b>
        <el-tag v-if="report?.mock" type="warning" size="small" style="margin-left: 8px">MOCK 占位</el-tag>
        <el-button link type="primary" style="margin-left: 8px" @click="openLlm">🔬 AI 过程</el-button>
        <el-button link style="margin-left: 4px" @click="regenerate">重新起草</el-button>
      </template>
      <el-alert :title="report?.aiText" type="success" :closable="false" style="margin-bottom: 12px" />
      <div v-for="s in report?.sections || []" :key="s.key" class="section">
        <div class="sec-title">{{ s.title }}</div>
        <div class="sec-narr">{{ s.narrative }}</div>
        <el-table :data="s.dataPoints" size="small" border>
          <el-table-column prop="label" label="指标" width="180" />
          <el-table-column prop="value" label="数值" />
          <el-table-column prop="source" label="数据溯源" width="220" />
        </el-table>
      </div>
    </el-card>

    <!-- 🔬 透明四件套 -->
    <el-dialog v-model="llmDialog" title="🔬 AI 透明四件套" width="720px">
      <el-descriptions :column="2" border v-if="llm">
        <el-descriptions-item label="场景">{{ llm.scene }}</el-descriptions-item>
        <el-descriptions-item label="模型">{{ llm.model }}</el-descriptions-item>
        <el-descriptions-item label="置信分">{{ llm.confidence }}({{ llm.confidenceSource }})</el-descriptions-item>
        <el-descriptions-item label="状态">{{ llm.callStatus }}</el-descriptions-item>
      </el-descriptions>
      <el-tabs v-if="llm" style="margin-top: 10px">
        <el-tab-pane label="推理过程"><pre class="raw">{{ llm.reasoning }}</pre></el-tab-pane>
        <el-tab-pane label="完整输出"><pre class="raw">{{ llm.outputText }}</pre></el-tab-pane>
        <el-tab-pane label="原始数据(脱敏)"><pre class="raw">{{ llm.inputDigest }}</pre></el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>

<style scoped>
.monthly { display: flex; flex-direction: column; gap: 12px; }
.bar-inner { display: flex; align-items: center; justify-content: space-between; }
.lbl { margin-right: 8px; color: #666; }
.calendar { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.cal-day { border: 1px solid #ebeef5; border-radius: 8px; padding: 12px; background: #fafafa; }
.cal-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.cal-num { font-weight: 700; font-size: 15px; }
.cal-fin { font-size: 13px; margin-bottom: 4px; }
.cal-auto { font-size: 12px; color: #888; margin-bottom: 4px; }
.cal-note { font-size: 12px; color: #409eff; }
.kpis { display: flex; gap: 20px; margin-bottom: 12px; flex-wrap: wrap; }
.kpi { display: flex; flex-direction: column; }
.kpi span { font-size: 12px; color: #999; }
.kpi b { font-size: 18px; }
.section { margin-bottom: 16px; }
.sec-title { font-weight: 700; margin-bottom: 4px; }
.sec-narr { font-size: 13px; color: #555; margin-bottom: 8px; }
.raw { white-space: pre-wrap; word-break: break-all; font-size: 12px; max-height: 320px; overflow: auto; }
</style>
