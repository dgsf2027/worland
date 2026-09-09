<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchMaintenances, createMaintenance, assignMaintenance, handleMaintenance, fetchSpareAlert,
  type MaintenanceItem, type SparePartAlertResponse,
} from '@/api/maintenance'

const activeTab = ref('list')
const list = ref<MaintenanceItem[]>([])
const total = ref(0)
const loading = ref(false)
const filters = reactive<{ status: string; type: string }>({ status: '', type: '' })

const statusTag: Record<string, string> = { 待派工: 'warning', 处理中: 'primary', 已完成: 'success', 已关闭: 'info' }
const typeTag: Record<string, string> = { 报修: 'danger', 预防: 'primary', 巡检: 'info' }

async function loadList() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 50 }
    if (filters.status) params.status = filters.status
    if (filters.type) params.type = filters.type
    const res = await fetchMaintenances(params)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function money(v?: number) {
  if (v === null || v === undefined) return '—'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// ---- 报修/建单 ----
const createForm = reactive<{ assetId?: number; bomId?: number; type: string; faultDesc: string }>({
  type: '报修', faultDesc: '',
})
async function submitCreate() {
  if (!createForm.assetId) { ElMessage.warning('请填写设备ID'); return }
  const body: Record<string, any> = { assetId: createForm.assetId, type: createForm.type }
  if (createForm.bomId) body.bomId = createForm.bomId
  if (createForm.faultDesc) body.faultDesc = createForm.faultDesc
  await createMaintenance(body)
  ElMessage.success('工单已创建(待派工)')
  createForm.faultDesc = ''
  activeTab.value = 'list'
  loadList()
}

// ---- 派工 ----
async function onAssign(row: MaintenanceItem) {
  try {
    const { value } = await ElMessageBox.prompt('责任方(我方/供应商)', `派工 ${row.no}`, {
      confirmButtonText: '派工', cancelButtonText: '取消', inputValue: '我方',
      inputValidator: (v) => (['我方', '供应商'].includes((v || '').trim()) ? true : '填 我方 或 供应商'),
    })
    await assignMaintenance(row.id, { responsibleParty: value.trim() })
    ElMessage.success('已派工(处理中)')
    loadList()
  } catch { /* 取消 */ }
}

// ---- 处理/完工 ----
const handleDlg = reactive<{ show: boolean; id?: number; no?: string; cost?: number; inWarranty: boolean; handleNote: string; recordFault: boolean }>({
  show: false, cost: 0, inWarranty: false, handleNote: '', recordFault: true,
})
function openHandle(row: MaintenanceItem) {
  handleDlg.show = true
  handleDlg.id = row.id
  handleDlg.no = row.no
  handleDlg.cost = 0
  handleDlg.inWarranty = false
  handleDlg.handleNote = ''
  handleDlg.recordFault = row.type === '报修'
}
async function submitHandle() {
  await handleMaintenance(handleDlg.id!, {
    cost: handleDlg.cost, inWarranty: handleDlg.inWarranty,
    handleNote: handleDlg.handleNote, recordFault: handleDlg.recordFault,
  })
  ElMessage.success('已完工' + (handleDlg.inWarranty ? '(质保内→转供应商·费用不计我方)' : ''))
  handleDlg.show = false
  loadList()
}

// ---- 备件提示 ----
const spare = ref<SparePartAlertResponse | null>(null)
async function loadSpare() { spare.value = await fetchSpareAlert() }

onMounted(() => { loadList(); loadSpare() })
</script>

<template>
  <div class="page">
    <div class="sub">维保工单：报修/预防/巡检 → 派工 → 处理 → 回写闭环；故障回写配件 fault_count；质保内转供应商(费用不计我方)；高故障配件备件提示。</div>

    <el-tabs v-model="activeTab" @tab-change="(n: string | number) => n === 'spare' && loadSpare()">
      <!-- ============ 工单列表 ============ -->
      <el-tab-pane label="工单列表" name="list">
        <div class="filterbar">
          <el-select v-model="filters.status" placeholder="状态" clearable style="width: 120px" @change="loadList">
            <el-option v-for="s in ['待派工','处理中','已完成','已关闭']" :key="s" :label="s" :value="s" />
          </el-select>
          <el-select v-model="filters.type" placeholder="类型" clearable style="width: 120px" @change="loadList">
            <el-option v-for="t in ['报修','预防','巡检']" :key="t" :label="t" :value="t" />
          </el-select>
          <el-button type="primary" @click="loadList">筛选</el-button>
          <el-button @click="activeTab = 'create'">+ 报修</el-button>
          <span class="cnt">共 {{ total }} 单</span>
        </div>
        <el-table :data="list" v-loading="loading" stripe border size="small">
          <el-table-column prop="no" label="工单号" min-width="130" />
          <el-table-column label="类型" width="70"><template #default="{ row }"><el-tag :type="(typeTag[row.type] as any) || 'info'" size="small">{{ row.type }}</el-tag></template></el-table-column>
          <el-table-column prop="serialNo" label="设备" width="120" />
          <el-table-column prop="bomName" label="故障配件" width="110"><template #default="{ row }">{{ row.bomName || '—' }}</template></el-table-column>
          <el-table-column prop="faultDesc" label="故障描述" min-width="150" show-overflow-tooltip />
          <el-table-column label="责任方" width="90">
            <template #default="{ row }">
              {{ row.responsibleParty }}
              <el-tag v-if="row.inWarranty" type="success" size="small">质保</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="我方成本" width="100" align="right"><template #default="{ row }">{{ money(row.ourCost) }}</template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="(statusTag[row.status] as any) || 'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button v-if="row.status === '待派工'" link type="primary" size="small" @click="onAssign(row)">派工</el-button>
              <el-button v-if="row.status === '处理中'" link type="success" size="small" @click="openHandle(row)">完工</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="mini">我方成本 = 责任方我方 且 非质保内 才计入；质保内自动转供应商，费用记录但 ourCost=0。</div>
      </el-tab-pane>

      <!-- ============ 报修 ============ -->
      <el-tab-pane label="报修/建单" name="create">
        <div class="panel">
          <h4>报修 / 预防 / 巡检 建单</h4>
          <div class="frm">
            <el-input-number v-model="createForm.assetId" :min="1" placeholder="设备ID" controls-position="right" style="width: 140px" />
            <el-input-number v-model="createForm.bomId" :min="1" placeholder="故障配件ID(可空)" controls-position="right" style="width: 170px" />
            <el-select v-model="createForm.type" style="width: 110px">
              <el-option v-for="t in ['报修','预防','巡检']" :key="t" :label="t" :value="t" />
            </el-select>
            <el-input v-model="createForm.faultDesc" placeholder="故障描述" style="width: 300px" />
            <el-button type="primary" @click="submitCreate">提交建单</el-button>
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ 备件提示 ============ -->
      <el-tab-pane label="高故障备件提示" name="spare">
        <div class="panel">
          <h4>高故障配件备件提示(fault_count &gt; {{ spare?.threshold ?? 3 }})　共 {{ spare?.alertCount ?? 0 }} 项</h4>
          <el-table :data="spare?.items || []" border size="small">
            <el-table-column prop="serialNo" label="设备" width="130" />
            <el-table-column prop="bomName" label="配件" min-width="130" />
            <el-table-column label="故障次数" width="90" align="right"><template #default="{ row }"><b class="warn">{{ row.faultCount }}</b></template></el-table-column>
            <el-table-column label="可维修" width="80"><template #default="{ row }"><el-tag :type="row.repairable ? 'success' : 'danger'" size="small">{{ row.repairable ? '可修' : '不可修' }}</el-tag></template></el-table-column>
            <el-table-column prop="suggestion" label="建议" min-width="200" />
          </el-table>
          <el-empty v-if="!(spare?.items || []).length" description="暂无高故障配件" :image-size="60" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 完工弹窗 -->
    <el-dialog v-model="handleDlg.show" :title="'处理完工 ' + (handleDlg.no || '')" width="440px">
      <div class="dlg">
        <div class="row"><span>维修费用</span><el-input-number v-model="handleDlg.cost" :min="0" controls-position="right" style="width: 180px" /></div>
        <div class="row"><span>质保内</span><el-switch v-model="handleDlg.inWarranty" /> <span class="mini">质保内→转供应商·费用不计我方</span></div>
        <div class="row"><span>回写故障计数</span><el-switch v-model="handleDlg.recordFault" /> <span class="mini">fault_count++(报修默认开)</span></div>
        <div class="row"><span>处理记录</span><el-input v-model="handleDlg.handleNote" type="textarea" :rows="2" style="width: 260px" /></div>
      </div>
      <template #footer>
        <el-button @click="handleDlg.show = false">取消</el-button>
        <el-button type="primary" @click="submitHandle">确认完工</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 4px; }
.sub { color: #666; font-size: 13px; margin-bottom: 10px; }
.filterbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.cnt { color: #999; font-size: 12px; margin-left: auto; }
.mini { color: #999; font-size: 12px; margin-top: 8px; }
.panel { border: 1px solid #eee; border-radius: 8px; padding: 14px; background: #fff; margin-bottom: 12px; }
.panel h4 { margin: 0 0 12px; font-size: 14px; }
.frm { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.warn { color: #d9534f; }
.dlg .row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.dlg .row > span:first-child { min-width: 90px; color: #555; font-size: 13px; }
</style>
