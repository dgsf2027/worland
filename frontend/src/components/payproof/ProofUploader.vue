<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { checkUploadFile } from '@/api/asset'
import { uploadProofFile, PROOF_EXTS, PROOF_MAX_MB, type UploadedProof } from '@/api/payProof'
import type { RecognizeKind } from '@/api/purchaseContract'
import { recognizeFromFiles } from '@/utils/recognizeFiles'

/**
 * 回单上传:选文件即上传,v-model 是已上传文件的 id 列表。移除只是不挂到本次登记上。
 * 传 recognizeKind(合同/回单/发票)时：选文件的同时自动识别，识别出的字段通过 recognized 事件交给页面预填。
 */
const props = defineProps<{ recognizeKind?: RecognizeKind }>()
const emit = defineEmits<{ (e: 'recognized', data: any): void }>()
const fileIds = defineModel<number[]>({ default: () => [] })
const recognizing = ref(false)
const progress = ref('')
const files = ref<UploadedProof[]>([])
const uploading = ref(false)
const input = ref<HTMLInputElement>()

/** 上传一批文件并挂到本次登记上(选文件、拍照识别后都走这里) */
async function addFiles(picked: File[]) {
  for (const f of picked) {
    const err = checkUploadFile(f, PROOF_EXTS, PROOF_MAX_MB)
    if (err) { ElMessage.warning(err); return }
  }
  uploading.value = true
  try {
    for (const f of picked) files.value.push(await uploadProofFile(f))
    fileIds.value = files.value.map((f) => f.id)
  } finally {
    uploading.value = false
  }
}
async function onPick(e: Event) {
  const picked = Array.from((e.target as HTMLInputElement).files || [])
  ;(e.target as HTMLInputElement).value = ''
  if (!props.recognizeKind) { await addFiles(picked); return }
  // 存档与识别同时进行：识别失败不影响文件已挂上
  recognizing.value = true
  try {
    const [, data] = await Promise.all([
      addFiles(picked),
      recognizeFromFiles(props.recognizeKind, picked, (m) => { progress.value = m }),
    ])
    if (data) {
      emit('recognized', data)
      ElMessage.success('已识别并填入，请核对后再保存')
    }
  } finally {
    recognizing.value = false
  }
}
/** 清空已选(弹窗重开时用) */
function reset() {
  files.value = []
  fileIds.value = []
}
defineExpose({ addFiles, reset })
function remove(i: number) {
  files.value.splice(i, 1)
  fileIds.value = files.value.map((f) => f.id)
}
</script>

<template>
  <div class="pu">
    <div v-for="(f, i) in files" :key="f.id" class="pu-file">
      <span>📎 {{ f.fileName }}</span>
      <el-button link type="danger" size="small" @click="remove(i)">移除</el-button>
    </div>
    <el-button size="small" :loading="uploading || recognizing" @click="input?.click()">
      {{ recognizeKind ? '＋ 上传并自动识别' : '＋ 上传回单' }}
    </el-button>
    <span v-if="recognizing" class="pu-tip">{{ progress || '正在识别，请稍候…' }}（扫描件每页约 5–10 秒，首次使用还要加载识别引擎）</span>
    <span v-else-if="recognizeKind" class="pu-tip">选文件后自动识别并填入表单，原件同时留存；jpg/png/pdf，单个 ≤ {{ PROOF_MAX_MB }}MB，可多张；上传后不可删除</span>
    <span v-else class="pu-tip">银行回单或支付截图，jpg/png/pdf，单个 ≤ {{ PROOF_MAX_MB }}MB，可多张；上传后不可删除</span>
    <input ref="input" type="file" accept=".jpg,.jpeg,.png,.pdf" multiple hidden @change="onPick" />
  </div>
</template>

<style scoped>
.pu { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; width: 100%; }
.pu-file { font-size: 13px; display: flex; gap: 8px; align-items: center; }
.pu-tip { font-size: 12px; color: #909399; }
</style>
