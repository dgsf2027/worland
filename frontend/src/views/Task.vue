<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchTasks, dispatchTask, transferTask, startTask, completeTask, systemScan,
  type TaskItem,
} from '@/api/task'
import {
  fetchApprovals, initiateApproval, approveApproval, rejectApproval,
  type ApprovalItem,
} from '@/api/approval'

const activeTab = ref('task')

// ============ 任务 ============
const tasks = ref<TaskItem[]>([])
const taskFilter = reactive<{ role: string; status: string; source: string }>({ role: '', status: '', source: '' })
const statusTag: Record<string, string> = { 已完成: 'success', 进行中: 'warning', 待开始: 'info', 已作废: 'info', 超时: 'danger' }
const prioTag: Record<string, string> = { 高: 'danger', 中: 'warning', 低: 'info' }

async function loadTasks() {
  const params: Record<string, any> = { page: 1, size: 100 }
  if (taskFilter.role) params.role = taskFilter.role
  if (taskFilter.status) params.status = taskFilter.status
  if (taskFilter.source) params.source = taskFilter.source
  const res = await fetchTasks(params)
  tasks.value = res.records
}

const dispatchDlg = ref(false)
const dForm = reactive<Record<string, any>>({ title: '', type: '通用', assigneeRole: '', assigneeUserName: '', priority: '中', verifyRequired: false })
function openDispatch() {
  Object.assign(dForm, { title: '', type: '通用', assigneeRole: '', assigneeUserName: '', priority: '中', verifyRequired: false })
  dispatchDlg.value = true
}
async function submitDispatch() {
  if (!dForm.title) { ElMessage.warning('任务标题必填'); return }
  if (!dForm.assigneeRole) { ElMessage.warning('请选承接角色'); return }
  await dispatchTask({ ...dForm })
  ElMessage.success('已派单')
  dispatchDlg.value = false
  loadTasks()
}

async function doStart(row: TaskItem) {
  await startTask(row.id)
  ElMessage.success('已开始')
  loadTasks()
}
async function doComplete(row: TaskItem) {
  let evidence = ''
  if (row.verifyRequired) {
    try {
      const r = await ElMessageBox.prompt('本任务需完成证据（文件URL/说明）', '完成校验', { inputPlaceholder: '如 s3://bom/xxx.xlsx' })
      evidence = r.value
    } catch { return }
  }
  await completeTask(row.id, { evidence })
  ElMessage.success('已完成')
  loadTasks()
}
async function doTransfer(row: TaskItem) {
  try {
    const role = await ElMessageBox.prompt('转派到角色（老板/财务/供应链/业务）', '转派', { inputPlaceholder: '供应链' })
    const reason = await ElMessageBox.prompt('转派理由（留痕）', '转派', { inputPlaceholder: '如 李工外出' })
    await transferTask(row.id, { toRole: role.value, reason: reason.value })
    ElMessage.success('已转派（留痕）')
    loadTasks()
  } catch { /* cancelled */ }
}
async function doSystemScan() {
  const r = await systemScan()
  ElMessage.success(`系统派单：逾期${r.overdueOpened} 兑付缺口${r.coverageGapOpened} 到期${r.expiryOpened}`)
  loadTasks()
}

// ============ 审批 ============
const approvals = ref<ApprovalItem[]>([])
const apStatusTag: Record<string, string> = { 已通过: 'success', 已驳回: 'danger', 待审批: 'warning' }
async function loadApprovals() {
  const res = await fetchApprovals({ page: 1, size: 100 })
  approvals.value = res.records
}
const apDlg = ref(false)
const apForm = reactive<Record<string, any>>({ subject: '', amount: undefined, principalReturnRate: undefined })
function openInitiate() {
  Object.assign(apForm, { subject: '', amount: undefined, principalReturnRate: undefined })
  apDlg.value = true
}
async function submitInitiate() {
  if (!apForm.amount || apForm.principalReturnRate == null) { ElMessage.warning('金额与本金回报率必填'); return }
  try {
    await initiateApproval({ ...apForm })
    ElMessage.success('已发起投放审批')
    apDlg.value = false
    loadApprovals()
  } catch { /* 达标闸拒绝已由拦截器提示 */ }
}
async function doApprove(row: ApprovalItem) {
  await approveApproval(row.id, { reason: row.decisionMode === '自主' ? '300万内自主通过' : '与合伙人协商通过' })
  ElMessage.success('已通过')
  loadApprovals()
}
async function doReject(row: ApprovalItem) {
  try {
    const r = await ElMessageBox.prompt('驳回原因', '驳回', { inputPlaceholder: '如回报未达标' })
    await rejectApproval(row.id, { reason: r.value })
    ElMessage.success('已驳回')
    loadApprovals()
  } catch { /* cancelled */ }
}
function pct(v?: number) { return v == null ? '—' : (v * 100).toFixed(1) + '%' }
function money(v?: number) { return v == null ? '—' : '¥' + v.toLocaleString('zh-CN') }

onMounted(() => { loadTasks(); loadApprovals() })
</script>

<template>
  <div>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
      title="任务绑角色、角色绑人，转派留痕，完成必有系统校验（不是打勾就算）。投放审批 300 万内自主，超额转协商。" />
    <el-tabs v-model="activeTab">
      <!-- ======== 任务 ======== -->
      <el-tab-pane label="任务中心" name="task">
        <div class="bar">
          <el-select v-model="taskFilter.role" placeholder="承接角色" clearable style="width:130px" @change="loadTasks">
            <el-option label="老板" value="老板" /><el-option label="财务" value="财务" />
            <el-option label="供应链" value="供应链" /><el-option label="业务" value="业务" />
          </el-select>
          <el-select v-model="taskFilter.source" placeholder="来源" clearable style="width:110px" @change="loadTasks">
            <el-option label="系统" value="系统" /><el-option label="派单" value="派单" />
          </el-select>
          <el-select v-model="taskFilter.status" placeholder="状态" clearable style="width:110px" @change="loadTasks">
            <el-option label="待开始" value="待开始" /><el-option label="进行中" value="进行中" />
            <el-option label="已完成" value="已完成" /><el-option label="超时" value="超时" />
          </el-select>
          <el-button type="primary" @click="openDispatch">＋ 派单</el-button>
          <el-button @click="doSystemScan">⚡ 系统派单扫描</el-button>
        </div>
        <el-table :data="tasks" size="small" border>
          <el-table-column prop="no" label="任务号" width="160" />
          <el-table-column prop="title" label="任务" min-width="180" />
          <el-table-column prop="type" label="类型" width="110" />
          <el-table-column label="承接" width="140">
            <template #default="{ row }">{{ row.assigneeRole }}<span v-if="row.assigneeUserName">·{{ row.assigneeUserName }}</span></template>
          </el-table-column>
          <el-table-column label="来源" width="80">
            <template #default="{ row }"><el-tag size="small" :type="row.source === '系统' ? 'warning' : 'info'">{{ row.source }}</el-tag></template>
          </el-table-column>
          <el-table-column label="优先级" width="80">
            <template #default="{ row }"><el-tag size="small" :type="prioTag[row.priority] || 'info'">{{ row.priority }}</el-tag></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }"><el-tag size="small" :type="statusTag[row.status] || 'info'">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="校验" width="130">
            <template #default="{ row }">
              <el-tag v-if="row.verifyRequired" size="small" :type="row.verifyEvidence ? 'success' : 'warning'">
                {{ row.verifyEvidence ? '已验证' : '需证据' }}
              </el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="转派留痕" width="90">
            <template #default="{ row }">
              <el-popover v-if="row.transferLog?.length" width="320" trigger="hover">
                <template #reference><el-tag size="small" type="primary">{{ row.transferLog.length }} 次</el-tag></template>
                <div v-for="(t, i) in row.transferLog" :key="i" class="tlog">
                  {{ t.at }} · <b>{{ t.by }}</b>：{{ t.from }} → {{ t.to }}（{{ t.reason }}）
                </div>
              </el-popover>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status !== '已完成' && row.status !== '已作废'">
                <el-button link size="small" @click="doStart(row)">开始</el-button>
                <el-button link size="small" @click="doTransfer(row)">转派</el-button>
                <el-button link size="small" type="success" @click="doComplete(row)">完成</el-button>
              </template>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ======== 审批 ======== -->
      <el-tab-pane label="投放审批" name="approval">
        <div class="bar">
          <el-button type="primary" @click="openInitiate">＋ 发起投放审批</el-button>
          <span class="hint">本金回报 ≥ 目标方可发起；≤300万自主 / 超额协商；通过需老板。</span>
        </div>
        <el-table :data="approvals" size="small" border>
          <el-table-column prop="no" label="审批号" width="160" />
          <el-table-column prop="subject" label="标的" min-width="150" />
          <el-table-column label="金额" width="120"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
          <el-table-column label="本金回报" width="100"><template #default="{ row }">{{ pct(row.principalReturnRate) }}</template></el-table-column>
          <el-table-column label="目标" width="90"><template #default="{ row }">{{ pct(row.targetRate) }}</template></el-table-column>
          <el-table-column label="裁决方式" width="100">
            <template #default="{ row }"><el-tag size="small" :type="row.decisionMode === '自主' ? 'success' : 'warning'">{{ row.decisionMode }}</el-tag></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }"><el-tag size="small" :type="apStatusTag[row.status] || 'info'">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="approverName" label="审批人" width="90" />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === '待审批'">
                <el-button link size="small" type="success" @click="doApprove(row)">通过</el-button>
                <el-button link size="small" type="danger" @click="doReject(row)">驳回</el-button>
              </template>
              <span v-else>{{ row.decisionReason || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 派单弹窗 -->
    <el-dialog v-model="dispatchDlg" title="派单（绑角色绑人）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="任务标题"><el-input v-model="dForm.title" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="dForm.type" style="width:100%">
            <el-option label="集采比价" value="集采比价" /><el-option label="BOM" value="BOM" />
            <el-option label="催收" value="催收" /><el-option label="跟进待办" value="跟进待办" />
            <el-option label="通用" value="通用" />
          </el-select>
        </el-form-item>
        <el-form-item label="承接角色">
          <el-select v-model="dForm.assigneeRole" style="width:100%">
            <el-option label="老板" value="老板" /><el-option label="财务" value="财务" />
            <el-option label="供应链" value="供应链" /><el-option label="业务" value="业务" />
          </el-select>
        </el-form-item>
        <el-form-item label="承接人"><el-input v-model="dForm.assigneeUserName" placeholder="如 李工（可空·仅绑角色）" /></el-form-item>
        <el-form-item label="优先级">
          <el-radio-group v-model="dForm.priority"><el-radio label="高" /><el-radio label="中" /><el-radio label="低" /></el-radio-group>
        </el-form-item>
        <el-form-item label="需校验"><el-switch v-model="dForm.verifyRequired" /> <span class="hint">完成需上传证据</span></el-form-item>
      </el-form>
      <template #footer><el-button @click="dispatchDlg = false">取消</el-button><el-button type="primary" @click="submitDispatch">派单</el-button></template>
    </el-dialog>

    <!-- 发起审批弹窗 -->
    <el-dialog v-model="apDlg" title="发起投放审批" width="440px">
      <el-form label-width="100px">
        <el-form-item label="标的"><el-input v-model="apForm.subject" placeholder="如 CG-2026-031 采购" /></el-form-item>
        <el-form-item label="投放金额"><el-input-number v-model="apForm.amount" :min="0" :step="10000" style="width:100%" /></el-form-item>
        <el-form-item label="本金回报率"><el-input-number v-model="apForm.principalReturnRate" :min="0" :max="1" :step="0.01" :precision="4" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="apDlg = false">取消</el-button><el-button type="primary" @click="submitInitiate">发起</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { margin-bottom: 12px; display: flex; gap: 8px; align-items: center; }
.hint { color: #909399; font-size: 12px; }
.tlog { font-size: 12px; padding: 2px 0; }
</style>
