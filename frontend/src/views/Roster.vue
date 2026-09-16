<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchRoster, updateRole, computeCommission, fetchCommission,
  type RosterAccount, type CommissionSummary,
} from '@/api/roster'
import { sessionRole } from '@/utils/session'
import { refreshSession } from '@/api/auth'

const activeTab = ref('roster')
const canEditRole = computed(() => sessionRole.value === '老板')

// ============ 花名册 ============
const roster = ref<RosterAccount[]>([])
const loading = ref(false)
const loadError = ref(false)
const saving = ref(false)
const roles = ['老板', '财务', '供应链', '业务', 'GP', 'LP']
const roleNotes: Record<string, string> = {
  老板: '可管理账号权限及执行老板审批操作。请仅授予需要管理系统的同事。',
  财务: '可执行财务相关操作，查看成本、账期和授信。',
  供应链: '可执行供应链相关操作，查看成本、账期和授信。',
  业务: '客户范围限本人名下及公海，可查看成本、账期和授信。',
  GP: '投资人角色，成本、账期和授信等敏感字段受限。',
  LP: '投资人角色，成本、账期和授信等敏感字段受限。',
}
async function loadRoster() {
  loading.value = true
  loadError.value = false
  try {
    const [accounts] = await Promise.all([fetchRoster(), refreshSession()])
    roster.value = accounts
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}
const editDlg = ref(false)
const eForm = reactive({ accountId: 0, username: '', displayName: '', role: '', active: true })
const originalRole = ref('')
const originalActive = ref(true)
function openEdit(row: RosterAccount) {
  Object.assign(eForm, row)
  originalRole.value = row.role
  originalActive.value = row.active
  editDlg.value = true
}
async function submitEdit() {
  if (saving.value) return
  saving.value = true
  try {
    if (!eForm.active && originalActive.value) {
      await ElMessageBox.confirm(`停用后，${eForm.displayName}（${eForm.username}）将无法继续访问系统。`, '停用账号', { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' })
    } else if (eForm.role === '老板' && originalRole.value !== '老板') {
      await ElMessageBox.confirm(`${eForm.displayName}（${eForm.username}）将能管理其他人的角色和账号状态。`, '授予老板权限', { type: 'warning', confirmButtonText: '确认授权', cancelButtonText: '取消' })
    }
    await updateRole(eForm.accountId, { role: eForm.role, active: eForm.active })
    ElMessage.success('账号权限已更新，下次请求生效')
    editDlg.value = false
    await loadRoster()
  } catch { /* 取消保持表单；接口错误由拦截器提示 */ }
  finally { saving.value = false }
}

// ============ 提成 ============
const period = ref(new Date().toISOString().slice(0, 7))
const summaries = ref<CommissionSummary[]>([])
async function loadCommission() {
  summaries.value = await fetchCommission(period.value)
}
async function doCompute() {
  const r = await computeCommission(period.value)
  ElMessage.success(`计提完成：降本${r.costCutRows}行 成交${r.dealRows}行 跳过${r.skipped} 合计¥${r.totalCommission}`)
  loadCommission()
}
function money(v?: number) { return v == null ? '—' : '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function pct(v?: number) { return v == null ? '—' : (v * 100).toFixed(2) + '%' }

onMounted(() => { loadRoster(); loadCommission() })
</script>

<template>
  <div>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
      title="同事注册或通过门户首次登录后会出现在账号列表。老板可在此分配角色，无需服务器权限；保存后，下次请求即按新权限执行。" />
    <el-tabs v-model="activeTab">
      <!-- 花名册 -->
      <el-tab-pane label="花名册 · 账号权限" name="roster">
        <div class="bar">
          <el-button :loading="loading" @click="loadRoster">刷新账号与权限</el-button>
          <span v-if="!canEditRole" class="hint">当前为只读。需要调整权限时，请联系拥有「老板」角色的管理员。</span>
        </div>
        <el-alert v-if="loadError" type="error" :closable="false" title="账号列表加载失败，请点击上方刷新重试。" class="load-error" />
        <el-table v-else v-loading="loading" :data="roster" row-key="accountId" size="small" border empty-text="暂无登录账号，同事首次登录后请刷新列表">
          <el-table-column prop="displayName" label="成员" min-width="100" show-overflow-tooltip />
          <el-table-column prop="username" label="登录账号" min-width="160" show-overflow-tooltip />
          <el-table-column prop="loginSource" label="登录方式" width="100" />
          <el-table-column label="角色" width="90">
            <template #default="{ row }"><el-tag size="small">{{ row.role }}</el-tag></template>
          </el-table-column>
          <el-table-column label="成本等敏感字段" min-width="130">
            <template #default="{ row }">
              <el-tag size="small" :type="row.costVisible ? 'success' : 'warning'">{{ row.costVisible ? '可见' : '🔒 保密' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="客户行级限制" min-width="130">
            <template #default="{ row }"><span>{{ row.ownerScoped ? '名下+公海' : '无本人归属限制' }}</span></template>
          </el-table-column>
          <el-table-column label="账号状态" width="90">
            <template #default="{ row }"><el-tag size="small" :type="row.active ? 'success' : 'info'">{{ row.active ? '启用' : '停用' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }"><el-button v-if="canEditRole" link size="small" @click="openEdit(row)">改权限</el-button><span v-else class="hint">仅老板</span></template>
          </el-table-column>
        </el-table>
        <div class="note">成本等敏感字段与客户行级限制随角色确定。权限变更保留操作记录；系统至少保留一个启用的老板账号。停用账号后，该账号无法继续访问。</div>
      </el-tab-pane>

      <!-- 提成 -->
      <el-tab-pane label="提成 · 降本/成交贡献" name="commission">
        <div class="bar">
          <el-date-picker v-model="period" type="month" value-format="YYYY-MM" placeholder="归属期" style="width:150px" @change="loadCommission" />
          <el-button type="primary" @click="doCompute">计提本期提成</el-button>
          <el-button @click="loadCommission">刷新</el-button>
          <span class="hint">提成从「管理费」列支，激励与降本/拓客挂钩，可溯每单来源。</span>
        </div>
        <el-table :data="summaries" size="small" border>
          <el-table-column prop="userName" label="成员" width="100" />
          <el-table-column prop="role" label="角色" width="90" />
          <el-table-column label="集采降本" min-width="200">
            <template #default="{ row }">
              <span v-if="row.costCutOrders">{{ row.costCutOrders }} 单 · 降本 {{ money(row.costCutBase) }} → 提成 <b class="up">{{ money(row.costCutCommission) }}</b></span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="成交贡献" min-width="200">
            <template #default="{ row }">
              <span v-if="row.dealOrders">{{ row.dealOrders }} 单 · 成交额 {{ money(row.dealBase) }} → 提成 <b class="up">{{ money(row.dealCommission) }}</b></span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="合计提成（管理费列支）" width="180">
            <template #default="{ row }"><b class="up">{{ money(row.totalCommission) }}</b></template>
          </el-table-column>
          <el-table-column label="明细" width="70">
            <template #default="{ row }">
              <el-popover width="360" trigger="hover">
                <template #reference><el-tag size="small" type="primary">{{ row.lines.length }} 条</el-tag></template>
                <div v-for="l in row.lines" :key="l.id" class="cline">
                  {{ l.type }} · {{ l.sourceRef }} · 基 {{ money(l.baseAmount) }} × {{ pct(l.rate) }} → {{ money(l.commissionAmount) }}
                </div>
              </el-popover>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 改权限弹窗 -->
    <el-dialog v-model="editDlg" title="修改账号权限" width="min(460px, 94vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-width="90px">
        <el-form-item label="登录账号"><span class="account-name">{{ eForm.displayName }}（{{ eForm.username }}）</span></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="eForm.role" :disabled="saving" style="width:100%">
            <el-option v-for="role in roles" :key="role" :label="role" :value="role" />
          </el-select>
          <span class="hint">{{ roleNotes[eForm.role] }}</span>
        </el-form-item>
        <el-form-item label="账号启用"><el-switch v-model="eForm.active" :disabled="saving" aria-label="账号启用" /><span class="hint">停用后立即阻止后续访问</span></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="editDlg = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!canEditRole" @click="submitEdit">保存权限</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { margin-bottom: 12px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.hint { color: #606266; font-size: 12px; }
.note { color: #606266; font-size: 12px; margin-top: 8px; line-height: 1.6; }
.account-name { overflow-wrap: anywhere; }
.load-error { margin-bottom: 12px; }
.up { color: #67c23a; }
.cline { font-size: 12px; padding: 2px 0; }
</style>
