<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { checkUploadFile } from '@/api/asset'
import { uploadProofFile, PROOF_EXTS, PROOF_MAX_MB, type UploadedProof } from '@/api/payProof'

/** 回单上传:选文件即上传,v-model 是已上传文件的 id 列表。移除只是不挂到本次登记上。 */
const fileIds = defineModel<number[]>({ default: () => [] })
const files = ref<UploadedProof[]>([])
const uploading = ref(false)
const input = ref<HTMLInputElement>()

async function onPick(e: Event) {
  const picked = Array.from((e.target as HTMLInputElement).files || [])
  ;(e.target as HTMLInputElement).value = ''
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
    <el-button size="small" :loading="uploading" @click="input?.click()">＋ 上传回单</el-button>
    <span class="pu-tip">银行回单或支付截图，jpg/png/pdf，单个 ≤ {{ PROOF_MAX_MB }}MB，可多张；上传后不可删除</span>
    <input ref="input" type="file" accept=".jpg,.jpeg,.png,.pdf" multiple hidden @change="onPick" />
  </div>
</template>

<style scoped>
.pu { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; width: 100%; }
.pu-file { font-size: 13px; display: flex; gap: 8px; align-items: center; }
.pu-tip { font-size: 12px; color: #909399; }
</style>
