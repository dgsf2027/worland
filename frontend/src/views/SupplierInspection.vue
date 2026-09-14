<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchInspections, createInspection, updateInspection, deleteInspection, decideInspection,
  uploadInspectionArchive, fetchInspectionArchives,
  INSPECTION_SCOPES, INSPECTION_ARCHIVE_EXTS, INSPECTION_ARCHIVE_MAX_MB,
  type InspectionItem, type FileItem,
} from '@/api/supplier'
import { checkUploadFile, saveFile } from '@/api/asset'

const router = useRouter()

// ---- 列表 ----
const filters = reactive<{ keyword: string; result: string }>({ keyword: '', result: '' })
const results = ['待考察', '合格', '不合格']
const list = ref<InspectionItem[]>([])
const total = ref(0)
const loading = ref(false)

async function loadList() {
  loading.value = true
  try {
    const params: Record<string, any> = { page: 1, size: 100 }
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

function capitalWan(v?: number) {
  if (v === null || v === undefined) return '—'
  return (v / 10000).toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' 万元'
}
function fmtTime(v?: string) {
  return v ? v.replace('T', ' ').slice(0, 16) : '—'
}
function fileSize(bytes: number) {
  return bytes >= 1024 * 1024 ? (bytes / 1024 / 1024).toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' KB'
}

// ---- 新增 / 编辑 ----
const formVisible = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const saving = ref(false)
const form = reactive<{
  id?: number; companyName: string; legalPerson: string; registeredCapitalWan?: number
  businessScope: string[]; contact: string; phone: string; remark: string
}>({ companyName: '', legalPerson: '', registeredCapitalWan: undefined, businessScope: [], contact: '', phone: '', remark: '' })
const queuedFiles = ref<File[]>([])

function openCreate() {
  formMode.value = 'create'
  Object.assign(form, {
    id: undefined, companyName: '', legalPerson: '', registeredCapitalWan: undefined,
    businessScope: [], contact: '', phone: '', remark: '',
  })
  queuedFiles.value = []
  formVisible.value = true
}

function openEdit(row: InspectionItem) {
  formMode.value = 'edit'
  Object.assign(form, {
    id: row.id,
    companyName: row.companyName,
    legalPerson: row.legalPerson || '',
    registeredCapitalWan: row.registeredCapital == null ? undefined : row.registeredCapital / 10000,
    businessScope: [...(row.businessScope || [])],
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

async function submitForm() {
  if (!form.companyName.trim()) {
    ElMessage.warning('公司名称必填')
    return
  }
  if (!form.businessScope.length) {
    ElMessage.warning('请至少选择一项业务范围')
    return
  }
  const body = {
    companyName: form.companyName.trim(),
    legalPerson: form.legalPerson.trim() || null,
    registeredCapital: form.registeredCapitalWan == null ? null : Math.round(form.registeredCapitalWan * 10000 * 100) / 100,
    businessScope: form.businessScope,
    contact: form.contact.trim() || null,
    phone: form.phone.trim() || null,
    remark: form.remark,
  }
  saving.value = true
  try {
    let id = form.id
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

// ---- 判定 ----
async function decide(row: InspectionItem, result: '合格' | '不合格') {
  const tip = result === '合格'
    ? `判定「${row.companyName}」考察合格后，将自动列入「供应商 · 上游」（阶段=入库）并建立关联。判定后不可更改。`
    : `判定「${row.companyName}」考察不合格后，不会列入「供应商 · 上游」。判定后不可更改。`
  let conclusion = ''
  try {
    if (!row.archiveCount) {
      await ElMessageBox.confirm('该考察还没有上传考察记录压缩包，确认直接判定？', '缺少考察记录', { type: 'warning' })
    }
    const { value } = await ElMessageBox.prompt(tip, `判定${result}`, {
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
    <div class="sub">新增考察 → 上传考察记录压缩包（图片/视频）→ 判定：<b>合格</b>自动列入「供应商 · 上游」并关联；<b>不合格</b>不列入。</div>

    <div class="filterbar">
      <el-input v-model="filters.keyword" placeholder="🔍 公司/法人/联系人" style="width: 220px" clearable @keyup.enter="loadList" @clear="loadList" />
      <el-select v-model="filters.result" placeholder="考察结果" clearable style="width: 130px" @change="loadList">
        <el-option v-for="r in results" :key="r" :label="r" :value="r" />
      </el-select>
      <el-button @click="loadList">查询</el-button>
      <span class="cnt">共 {{ total }} 家</span>
      <el-button type="primary" @click="openCreate">+ 新增考察供应商</el-button>
    </div>

    <el-table :data="list" v-loading="loading" stripe border size="small">
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="(resultType[row.result] as any) || 'info'" size="small">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="companyName" label="公司名称" min-width="170" show-overflow-tooltip />
      <el-table-column label="法人" width="90"><template #default="{ row }">{{ row.legalPerson || '—' }}</template></el-table-column>
      <el-table-column label="注册资本" width="110" align="right"><template #default="{ row }">{{ capitalWan(row.registeredCapital) }}</template></el-table-column>
      <el-table-column label="业务范围" min-width="150">
        <template #default="{ row }">
          <el-tag v-for="s in row.businessScope" :key="s" size="small" class="scope-tag">{{ s }}</el-tag>
          <span v-if="!row.businessScope?.length">—</span>
        </template>
      </el-table-column>
      <el-table-column label="主要联系人" width="150">
        <template #default="{ row }">{{ row.contact || '—' }}<div class="mini">{{ row.phone || '' }}</div></template>
      </el-table-column>
      <el-table-column label="考察记录" width="100">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openArchives(row)">📦 {{ row.archiveCount }} 个</el-button>
        </template>
      </el-table-column>
      <el-table-column label="关联供应商" min-width="150">
        <template #default="{ row }">
          <template v-if="row.supplierId">
            <a class="lnk" @click="goSupplier(row.supplierId)">{{ row.supplierName }}</a>
            <el-tag size="small" type="info" class="scope-tag">{{ row.supplierStatus }}</el-tag>
          </template>
          <span v-else-if="row.result === '不合格'" class="mini">不列入</span>
          <span v-else>—</span>
          <div v-if="row.decidedAt" class="mini">{{ row.decidedByName || '—' }} · {{ fmtTime(row.decidedAt) }}</div>
          <div v-if="row.conclusion" class="mini" :title="row.conclusion">结论：{{ row.conclusion }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <template v-if="row.result === '待考察'">
            <el-button link type="success" size="small" @click="decide(row, '合格')">合格</el-button>
            <el-button link type="danger" size="small" @click="decide(row, '不合格')">不合格</el-button>
            <el-button link size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
          <span v-else class="mini">已判定</span>
        </template>
      </el-table-column>
    </el-table>
    <div class="mini">判定需「供应链」或「老板」角色，判定后不可更改；需复查请新增一条考察记录。</div>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="formVisible" :title="formMode === 'edit' ? '编辑考察供应商' : '新增考察供应商'" width="600px" :close-on-click-modal="false">
      <el-form :model="form" label-width="110px" size="small" :disabled="saving">
        <el-form-item label="公司名称" required>
          <el-input v-model="form.companyName" maxlength="128" placeholder="工商注册全称" />
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
        <el-form-item label="业务范围" required>
          <el-checkbox-group v-model="form.businessScope">
            <el-checkbox v-for="s in INSPECTION_SCOPES" :key="s" :value="s">{{ s }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="主要联系人"><el-input v-model="form.contact" maxlength="64" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="联系方式"><el-input v-model="form.phone" maxlength="32" /></el-form-item>
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
        <el-upload v-if="archiveRow.result === '待考察'" :http-request="uploadArchiveRequest" :before-upload="beforeArchive"
          :accept="archiveAccept" :show-file-list="false" :disabled="uploading">
          <el-button type="primary" :loading="uploading">上传压缩包</el-button>
        </el-upload>
        <div class="upload-tip">
          {{ archiveRow.result === '待考察' ? archiveHint : '考察已判定，考察记录不可再追加' }}
        </div>
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
.sub { color: #666; font-size: 13px; margin-bottom: 10px; }
.filterbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.cnt { color: #999; font-size: 12px; margin-left: auto; }
.mini { color: #999; font-size: 12px; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lnk { color: #2e6da4; cursor: pointer; font-weight: 600; }
.lnk:hover { text-decoration: underline; }
.scope-tag { margin: 0 4px 2px 0; }
.archive-box { width: 100%; }
.upload-tip { margin-top: 6px; color: #909399; font-size: 12px; line-height: 1.5; }
.attachment-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.progress { margin: 10px 0; }
.archive-table { margin-top: 10px; }
</style>
