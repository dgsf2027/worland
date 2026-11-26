<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteNotifyChannel,
  fetchAutomationLogs,
  fetchAutomationRules,
  fetchBankStatements,
  fetchCollectionNotices,
  fetchNotifyChannels,
  fetchUnpaidBillOptions,
  ignoreBankStatement,
  importBankStatementFile,
  reassignBankStatement,
  reconcileBankStatements,
  runAutomationRule,
  saveNotifyChannel,
  submitManualBankStatements,
  testNotifyChannel,
  updateAutomationRule,
  type AutomationLog,
  type AutomationRule,
  type BankStatement,
  type CollectionNotice,
  type NotifyChannel,
  type RunRuleResult,
  type UnpaidBillOption,
} from '@/api/automation'
import { sessionRole } from '@/utils/session'

const canManage = computed(() => ['老板', '财务'].includes(sessionRole.value || ''))
const activeTab = ref('rules')

// ============ Tab 1: 自动化规则总控台 ============
const rulesLoading = ref(false)
const rules = ref<AutomationRule[]>([])
const runningKey = ref<string | null>(null)
const runPreviewVisible = ref(false)
const runPreview = ref<RunRuleResult | null>(null)

async function loadRules() {
  rulesLoading.value = true
  try {
    rules.value = await fetchAutomationRules()
  } finally {
    rulesLoading.value = false
  }
}

async function toggleRule(row: AutomationRule, val: boolean) {
  const updated = await updateAutomationRule(row.ruleKey, { enabled: val })
  Object.assign(row, updated)
  ElMessage.success(`${row.ruleName} 已${val ? '开启' : '关闭'}`)
}

const editRuleVisible = ref(false)
const editRuleSaving = ref(false)
const editRuleForm = reactive<{
  ruleKey: string
  ruleName: string
  leadDays: number | undefined
  thresholdAmount: number | undefined
  targetRole: string
}>({
  ruleKey: '',
  ruleName: '',
  leadDays: undefined,
  thresholdAmount: undefined,
  targetRole: '老板',
})
function openEditRule(r: AutomationRule) {
  editRuleForm.ruleKey = r.ruleKey
  editRuleForm.ruleName = r.ruleName
  editRuleForm.leadDays = r.leadDays
  editRuleForm.thresholdAmount = r.thresholdAmount
  editRuleForm.targetRole = r.targetRole || '老板'
  editRuleVisible.value = true
}
async function submitEditRule() {
  editRuleSaving.value = true
  try {
    await updateAutomationRule(editRuleForm.ruleKey, {
      leadDays: editRuleForm.leadDays ?? null,
      thresholdAmount: editRuleForm.thresholdAmount ?? null,
      targetRole: editRuleForm.targetRole,
    })
    editRuleVisible.value = false
    ElMessage.success('规则参数已更新')
    await loadRules()
  } finally {
    editRuleSaving.value = false
  }
}

async function handleRunNow(r: AutomationRule) {
  runningKey.value = r.ruleKey
  try {
    const res = await runAutomationRule(r.ruleKey)
    runPreview.value = res
    runPreviewVisible.value = true
    await Promise.all([loadRules(), loadLogs()])
  } finally {
    runningKey.value = null
  }
}

// ============ Tab 2: 银行流水智能对账与一键核销 ============
const stmtLoading = ref(false)
const stmtFilter = ref('')
const statements = ref<BankStatement[]>([])
const unpaidBills = ref<UnpaidBillOption[]>([])
const selectedStmtIds = ref<number[]>([])
const importing = ref(false)
const reconciling = ref(false)

async function loadStatements() {
  stmtLoading.value = true
  try {
    const [st, ub] = await Promise.all([
      fetchBankStatements(stmtFilter.value || undefined),
      fetchUnpaidBillOptions(),
    ])
    statements.value = st
    unpaidBills.value = ub
  } finally {
    stmtLoading.value = false
  }
}

function handleStmtSelection(rows: BankStatement[]) {
  selectedStmtIds.value = rows.filter((r) => r.matchStatus !== '已核销' && r.matchedBizId).map((r) => r.id)
}

async function handleUploadStatement(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  importing.value = true
  try {
    const res = await importBankStatementFile(file)
    ElMessage.success(
      `导入完成（批次 ${res.batchNo}）：导入 ${res.importedRows} 条，去重 ${res.duplicateRows} 条，高置信度匹配 ${res.matchedRows} 条，待确认 ${res.needConfirmRows} 条`,
    )
    await loadStatements()
  } finally {
    importing.value = false
    input.value = ''
  }
}

const manualDlg = ref(false)
const manualSaving = ref(false)
const manualForm = reactive({
  txDate: new Date().toISOString().slice(0, 10),
  counterpartyName: '',
  amount: undefined as number | undefined,
  bankSerial: '',
  memo: '',
})
function openManualStmt() {
  manualForm.txDate = new Date().toISOString().slice(0, 10)
  manualForm.counterpartyName = ''
  manualForm.amount = undefined
  manualForm.bankSerial = ''
  manualForm.memo = ''
  manualDlg.value = true
}
async function submitManualStmt() {
  if (!manualForm.amount || manualForm.amount <= 0) {
    ElMessage.warning('收款金额必填且大于 0')
    return
  }
  manualSaving.value = true
  try {
    const res = await submitManualBankStatements([
      {
        txDate: manualForm.txDate,
        counterpartyName: manualForm.counterpartyName,
        amount: manualForm.amount,
        direction: 'IN',
        bankSerial: manualForm.bankSerial || undefined,
        memo: manualForm.memo,
      },
    ])
    ElMessage.success(`已录入并智能匹配（高置信度 ${res.matchedRows} 条，待确认 ${res.needConfirmRows} 条）`)
    manualDlg.value = false
    await loadStatements()
  } finally {
    manualSaving.value = false
  }
}

function downloadCsvTemplate() {
  const csv = '\uFEFF交易日期,对方户名,收款金额,方向,银行流水号,摘要备注\n2026-11-15,示例客户有限公司,4736.69,收入,BANK202611150001,支付货架租金 CT-2026-001\n'
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = '银行对账单导入模板.csv'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10000)
}

async function handleReassign(row: BankStatement, billId: number | null) {
  await reassignBankStatement(row.id, billId)
  ElMessage.success('已更新匹配收租单')
  await loadStatements()
}

async function handleIgnore(row: BankStatement) {
  await ignoreBankStatement(row.id)
  ElMessage.success('已标记忽略')
  await loadStatements()
}

async function handleBatchReconcile(onlyHighConfidence: boolean) {
  const ids = onlyHighConfidence
    ? statements.value
        .filter((s) => s.matchStatus === '已匹配' && s.confidence >= 90 && s.matchedBizId)
        .map((s) => s.id)
    : selectedStmtIds.value
  if (!ids.length) {
    ElMessage.warning(onlyHighConfidence ? '当前没有高置信度(≥90%)待核销流水' : '请先勾选已匹配收租单的流水')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认对 ${ids.length} 笔银行流水执行一键批量核销？系统将自动过账收入凭证、关闭对应逾期案，并启动 10 工作日 3% 销项开票倒计时。`,
      '一键批量核销',
      { type: 'warning', confirmButtonText: '确认批量核销' },
    )
  } catch {
    return
  }
  reconciling.value = true
  try {
    const res = await reconcileBankStatements(ids)
    ElMessage.success(`批量核销完成：成功 ${res.succeeded} 笔，失败 ${res.failed} 笔`)
    if (res.failed > 0 && res.messages.length) {
      ElMessageBox.alert(res.messages.join('\n'), '批量核销明细')
    }
    await loadStatements()
  } finally {
    reconciling.value = false
  }
}

// ============ Tab 3: 一键催租通知函 ============
const noticesLoading = ref(false)
const notices = ref<CollectionNotice[]>([])
const noticeLeadDays = ref(3)

async function loadNotices() {
  noticesLoading.value = true
  try {
    notices.value = await fetchCollectionNotices(noticeLeadDays.value)
  } finally {
    noticesLoading.value = false
  }
}

async function copyNotice(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制催款通知文案，可直接粘贴发客户微信/群')
  } catch {
    ElMessageBox.alert(text, '请手动复制文案')
  }
}

// ============ Tab 4: 外推通道 & 执行日志 ============
const channels = ref<NotifyChannel[]>([])
const logs = ref<AutomationLog[]>([])
const channelDlg = ref(false)
const channelSaving = ref(false)
const testingChannelId = ref<number | null>(null)
const channelForm = reactive<Record<string, any>>({
  id: undefined,
  name: '',
  channelType: 'wecom',
  webhookUrl: '',
  secret: '',
  targetRole: '老板',
  enabled: true,
})

async function loadChannels() {
  if (!canManage.value) return
  channels.value = await fetchNotifyChannels()
}

async function loadLogs() {
  logs.value = await fetchAutomationLogs({ limit: 80 })
}

function openCreateChannel() {
  Object.assign(channelForm, {
    id: undefined,
    name: '',
    channelType: 'wecom',
    webhookUrl: '',
    secret: '',
    targetRole: '老板',
    enabled: true,
  })
  channelDlg.value = true
}

function openEditChannel(ch: NotifyChannel) {
  Object.assign(channelForm, {
    id: ch.id,
    name: ch.name,
    channelType: ch.channelType,
    webhookUrl: ch.webhookUrl,
    secret: ch.secret || '',
    targetRole: ch.targetRole,
    enabled: ch.enabled === 1,
  })
  channelDlg.value = true
}

async function submitChannel() {
  if (!channelForm.name?.trim() || !channelForm.webhookUrl?.trim()) {
    ElMessage.warning('通道名称与 Webhook 地址必填')
    return
  }
  channelSaving.value = true
  try {
    await saveNotifyChannel(channelForm)
    channelDlg.value = false
    ElMessage.success('外推通道已保存')
    await loadChannels()
  } finally {
    channelSaving.value = false
  }
}

async function handleTestChannel(ch: NotifyChannel) {
  testingChannelId.value = ch.id
  try {
    const msg = await testNotifyChannel(ch.id)
    ElMessage.success(msg)
    await loadChannels()
  } finally {
    testingChannelId.value = null
  }
}

async function handleDeleteChannel(ch: NotifyChannel) {
  await ElMessageBox.confirm(`确认删除推送通道「${ch.name}」？`, '删除通道', { type: 'warning' })
  await deleteNotifyChannel(ch.id)
  ElMessage.success('已删除')
  await loadChannels()
}

function fmtMoney(v?: number | null) {
  if (v == null) return '—'
  return '¥' + Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function levelTag(level: string) {
  if (level.includes('T+15')) return 'danger'
  if (level.includes('T+7')) return 'warning'
  if (level.includes('T+1')) return 'primary'
  return 'info'
}
function matchTag(status: string) {
  if (status === '已核销') return 'success'
  if (status === '已匹配') return 'primary'
  if (status === '待确认') return 'warning'
  return 'info'
}
function channelLabel(t: string) {
  return ({ wecom: '企业微信机器人', dingtalk: '钉钉机器人', feishu: '飞书机器人', generic: '通用Webhook' } as any)[t] || t
}

onMounted(async () => {
  await Promise.all([loadRules(), loadNotices(), loadChannels(), loadLogs(), canManage.value ? loadStatements() : Promise.resolve()])
})
</script>

<template>
  <div class="auto-page">
    <el-alert
      type="success"
      :closable="false"
      style="margin-bottom: 12px"
      title="耀石系统自动化引擎（一期+二期）：平时自动扫描/推送晨报、自动级联签约建项与1元留购、拖入银行对账单即可一键批量核销并记凭证启开票倒计时。"
    />

    <el-tabs v-model="activeTab" type="border-card">
      <!-- Tab 1: 自动化总控台 -->
      <el-tab-pane label="⚡ 自动化总控台（10条规则）" name="rules">
        <el-table :data="rules" v-loading="rulesLoading" border stripe size="small">
          <el-table-column prop="category" label="分类" width="95">
            <template #default="{ row }">
              <el-tag size="small" :type="row.category === '事件联动' ? 'warning' : row.category === '推送简报' ? 'success' : 'primary'" effect="plain">
                {{ row.category }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="规则名称与说明" min-width="260">
            <template #default="{ row }">
              <div style="font-weight: 600">{{ row.ruleName }}</div>
              <div class="sub">{{ row.description }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="scheduleDesc" label="触发频次" width="135" />
          <el-table-column label="参数配置" width="165">
            <template #default="{ row }">
              <div v-if="row.leadDays != null && row.leadDays > 0">提前窗口：T-{{ row.leadDays }} 天</div>
              <div v-if="row.thresholdAmount != null">阈值：{{ row.ruleKey === 'data_self_heal_scan' ? `±${row.thresholdAmount}%` : fmtMoney(row.thresholdAmount) }}</div>
              <div class="sub">触达：{{ row.targetRole || '老板' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="开关" width="75" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled === 1"
                :disabled="!canManage"
                size="small"
                @change="(v: any) => toggleRule(row, Boolean(v))"
              />
            </template>
          </el-table-column>
          <el-table-column label="最近运行结果" min-width="220">
            <template #default="{ row }">
              <template v-if="row.lastRunAt">
                <el-tag size="small" :type="row.lastStatus === 'SUCCESS' ? 'success' : row.lastStatus === 'FAILED' ? 'danger' : 'info'">
                  {{ row.lastStatus }}
                </el-tag>
                <span class="sub" style="margin-left: 6px">{{ row.lastRunAt }}</span>
                <div class="sub">{{ row.lastSummary }}</div>
              </template>
              <span v-else class="sub">尚未执行</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right" v-if="canManage">
            <template #default="{ row }">
              <el-button link type="primary" size="small" :loading="runningKey === row.ruleKey" @click="handleRunNow(row)">
                ▶ 试跑
              </el-button>
              <el-button link size="small" @click="openEditRule(row)">调参数</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 2: 银行流水智能自动对账 -->
      <el-tab-pane label="🏦 银行流水智能对账与核销" name="bank" v-if="canManage">
        <div class="bar">
          <div class="bar-left">
            <label class="upload-btn">
              <span>{{ importing ? '解析匹配中...' : '📥 上传网银流水（CSV / Excel）' }}</span>
              <input type="file" accept=".csv,.xls,.xlsx" style="display: none" :disabled="importing" @change="handleUploadStatement" />
            </label>
            <el-button size="small" @click="openManualStmt">+ 手工录入单笔试跑</el-button>
            <el-button size="small" link type="primary" @click="downloadCsvTemplate">下载标准CSV模板</el-button>
            <el-select v-model="stmtFilter" clearable placeholder="全部匹配状态" size="small" style="width: 135px" @change="loadStatements">
              <el-option label="已匹配(≥90%)" value="已匹配" />
              <el-option label="待确认" value="待确认" />
              <el-option label="未匹配" value="未匹配" />
              <el-option label="已核销" value="已核销" />
              <el-option label="已忽略" value="已忽略" />
            </el-select>
          </div>
          <div class="bar-right">
            <el-button size="small" type="success" :loading="reconciling" @click="handleBatchReconcile(true)">
              ⚡ 一键核销全部高置信度流水
            </el-button>
            <el-button size="small" type="primary" :loading="reconciling" :disabled="!selectedStmtIds.length" @click="handleBatchReconcile(false)">
              ✅ 核销已勾选 ({{ selectedStmtIds.length }})
            </el-button>
          </div>
        </div>

        <el-table :data="statements" v-loading="stmtLoading" border stripe size="small" @selection-change="handleStmtSelection">
          <el-table-column type="selection" width="42" :selectable="(r: BankStatement) => r.matchStatus !== '已核销' && !!r.matchedBizId" />
          <el-table-column prop="txDate" label="交易日" width="105" />
          <el-table-column label="对方户名 / 摘要" min-width="200">
            <template #default="{ row }">
              <div style="font-weight: 600">{{ row.counterpartyName || '—' }}</div>
              <div class="sub">{{ row.memo || '—' }} <span v-if="row.bankSerial">· 流水:{{ row.bankSerial }}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="115" align="right">
            <template #default="{ row }">
              <b :style="{ color: row.direction === 'IN' ? '#16a34a' : '#6b7280' }">
                {{ row.direction === 'IN' ? '+' : '-' }}{{ fmtMoney(row.amount) }}
              </b>
            </template>
          </el-table-column>
          <el-table-column label="智能匹配结果" min-width="240">
            <template #default="{ row }">
              <div style="display: flex; align-items: center; gap: 6px; flex-wrap: wrap">
                <el-tag size="small" :type="matchTag(row.matchStatus)">{{ row.matchStatus }}</el-tag>
                <el-tag v-if="row.confidence > 0" size="small" effect="plain" :type="row.confidence >= 90 ? 'success' : 'warning'">
                  置信度 {{ row.confidence }}%
                </el-tag>
                <span v-if="row.matchedBizNo" style="font-weight: 600">{{ row.matchedBizNo }}</span>
              </div>
              <div class="sub">{{ row.matchReason }}</div>
            </template>
          </el-table-column>
          <el-table-column label="人工指定/调整收租单" width="230">
            <template #default="{ row }">
              <el-select
                v-if="row.matchStatus !== '已核销' && row.direction === 'IN'"
                :model-value="row.matchedBizId"
                filterable
                clearable
                size="small"
                style="width: 100%"
                placeholder="指定目标收租单"
                @change="(v: any) => handleReassign(row, v || null)"
              >
                <el-option
                  v-for="b in unpaidBills"
                  :key="b.billId"
                  :label="`${b.billNo} · ${b.customerName} · 欠${fmtMoney(b.remainingAmount)}`"
                  :value="b.billId"
                />
              </el-select>
              <span v-else class="sub">{{ row.reconciledAt ? `${row.operatorName || ''} 于 ${row.reconciledAt}` : '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.matchStatus !== '已核销' && row.matchStatus !== '已忽略'"
                link
                type="info"
                size="small"
                @click="handleIgnore(row)"
              >
                忽略
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 3: 一键催租通知函 -->
      <el-tab-pane label="📣 阶梯催收与催款通知函" name="notices">
        <div class="bar">
          <div class="bar-left">
            <span>包含未来</span>
            <el-input-number v-model="noticeLeadDays" :min="0" :max="30" size="small" style="width: 90px" @change="loadNotices" />
            <span>天内到期及全部已逾期租金单</span>
          </div>
          <el-button size="small" @click="loadNotices">🔄 刷新</el-button>
        </div>

        <el-table :data="notices" v-loading="noticesLoading" border stripe size="small">
          <el-table-column label="催收等级" width="135">
            <template #default="{ row }">
              <el-tag size="small" :type="levelTag(row.level)">{{ row.level }}</el-tag>
              <div class="sub">{{ row.overdueDays > 0 ? `已逾期 ${row.overdueDays} 天` : `距到期 ${-row.overdueDays} 天` }}</div>
            </template>
          </el-table-column>
          <el-table-column label="客户 / 合同期次" width="200">
            <template #default="{ row }">
              <div style="font-weight: 600">{{ row.customerName }}</div>
              <div class="sub">{{ row.contractNo }} · 第 {{ row.periodNo }} 期（{{ row.dueDate }}）</div>
            </template>
          </el-table-column>
          <el-table-column label="欠付 / 滞纳金(估)" width="150" align="right">
            <template #default="{ row }">
              <div style="font-weight: 600; color: #dc2626">{{ fmtMoney(row.unpaidAmount) }}</div>
              <div class="sub" v-if="row.estimatedPenalty > 0">滞纳金 {{ fmtMoney(row.estimatedPenalty) }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="noticeText" label="自动生成标准催款通知文案" min-width="320" />
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" plain size="small" @click="copyNotice(row.noticeText)">📋 复制</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 4: 外推通道与执行日志 -->
      <el-tab-pane label="🔔 推送通道与执行日志" name="channels">
        <div v-if="canManage" style="margin-bottom: 18px">
          <div class="bar">
            <b>外推消息通道（兼容企业微信 / 钉钉 / 飞书群机器人 / 通用 Webhook，严禁推送成本给 LP）</b>
            <el-button type="primary" size="small" @click="openCreateChannel">+ 新增推送通道</el-button>
          </div>
          <el-table :data="channels" border size="small">
            <el-table-column prop="name" label="通道名称" width="170" />
            <el-table-column label="平台类型" width="140">
              <template #default="{ row }">{{ channelLabel(row.channelType) }}</template>
            </el-table-column>
            <el-table-column prop="targetRole" label="接收角色" width="95" />
            <el-table-column prop="webhookUrl" label="Webhook 地址" min-width="240" show-overflow-tooltip />
            <el-table-column label="最近推送状态" min-width="180">
              <template #default="{ row }">
                <span v-if="row.lastError" style="color: #dc2626">{{ row.lastError }}</span>
                <span v-else-if="row.lastSentAt" style="color: #16a34a">正常 · {{ row.lastSentAt }}</span>
                <span v-else class="sub">尚未推送</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="185" fixed="right">
              <template #default="{ row }">
                <el-button link type="success" size="small" :loading="testingChannelId === row.id" @click="handleTestChannel(row)">
                  🔔 测试
                </el-button>
                <el-button link type="primary" size="small" @click="openEditChannel(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="handleDeleteChannel(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="bar">
          <b>自动化执行审计日志（最近 80 条 · 含定时 Cron / 手动试跑 / 实时业务事件）</b>
          <el-button size="small" @click="loadLogs">🔄 刷新日志</el-button>
        </div>
        <el-table :data="logs" border stripe size="small">
          <el-table-column prop="executedAt" label="执行时间" width="160" />
          <el-table-column prop="triggerSource" label="来源" width="85">
            <template #default="{ row }">
              <el-tag size="small" :type="row.triggerSource === 'EVENT' ? 'warning' : row.triggerSource === 'MANUAL' ? 'primary' : 'info'" effect="plain">
                {{ row.triggerSource }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ruleName" label="规则名称" width="180" />
          <el-table-column label="状态" width="85">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'danger' : 'info'">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="命中/推送" width="95" align="center">
            <template #default="{ row }">{{ row.affectedCount }} / {{ row.pushedCount }}</template>
          </el-table-column>
          <el-table-column prop="summary" label="执行摘要" min-width="280" />
          <el-table-column label="耗时" width="75" align="right">
            <template #default="{ row }">{{ row.durationMs }}ms</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 试跑结果预览弹窗 -->
    <el-dialog v-model="runPreviewVisible" :title="runPreview ? `试跑结果 · ${runPreview.ruleName}` : '试跑结果'" width="600px">
      <div v-if="runPreview">
        <el-alert :type="runPreview.status === 'SUCCESS' ? 'success' : 'error'" :closable="false" :title="runPreview.summary" style="margin-bottom: 12px" />
        <pre v-if="runPreview.previewText" class="preview-box">{{ runPreview.previewText }}</pre>
        <div class="sub">命中条数：{{ runPreview.affectedCount }} · 外推通道数：{{ runPreview.pushedCount }} · 耗时：{{ runPreview.durationMs }}ms</div>
      </div>
      <template #footer>
        <el-button type="primary" @click="runPreviewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 规则参数调整弹窗 -->
    <el-dialog v-model="editRuleVisible" :title="`调整参数 · ${editRuleForm.ruleName}`" width="460px">
      <el-form label-width="110px" size="small">
        <el-form-item label="提前预警天数">
          <el-input-number v-model="editRuleForm.leadDays" :min="0" :max="365" style="width: 100%" />
        </el-form-item>
        <el-form-item label="阈值(金额/%)">
          <el-input-number v-model="editRuleForm.thresholdAmount" :min="0" :precision="2" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="推送目标角色">
          <el-select v-model="editRuleForm.targetRole" style="width: 100%">
            <el-option label="老板" value="老板" />
            <el-option label="财务" value="财务" />
            <el-option label="业务" value="业务" />
            <el-option label="全员" value="全员" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editRuleVisible = false">取消</el-button>
        <el-button type="primary" :loading="editRuleSaving" @click="submitEditRule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增/编辑外推通道弹窗 -->
    <el-dialog v-model="channelDlg" :title="channelForm.id ? '编辑推送通道' : '新增外推消息通道'" width="540px">
      <el-form :model="channelForm" label-width="110px" size="small">
        <el-form-item label="通道名称" required>
          <el-input v-model="channelForm.name" placeholder="如：老板企微群 / 财务钉钉提醒群" />
        </el-form-item>
        <el-form-item label="平台类型" required>
          <el-radio-group v-model="channelForm.channelType">
            <el-radio-button value="wecom">企业微信</el-radio-button>
            <el-radio-button value="dingtalk">钉钉</el-radio-button>
            <el-radio-button value="feishu">飞书</el-radio-button>
            <el-radio-button value="generic">通用Webhook</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="接收角色" required>
          <el-select v-model="channelForm.targetRole" style="width: 100%">
            <el-option label="老板（接收晨报、资金水位与巡检）" value="老板" />
            <el-option label="财务（接收收租/开票/应付/催收）" value="财务" />
            <el-option label="业务（接收质保/到期合同提醒）" value="业务" />
            <el-option label="全员（接收全部角色通知）" value="全员" />
          </el-select>
        </el-form-item>
        <el-form-item label="Webhook 地址" required>
          <el-input v-model="channelForm.webhookUrl" placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..." />
        </el-form-item>
        <el-form-item label="加签 Secret">
          <el-input v-model="channelForm.secret" placeholder="钉钉/飞书开启加签校验时填写，企微留空" />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="channelForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="channelDlg = false">取消</el-button>
        <el-button type="primary" :loading="channelSaving" @click="submitChannel">保存</el-button>
      </template>
    </el-dialog>

    <!-- 手工录入单笔银行流水试跑弹窗 -->
    <el-dialog v-model="manualDlg" title="录入银行收款流水（自动智能匹配收租单）" width="480px">
      <el-form :model="manualForm" label-width="100px" size="small">
        <el-form-item label="交易日期">
          <el-date-picker v-model="manualForm.txDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="付款方户名">
          <el-input v-model="manualForm.counterpartyName" placeholder="如：顺兴物流有限公司" />
        </el-form-item>
        <el-form-item label="收款金额" required>
          <el-input-number v-model="manualForm.amount" :min="0.01" :precision="2" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="银行流水号">
          <el-input v-model="manualForm.bankSerial" placeholder="可选，用于自动防重导入" />
        </el-form-item>
        <el-form-item label="摘要/备注">
          <el-input v-model="manualForm.memo" placeholder="如包含合同号将 99% 精确命中" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualDlg = false">取消</el-button>
        <el-button type="primary" :loading="manualSaving" @click="submitManualStmt">导入并智能匹配</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.auto-page { padding: 4px 0; }
.bar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; }
.bar-left, .bar-right { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.sub { font-size: 12px; color: #6b7280; margin-top: 2px; }
.upload-btn {
  display: inline-block; padding: 6px 12px; background: #eff6ff; color: #1d4ed8;
  border: 1px dashed #93c5fd; border-radius: 6px; cursor: pointer; font-size: 12px;
}
.upload-btn:hover { background: #dbeafe; }
.preview-box {
  background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 6px; padding: 12px;
  white-space: pre-wrap; font-family: inherit; font-size: 13px; line-height: 1.65; margin: 0 0 10px;
}
</style>
