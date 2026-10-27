<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { RecognizeKind } from '@/api/purchaseContract'
import { recognizeFromFiles } from '@/utils/recognizeFiles'

/**
 * 拍照 / 选文件 → 自动识别合同、银行回单、发票的关键字段。
 * 支持：图片(jpg/png/webp)、PDF(有文字层的直接取文字，扫描版转图片识别)、txt 文本。
 * 识别结果只是预填：触发 recognized(data, files)，由页面把字段填进表单并把原件(图片/PDF)挂成附件，用户核对后再保存。
 * 手机浏览器点「拍照识别」直接唤起相机。
 */
const props = defineProps<{ kind: RecognizeKind; label?: string }>()
const emit = defineEmits<{ (e: 'recognized', data: any, files: File[]): void }>()

const loading = ref(false)
const cameraInput = ref<HTMLInputElement>()
const albumInput = ref<HTMLInputElement>()
/** 能当原件留存的类型(与回单上传一致)；txt 只用来识别，不留存 */
const KEEP_EXTS = ['jpg', 'jpeg', 'png', 'pdf']
const extOf = (f: File) => (f.name.split('.').pop() || '').toLowerCase()

async function onPick(e: Event) {
  const files = Array.from((e.target as HTMLInputElement).files || [])
  ;(e.target as HTMLInputElement).value = ''
  if (!files.length) return
  loading.value = true
  try {
    const data = await recognizeFromFiles(props.kind, files)
    if (!data) return
    emit('recognized', data, files.filter((f) => KEEP_EXTS.includes(extOf(f))))
    ElMessage.success('已识别并填入，请核对后再保存')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <span class="snap">
    <el-button size="small" type="primary" plain :loading="loading" @click="cameraInput?.click()">
      📷 {{ label || '拍照识别' }}
    </el-button>
    <el-button size="small" plain :loading="loading" @click="albumInput?.click()">📄 选文件识别</el-button>
    <input ref="cameraInput" type="file" accept="image/*" capture="environment" hidden @change="onPick" />
    <input ref="albumInput" type="file" accept="image/jpeg,image/png,image/webp,application/pdf,.pdf,.txt,text/plain" multiple hidden @change="onPick" />
  </span>
</template>

<style scoped>
.snap { display: inline-flex; gap: 8px; align-items: center; }
</style>
