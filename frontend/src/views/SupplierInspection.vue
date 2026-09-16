<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchInspections, createInspection, updateInspection, deleteInspection, decideInspection,
  uploadInspectionArchive, fetchInspectionArchives, exportInspections, importInspections,
  INSPECTION_SCOPES, INSPECTION_ARCHIVE_EXTS, INSPECTION_ARCHIVE_MAX_MB,
  type InspectionItem, type FileItem, type InspectionImportResult,
} from '@/api/supplier'
import { checkUploadFile, saveFile } from '@/api/asset'
import { sessionRole } from '@/utils/session'

const router = useRouter()
const canReview = computed(() => sessionRole.value === '供应链' || sessionRole.value === '老板')

// ---- 列表(按序号排序,后端已排好) ----
const filters = reactive<{ keyword: string; result: string }>({ keyword: '', result: '' })
const results = ['待考察', '合格', '不合格']
const list = ref<InspectionItem[]>([])
const total = ref(0)
const loading = ref(false)

async function loadList() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 1000 }
    if (filters.keyword) params.keyword = filters.keyword
    if (filters.result) params.result = filters.result
    const res = await fetchInspections(params)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

const resultType: Record<string, string> = { 待考察: 'warning', 合格: 'success', 不合格: 'danger' }
const PASS_NOTE = '列入“供应商上游”模块，做好关联关系'
const FAIL_NOTE = '不列入“供应商上游”模块，不做关联关系'

function fmtTime(v?: string) {
  return v ? v.replace('T', ' ').slice(0, 16) : '—'
}
function fileSize(bytes: number) {
  return bytes >= 1024 * 1024 ? (bytes / 1024 / 1024).toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' KB'
}

// ---- 导出 / 导入 ----
const exporting = ref(false)
async function onExport(template = false) {
  exporting.value = true
  try {
    await exportInspections(template)
    ElMessage.success(template ? '模板已下载' : '已导出，可修改后直接导入回系统')
  } finally {
    exporting.value = false
  }
}

const importing = ref(false)
const importResult = ref<InspectionImportResult | null>(null)
const importResultVisible = ref(false)

function beforeImport(file: File) {
  const err = checkUploadFile(file, ['xls', 'xlsx'], 10)
  if (err) {
    ElMessage.warning(err)
    return false
  }
  return true
}

async function importRequest(options: any) {
  const file = options.file as File
  try {
    await ElMessageBox.confirm(
      `将按「公司名称」导入「${file.name}」：已有的更新公司信息，没有的新增；「是否合格」填了是/否的会自动判定或改判，并同步供应商上游（改为不合格的会解除关联并把原供应商置为淘汰）。表格里没有的考察记录不会删除。确认导入？`,
      '导入考察汇总表', { type: 'warning', confirmButtonText: '确认导入' },
    )
  } catch {
    return // 取消
  }
  importing.value = true
  try {
    importResult.value = await importInspections(file)
    importResultVisible.value = true
    await loadList()
  } finally {
    importing.value = false
  }
}

// ---- 新增 / 编辑 ----
const formVisible = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const saving = ref(false)
const form = reactive<Record<string, any>>({})
const queuedFiles = ref<File[]>([])

function resetForm(values: Record<string, any>) {
  Object.keys(form).forEach((k) => delete form[k])
  Object.assign(form, {
    id: undefined, sortNo: undefined, companyName: '', legalPerson: '', registeredCapitalWan: '',
    establishedDate: undefined, businessScope: [], address: '', contact: '', phone: '', remark: '',
    ...values,
  })
}

function openCreate() {
  formMode.value = 'create'
  resetForm({})
  queuedFiles.value = []
  formVisible.value = true
}

function openEdit(row: InspectionItem) {
  formMode.value = 'edit'
  resetForm({
    id: row.id,
    sortNo: row.sortNo,
    companyName: row.companyName,
    legalPerson: row.legalPerson || '',
    registeredCapitalWan: row.registeredCapitalWan || '',
    establishedDate: row.establishedDate,
    businessScope: [...(row.businessScope || [])],
    address: row.address || '',
    contact: row.contact || '',
    phone: row.phone || '',
    remark: row.remark || '',
  })
  queuedFiles.value = []
  formVisible.value = true
}

function beforeArchive(file: File) {
  const err = checkUploadFile(file, INSPECTION_ARCHIVE_EXTS, INSPECTION_ARCHIVE_MAX_MB)
  if (err) {
    ElMessage.warning(err)
    return false
  }
  return true
}

function queueArchive(options: any) {
  queuedFiles.value.push(options.file as File)
}

function trimOrNull(v: any) {
  const s = String(v ?? '').trim()
  return s || null
}

async function submitForm() {
  if (!String(form.companyName || '').trim()) {
    ElMessage.warning('公司名称必填')
    return
  }
  const body = {
    sortNo: form.sortNo ?? null,
    companyName: String(form.companyName).trim(),
    legalPerson: trimOrNull(form.legalPerson),
    registeredCapitalWan: trimOrNull(form.registeredCapitalWan),
    establishedDate: form.establishedDate || null,
    businessScope: form.businessScope,
    address: trimOrNull(form.address),
    contact: trimOrNull(form.contact),
    phone: trimOrNull(form.phone),
    remark: form.remark,
  }
  saving.value = true
  try {
    let id = form.id as number | undefined
    if (id) {
      await updateInspection(id, body)
    } else {
      id = await createInspection(body)
      form.id = id
      formMode.value = 'edit'
    }
    formVisible.value = false
    await loadList()
    if (queuedFiles.value.length) {
      const files = queuedFiles.value
      queuedFiles.value = []
      await openArchives(list.value.find((r) => r.id === id) || ({ id, companyName: body.companyName } as InspectionItem))
      for (const f of files) await doUpload(f)
    } else {
      ElMessage.success('考察记录已保存')
    }
  } finally {
    saving.value = false
  }
}

// ---- 考察记录压缩包 ----
const archiveVisible = ref(false)
const archiveRow = ref<InspectionItem | null>(null)
const archives = ref<FileItem[]>([])
const archivesLoading = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const uploadingName = ref('')
const archiveHint = `仅支持压缩包（${INSPECTION_ARCHIVE_EXTS.join('/')}），内含考察图片和视频，单个不超过 ${INSPECTION_ARCHIVE_MAX_MB / 1024}GB`
const archiveAccept = INSPECTION_ARCHIVE_EXTS.map((e) => '.' + e).join(',')

async function openArchives(row: InspectionItem) {
  archiveRow.value = row
  archives.value = []
  archiveVisible.value = true
  await loadArchives()
}

async function loadArchives() {
  if (!archiveRow.value) return
  archivesLoading.value = true
  try {
    archives.value = await fetchInspectionArchives(archiveRow.value.id)
  } finally {
    archivesLoading.value = false
  }
}

async function doUpload(file: File) {
  if (!archiveRow.value) return
  uploading.value = true
  uploadPercent.value = 0
  uploadingName.value = file.name
  try {
    await uploadInspectionArchive(archiveRow.value.id, file, (p) => { uploadPercent.value = p })
    ElMessage.success(`「${file.name}」上传成功`)
    await loadArchives()
    await loadList()
  } finally {
    uploading.value = false
    uploadingName.value = ''
  }
}

async function uploadArchiveRequest(options: any) {
  await doUpload(options.file as File)
}

async function downloadArchive(file: FileItem) {
  ElMessage.info('开始下载，文件较大时请稍候')
  await saveFile(file.id, file.fileName)
}

// ---- 判定 / 改判 ----
async function decide(row: InspectionItem, result: '合格' | '不合格') {
  const changing = row.result !== '待考察'
  const tip = result === '合格'
    ? `${changing ? '改判' : '判定'}「${row.companyName}」为合格：自动列入「供应商 · 上游」（阶段=入库）并建立关联。`
    : `${changing ? '改判' : '判定'}「${row.companyName}」为不合格：不列入「供应商 · 上游」${row.supplierId ? `，并解除与「${row.supplierName}」的关联、将其置为淘汰` : ''}。`
  let conclusion = ''
  try {
    if (!row.archiveCount && !changing) {
      await ElMessageBox.confirm('该考察还没有上传考察记录压缩包，确认直接判定？', '缺少考察记录', { type: 'warning' })
    }
    const { value } = await ElMessageBox.prompt(tip, `${changing ? '改判' : '判定'}${result}`, {
      confirmButtonText: `确认${result}`,
      cancelButtonText: '取消',
      inputPlaceholder: '考察结论说明（选填）',
      inputValidator: (v) => (!v || v.length <= 255 ? true : '不超过 255 字'),
      type: result === '合格' ? 'success' : 'warning',
    })
    conclusion = value || ''
  } catch {
    return // 取消
  }
  const r = await decideInspection(row.id, result, conclusion)
  ElMessage.success(r.message)
  await loadList()
}

async function remove(row: InspectionItem) {
  try {
    await ElMessageBox.confirm(`确认删除「${row.companyName}」的考察记录？`, '删除考察', { type: 'warning' })
  } catch {
    return // 取消
  }
  await deleteInspection(row.id)
  ElMessage.success('已删除')
  await loadList()
}

function goSupplier(id: number) {
  router.push({ path: '/supplier', query: { id: String(id) } })
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <div class="sub">
      列表按「序号」排序，字段与《厂家考察汇总表》一致。<b>合格</b>自动列入「供应商 · 上游」并关联；<b>不合格</b>不列入、不关联。
      可「导出」为表格，修改后「导入」回系统自动更新。
    </div>

    <div class="filterbar">
      <el-input v-model="filters.keyword" placeholder="🔍 公司/法人/联系人" style="width: 220px" clearable @keyup.enter="loadList" @clear="loadList" />
      <el-select v-model="filters.result" placeholder="考察结果" clearable style="width: 130px" @change="loadList">
        <el-option v-for="r in results" :key="r" :label="r" :value="r" />
      </el-select>
      <el-button @click="loadList">查询</el-button>
      <span class="cnt">共 {{ total }} 家</span>
      <el-button link type="primary" :disabled="exporting" @click="onExport(true)">下载模板</el-button>
      <el-upload v-if="canReview" :http-request="importRequest" :before-upload="beforeImport" accept=".xls,.xlsx" :show-file-list="false" :disabled="importing">
        <el-button :loading="importing">⬆ 导入表格</el-button>
      </el-upload>
      <span v-else class="mini">导入/判定需供应链或老板</span>
      <el-button :loading="exporting" @click="onExport(false)">⬇ 导出表格</el-button>
      <el-button type="primary" @click="openCreate">+ 新增考察供应商</el-button>
    </div>

    <el-table :data="list" v-loading="loading" stripe border size="small">
      <el-table-column prop="sortNo" label="序号" width="60" align="center" fixed="left" />
      <el-table-column prop="companyName" label="公司名称" min-width="190" show-overflow-tooltip fixed="left" />
      <el-table-column label="法人" width="80"><template #default="{ row }">{{ row.legalPerson || '—' }}</template></el-table-column>
      <el-table-column label="注册资本/万元" width="110" align="right"><template #default="{ row }">{{ row.registeredCapitalWan || '—' }}</template></el-table-column>
      <el-table-column label="成立时间" width="100"><template #default="{ row }">{{ row.establishedDate || '—' }}</template></el-table-column>
      <el-table-column label="业务范围" width="120">
        <template #default="{ row }">
          <el-tag v-for="s in row.businessScope" :key="s" size="small" class="scope-tag">{{ s }}</el-tag>
          <span v-if="!row.businessScope?.length">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="address" label="公司地址" min-width="200" show-overflow-tooltip />
      <el-table-column label="主要联系人" width="90"><template #default="{ row }">{{ row.contact || '—' }}</template></el-table-column>
      <el-table-column label="主要电话" width="115"><template #default="{ row }">{{ row.phone || '—' }}</template></el-table-column>
      <el-table-column label="考察记录压缩包" width="110">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openArchives(row)">📦 {{ row.archiveCount }} 个</el-button>
        </template>
      </el-table-column>
      <el-table-column label="是否合格" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="(resultType[row.result] as any) || 'info'" size="small">{{ row.result === '合格' ? '是' : row.result === '不合格' ? '否' : '待考察' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="是否合格（关联）" min-width="230">
        <template #default="{ row }">
          <template v-if="row.result === '合格'">
            <div class="note pass">{{ PASS_NOTE }}</div>
            <template v-if="row.supplierId">
              <a class="lnk" @click="goSupplier(row.supplierId)">{{ row.supplierName }}</a>
              <el-tag size="small" type="info" class="scope-tag">{{ row.supplierStatus }}</el-tag>
            </template>
          </template>
          <div v-else-if="row.result === '不合格'" class="note fail">{{ FAIL_NOTE }}</div>
          <span v-else class="mini">待考察</span>
          <div v-if="row.decidedAt" class="mini">{{ row.decidedByName || '—' }} · {{ fmtTime(row.decidedAt) }}</div>
          <div v-if="row.conclusion" class="mini" :title="row.conclusion">结论：{{ row.conclusion }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canReview && row.result !== '合格'" link type="success" size="small" @click="decide(row, '合格')">
            {{ row.result === '待考察' ? '合格' : '改为合格' }}
          </el-button>
          <el-button v-if="canReview && row.result !== '不合格'" link type="danger" size="small" @click="decide(row, '不合格')">
            {{ row.result === '待考察' ? '不合格' : '改为不合格' }}
          </el-button>
          <el-button link size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.result !== '合格'" link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="mini">判定、改判和导入需「供应链」或「老板」角色，每次都会留痕。合格的考察记录需先改为不合格才能删除。</div>

    <!-- 导入结果 -->
    <el-dialog v-model="importResultVisible" title="导入结果" width="680px">
      <template v-if="importResult">
        <div class="import-kpi">
          <span>共 <b>{{ importResult.total }}</b> 行</span>
          <span>新增 <b>{{ importResult.created }}</b></span>
          <span>更新 <b>{{ importResult.updated }}</b></span>
          <span class="pass">合格 <b>{{ importResult.passed }}</b></span>
          <span class="fail">不合格 <b>{{ importResult.failed }}</b></span>
          <span>跳过 <b>{{ importResult.skipped }}</b></span>
        </div>
        <el-table v-if="importResult.messages.length" :data="importResult.messages" size="small" border max-height="360">
          <el-table-column label="行" width="60" align="center" prop="row" />
          <el-table-column label="公司名称" min-width="170" show-overflow-tooltip><template #default="{ row }">{{ row.companyName || '—' }}</template></el-table-column>
          <el-table-column label="说明" min-width="300">
            <template #default="{ row }"><span :class="row.level === 'warn' ? 'fail' : ''">{{ row.message }}</span></template>
          </el-table-column>
        </el-table>
        <div v-else class="mini">没有需要注意的行，结果与表格一致。</div>
      </template>
      <template #footer>
        <el-button type="primary" @click="importResultVisible = false">知道了</el-button>
      </template>
    </el-dialog>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="formVisible" :title="formMode === 'edit' ? '编辑考察供应商' : '新增考察供应商'" width="640px" :close-on-click-modal="false">
      <el-form :model="form" label-width="110px" size="small" :disabled="saving">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="序号">
              <el-input-number v-model="form.sortNo" :min="0" :precision="0" controls-position="right" placeholder="自动" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="公司名称" required label-width="80px">
              <el-input v-model="form.companyName" maxlength="128" placeholder="工商注册全称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="法人"><el-input v-model="form.legalPerson" maxlength="64" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="注册资本/万元"><el-input v-model="form.registeredCapitalWan" maxlength="64" placeholder="如 200 或 60*6" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="成立时间">
              <el-date-picker v-model="form.establishedDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="业务范围">
              <el-checkbox-group v-model="form.businessScope">
                <el-checkbox v-for="s in INSPECTION_SCOPES" :key="s" :value="s">{{ s }}</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="公司地址"><el-input v-model="form.address" maxlength="255" /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="主要联系人"><el-input v-model="form.contact" maxlength="64" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="主要电话"><el-input v-model="form.phone" maxlength="32" /></el-form-item>
          </el-col>
        </el-row>
        <el-form-item v-if="formMode === 'create'" label="考察记录">
          <div class="archive-box">
            <el-upload :http-request="queueArchive" :before-upload="beforeArchive" :accept="archiveAccept" :show-file-list="false" multiple>
              <el-button type="primary" plain>选择压缩包</el-button>
            </el-upload>
            <div class="upload-tip">{{ archiveHint }}。保存后自动上传。</div>
            <div class="attachment-list">
              <el-tag v-for="(f, i) in queuedFiles" :key="f.name + i" closable @close="queuedFiles.splice(i, 1)">
                {{ f.name }}（{{ fileSize(f.size) }}）
              </el-tag>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 考察记录压缩包 -->
    <el-dialog v-model="archiveVisible" :title="'考察记录 · ' + (archiveRow?.companyName || '')" width="600px"
      :close-on-click-modal="false" :close-on-press-escape="!uploading" :show-close="!uploading">
      <template v-if="archiveRow">
        <el-upload :http-request="uploadArchiveRequest" :before-upload="beforeArchive"
          :accept="archiveAccept" :show-file-list="false" :disabled="uploading">
          <el-button type="primary" :loading="uploading">上传压缩包</el-button>
        </el-upload>
        <div class="upload-tip">{{ archiveHint }}</div>
        <div v-if="uploading" class="progress">
          <div class="mini">正在上传「{{ uploadingName }}」，请勿关闭页面</div>
          <el-progress :percentage="uploadPercent" :stroke-width="12" />
        </div>
        <el-table v-loading="archivesLoading" :data="archives" size="small" border class="archive-table">
          <el-table-column prop="fileName" label="文件" min-width="200" show-overflow-tooltip />
          <el-table-column label="大小" width="90"><template #default="{ row }">{{ fileSize(row.size) }}</template></el-table-column>
          <el-table-column label="上传人" width="90"><template #default="{ row }">{{ row.uploaderName || '—' }}</template></el-table-column>
          <el-table-column label="" width="70">
            <template #default="{ row }"><el-button link type="primary" size="small" @click="downloadArchive(row)">下载</el-button></template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button :disabled="uploading" @click="archiveVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 4px; }
.sub { color: #666; font-size: 13px; margin-bottom: 10px; line-height: 1.6; }
.filterbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.cnt { color: #999; font-size: 12px; margin-left: auto; }
.mini { color: #999; font-size: 12px; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lnk { color: #2e6da4; cursor: pointer; font-weight: 600; }
.lnk:hover { text-decoration: underline; }
.scope-tag { margin: 0 4px 2px 0; }
.note { font-size: 12px; line-height: 1.5; }
.pass { color: #2f9e44; }
.fail { color: #e0533d; }
.import-kpi { display: flex; gap: 18px; flex-wrap: wrap; margin-bottom: 12px; font-size: 13px; }
.import-kpi b { font-size: 16px; margin-left: 2px; }
.archive-box { width: 100%; }
.upload-tip { margin-top: 6px; color: #909399; font-size: 12px; line-height: 1.5; }
.attachment-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.progress { margin: 10px 0; }
.archive-table { margin-top: 10px; }
</style>
