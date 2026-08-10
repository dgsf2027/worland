<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  importApi, type TemplateResp, type PreviewResp, type JobRow,
} from '@/api/imports'

const target = ref('supplier')
const targets = [
  { v: 'supplier', l: '供应商' },
  { v: 'customer', l: '客户' },
  { v: 'asset', l: '设备' },
]
const tpl = ref<TemplateResp | null>(null)
const preview = ref<PreviewResp | null>(null)
const jobs = ref<JobRow[]>([])
const file = ref<File | null>(null)
const projectId = ref<number | undefined>(undefined)
const loading = ref(false)

async function loadTpl() {
  tpl.value = await importApi.template(target.value)
  preview.value = null
  file.value = null
}
async function loadJobs() { jobs.value = await importApi.list() }

function onFile(f: any) {
  file.value = f.raw
  return false
}
async function doPreview() {
  if (!file.value) { ElMessage.warning('请先选择 .xlsx 文件'); return }
  loading.value = true
  try {
    preview.value = await importApi.preview(target.value, file.value, undefined, projectId.value)
    ElMessage.success(`预览完成:通过 ${preview.value.okRows} · 重复 ${preview.value.dupRows} · 失败 ${preview.value.errRows}`)
  } finally {
    loading.value = false
  }
}
async function doCommit() {
  if (!preview.value) return
  const r = await importApi.commit(preview.value.jobId)
  ElMessage.success(`入库:成功 ${r.imported} · 跳过重复 ${r.skippedDup} · 失败 ${r.failed}`)
  preview.value = null
  file.value = null
  await loadJobs()
}

const rowTag: Record<string, string> = { ok: 'success', dup: 'warning', err: 'danger' }
const statusTag: Record<string, string> = { 待确认: 'warning', 已导入: 'success', 已作废: 'info' }

onMounted(() => { loadTpl(); loadJobs() })
</script>

<template>
  <div class="import-center">
    <el-card shadow="never">
      <template #header>
        <b>导入中心</b>
        <span class="hint">映射 / 预览 / 去重 → 走正常单据同一校验+事件流(禁直写派生·公式注入转义·越权拒)</span>
      </template>

      <div class="toolbar">
        <span>导入目标:</span>
        <el-radio-group v-model="target" @change="loadTpl">
          <el-radio-button v-for="t in targets" :key="t.v" :label="t.v">{{ t.l }}</el-radio-button>
        </el-radio-group>
        <span style="margin-left:16px">目标项目ID(选填):</span>
        <el-input-number v-model="projectId" :min="1" size="small" style="width:120px" controls-position="right" />
        <el-upload :auto-upload="false" :show-file-list="false" :on-change="onFile" accept=".xlsx" style="margin-left:16px">
          <el-button size="small">选择 .xlsx</el-button>
        </el-upload>
        <span v-if="file" class="fname">{{ file.name }}</span>
        <el-button type="primary" size="small" :loading="loading" @click="doPreview" style="margin-left:12px">上传预览</el-button>
      </div>

      <div v-if="tpl" class="tpl">
        目标字段(去重键:<b>{{ tpl.dedupKeyLabel }}</b>):
        <el-tag v-for="f in tpl.fields" :key="f.key" size="small" :type="f.required ? 'danger' : 'info'" effect="plain" style="margin:2px">
          {{ f.label }}{{ f.required ? ' *' : '' }}
        </el-tag>
      </div>
    </el-card>

    <!-- 预览结果 -->
    <el-card v-if="preview" shadow="never" style="margin-top:14px">
      <template #header>
        <b>预览结果 · {{ preview.no }}</b>
        <div style="float:right">
          <el-tag type="success" effect="plain">通过 {{ preview.okRows }}</el-tag>
          <el-tag type="warning" effect="plain" style="margin:0 6px">重复 {{ preview.dupRows }}</el-tag>
          <el-tag type="danger" effect="plain">失败 {{ preview.errRows }}</el-tag>
          <el-tag v-if="preview.escapedCells > 0" type="info" effect="plain" style="margin-left:6px">🛡 公式转义 {{ preview.escapedCells }} 格</el-tag>
          <el-button type="primary" size="small" style="margin-left:12px" @click="doCommit" :disabled="preview.okRows === 0">确认入库(ok行·dup跳过)</el-button>
        </div>
      </template>
      <el-table :data="preview.rows" size="small" border max-height="420">
        <el-table-column prop="rowNo" label="行" width="56" />
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="rowTag[row.status]">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="校验说明" min-width="240">
          <template #default="{ row }">
            <span>{{ row.message }}</span>
            <el-tag v-if="row.escaped" size="small" type="info" style="margin-left:6px">含转义</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据" min-width="260">
          <template #default="{ row }">
            <span class="cell" v-for="(v, k) in row.data" :key="k"><em>{{ k }}</em>{{ v }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 作业历史 -->
    <el-card shadow="never" style="margin-top:14px">
      <template #header><b>导入作业历史</b></template>
      <el-table :data="jobs" size="small" border>
        <el-table-column prop="no" label="作业号" width="150" />
        <el-table-column prop="targetType" label="目标" width="90" />
        <el-table-column prop="fileName" label="文件" min-width="150" show-overflow-tooltip />
        <el-table-column prop="totalRows" label="总行" width="70" />
        <el-table-column prop="okRows" label="通过" width="70" />
        <el-table-column prop="dupRows" label="重复" width="70" />
        <el-table-column prop="errRows" label="失败" width="70" />
        <el-table-column prop="escapedCells" label="转义格" width="80" />
        <el-table-column prop="importedRows" label="已入库" width="80" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag[row.status]">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" label="导入人" width="90" />
        <el-table-column prop="createTime" label="时间" width="160" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.hint { color: #909399; font-size: 12px; margin-left: 10px; }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.fname { color: #409eff; font-size: 13px; }
.tpl { margin-top: 12px; color: #606266; font-size: 13px; }
.cell { display: inline-block; margin-right: 10px; font-size: 12px; }
.cell em { color: #c0c4cc; font-style: normal; margin-right: 3px; }
</style>
