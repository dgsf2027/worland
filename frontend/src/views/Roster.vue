<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchRoster, updateRole, computeCommission, fetchCommission,
  type RosterItem, type CommissionSummary,
} from '@/api/roster'

const activeTab = ref('roster')

// ============ 花名册 ============
const roster = ref<RosterItem[]>([])
async function loadRoster() {
  roster.value = await fetchRoster()
}
const editDlg = ref(false)
const eForm = reactive<Record<string, any>>({ id: 0, role: '', dataScope: '', costVisible: true, ownerScoped: false, active: true, remark: '' })
function openEdit(row: RosterItem) {
  Object.assign(eForm, { id: row.id, role: row.role, dataScope: row.dataScope, costVisible: row.costVisible, ownerScoped: row.ownerScoped, active: row.active, remark: row.remark })
  editDlg.value = true
}
async function submitEdit() {
  try {
    await updateRole(eForm.id, { role: eForm.role, dataScope: eForm.dataScope, costVisible: eForm.costVisible, ownerScoped: eForm.ownerScoped, active: eForm.active, remark: eForm.remark })
    ElMessage.success('已更新（入 audit·限老板）')
    editDlg.value = false
    loadRoster()
  } catch { /* 403 由拦截器提示 */ }
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
      title="人事从简起步：花名册 + 角色权限 + 提成。复用平台账号不自建登录；🔒 保密隔离：成本价/账期字段级权限，LP 看不到上下游价。" />
    <el-tabs v-model="activeTab">
      <!-- 花名册 -->
      <el-tab-pane label="花名册 · 角色权限" name="roster">
        <el-table :data="roster" size="small" border>
          <el-table-column prop="userName" label="成员" width="100" />
          <el-table-column label="角色" width="90">
            <template #default="{ row }"><el-tag size="small">{{ row.role }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="dataScope" label="数据权限" min-width="200" />
          <el-table-column label="成本可见" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.costVisible ? 'success' : 'warning'">{{ row.costVisible ? '可见' : '🔒 保密' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="行级隔离" width="100">
            <template #default="{ row }"><span>{{ row.ownerScoped ? '名下+公海' : '全量' }}</span></template>
          </el-table-column>
          <el-table-column label="在职" width="70">
            <template #default="{ row }"><el-tag size="small" :type="row.active ? 'success' : 'info'">{{ row.active ? '在职' : '离职' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }"><el-button link size="small" @click="openEdit(row)">改权限</el-button></template>
          </el-table-column>
        </el-table>
        <div class="note">改角色权限限「老板」，越权 403 且全程入 audit_log（M5-06 只追加）。</div>
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
    <el-dialog v-model="editDlg" title="角色权限改动（限老板·入 audit）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="角色">
          <el-select v-model="eForm.role" style="width:100%">
            <el-option label="老板" value="老板" /><el-option label="财务" value="财务" />
            <el-option label="供应链" value="供应链" /><el-option label="业务" value="业务" /><el-option label="LP" value="LP" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据权限"><el-input v-model="eForm.dataScope" /></el-form-item>
        <el-form-item label="成本可见"><el-switch v-model="eForm.costVisible" /> <span class="hint">关闭=🔒保密(如LP)</span></el-form-item>
        <el-form-item label="行级隔离"><el-switch v-model="eForm.ownerScoped" /> <span class="hint">仅名下+公海(如业务BD)</span></el-form-item>
        <el-form-item label="在职"><el-switch v-model="eForm.active" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="eForm.remark" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="editDlg = false">取消</el-button><el-button type="primary" @click="submitEdit">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { margin-bottom: 12px; display: flex; gap: 8px; align-items: center; }
.hint { color: #909399; font-size: 12px; }
.note { color: #909399; font-size: 12px; margin-top: 8px; }
.up { color: #67c23a; }
.cline { font-size: 12px; padding: 2px 0; }
</style>
