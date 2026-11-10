<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { checkUploadFile } from '@/api/asset'
import { PROOF_EXTS, PROOF_MAX_MB, uploadProofFile } from '@/api/payProof'
import { recognizeFromFiles } from '@/utils/recognizeFiles'
import { newReceiptDraft, readReceiptDraft, type ReceiptPaymentDraft } from '@/utils/receiptPayments'

const props = defineProps<{ disabled?: boolean; supplierName?: string }>()
const rows = defineModel<ReceiptPaymentDraft[]>({ required: true })
const busy = defineModel<boolean>('busy', { default: false })
const input = ref<HTMLInputElement>()
const camera = ref<HTMLInputElement>()
const progress = ref('')
let sequence = 0
let alive = true
const previewUrls = new Map<number, string>()
onBeforeUnmount(() => { alive = false; previewUrls.forEach((url) => URL.revokeObjectURL(url)) })

async function process(row: ReceiptPaymentDraft) {
  await readReceiptDraft(row, uploadProofFile, async (files) => {
    const result = await recognizeFromFiles('receipt', files, (message) => { progress.value = message })
    if (result?.payeeName && props.supplierName && !result.payeeName.includes(props.supplierName) && !props.supplierName.includes(result.payeeName)) {
      ElMessage.warning(`「${row.fileName}」收款方与合同供应商不一致，请核对`)
    }
    return result
  })
}
async function pick(event: Event) {
  const target = event.target as HTMLInputElement
  const picked = Array.from(target.files || [])
  target.value = ''
  if (busy.value || props.disabled || !picked.length) return
  if (rows.value.length + picked.length > 20) { ElMessage.warning('一次最多登记 20 笔付款'); return }
  const error = picked.map((file) => checkUploadFile(file, PROOF_EXTS, PROOF_MAX_MB)).find(Boolean)
  if (error) { ElMessage.warning(error); return }
  busy.value = true
  try {
    for (const file of picked) {
      if (!alive) break
      if (rows.value.some((row) => row.file.name === file.name && row.file.size === file.size && row.file.lastModified === file.lastModified)) {
        ElMessage.warning(`「${file.name}」已选择，请勿重复上传`)
        continue
      }
      rows.value.push(newReceiptDraft(file, ++sequence))
      await process(rows.value[rows.value.length - 1])
    }
  } finally { busy.value = false; progress.value = '' }
}
async function retry(row: ReceiptPaymentDraft) {
  if (busy.value || props.disabled) return
  busy.value = true
  try { await process(row) } finally { busy.value = false; progress.value = '' }
}
function preview(row: ReceiptPaymentDraft) {
  if (!previewUrls.has(row.key)) previewUrls.set(row.key, URL.createObjectURL(row.file))
  window.open(previewUrls.get(row.key), '_blank', 'noopener')
}
function remove(index: number) {
  const [row] = rows.value.splice(index, 1)
  const url = previewUrls.get(row.key)
  if (url) { URL.revokeObjectURL(url); previewUrls.delete(row.key) }
}
</script>

<template>
  <div>
    <div class="receipt-actions">
      <el-button type="primary" plain :loading="busy" :disabled="disabled" @click="input?.click()">上传回单，逐笔识别</el-button>
      <el-button :disabled="busy || disabled" @click="camera?.click()">拍照添加一笔</el-button>
      <input ref="input" hidden type="file" accept=".jpg,.jpeg,.png,.pdf" multiple @change="pick" />
      <input ref="camera" hidden type="file" accept="image/jpeg,image/png" capture="environment" @change="pick" />
    </div>
    <p class="receipt-hint">每个文件对应一笔实际付款。两张回单请选择两个文件；合并在一个 PDF 中的不同交易请先拆分。支持 JPG、PNG、PDF，单个不超过 10MB。</p>
    <p v-if="busy" class="receipt-hint" role="status">{{ progress || '正在上传回单…' }}</p>
    <el-empty v-if="!rows.length" description="上传回单后，将逐笔显示金额、日期与流水号" :image-size="50" />
    <div v-for="(row, index) in rows" :key="row.key" class="receipt-card">
      <div class="receipt-heading">
        <strong>第 {{ index + 1 }} 笔</strong>
        <el-button link type="primary" @click="preview(row)">{{ row.fileName }}</el-button>
        <el-button link type="danger" :disabled="busy || disabled" @click="remove(index)">移除</el-button>
      </div>
      <el-alert v-if="row.error" :title="row.error" type="warning" :closable="false" />
      <el-form label-width="90px" :disabled="busy || disabled">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="付款金额" required><el-input-number v-model="row.amount" :min="0.01" :precision="2" :controls="false" style="width: 100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="付款日期" required><el-date-picker v-model="row.paidDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="银行流水号"><el-input v-model="row.bankSerial" maxlength="64" placeholder="填写此张回单的流水号，请勿填写合并流水号" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="row.remark" maxlength="255" /></el-form-item>
      </el-form>
      <el-button v-if="row.status === 'failed'" size="small" :disabled="busy || disabled" @click="retry(row)">重试上传</el-button>
    </div>
  </div>
</template>

<style scoped>
.receipt-actions, .receipt-heading { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.receipt-heading { margin-bottom: 12px; }
.receipt-heading .el-button { max-width: 100%; white-space: normal; overflow-wrap: anywhere; }
.receipt-hint { font-size: 12px; color: #606266; line-height: 1.6; }
.receipt-card { border: 1px solid #dcdfe6; border-radius: 6px; padding: 14px; margin: 12px 0; }
.receipt-card .el-alert { margin-bottom: 12px; }
</style>
