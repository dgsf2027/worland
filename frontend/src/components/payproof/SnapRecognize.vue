<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { checkUploadFile } from '@/api/asset'
import { recognizePcDoc, type RecognizeKind } from '@/api/purchaseContract'

/**
 * 拍照 / 选图 → 自动识别合同、银行回单、发票的关键字段。
 * 识别结果只是预填：触发 recognized(data, files)，由页面把字段填进表单并把原图挂成附件，用户核对后再保存。
 * 手机浏览器点「拍照识别」直接唤起相机；电脑上两个按钮都是选图片。只识别图片（jpg/png/webp），PDF 请走「上传原件」。
 */
const props = defineProps<{ kind: RecognizeKind; label?: string }>()
const emit = defineEmits<{ (e: 'recognized', data: any, files: File[]): void }>()

const loading = ref(false)
const cameraInput = ref<HTMLInputElement>()
const albumInput = ref<HTMLInputElement>()
const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'webp']

async function onPick(e: Event) {
  const files = Array.from((e.target as HTMLInputElement).files || [])
  ;(e.target as HTMLInputElement).value = ''
  if (!files.length) return
  for (const f of files) {
    const err = checkUploadFile(f, IMAGE_EXTS, 8)
    if (err) { ElMessage.warning(err + '（识别只支持图片）'); return }
  }
  loading.value = true
  try {
    const data = await recognizePcDoc(props.kind, files)
    emit('recognized', data, files)
    ElMessage.success('已识别并填入，请核对后再保存')
  } catch {
    // 错误提示由请求拦截器统一弹出；识别失败不影响手工录入
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
    <el-button size="small" plain :loading="loading" @click="albumInput?.click()">🖼 选图识别</el-button>
    <input ref="cameraInput" type="file" accept="image/*" capture="environment" hidden @change="onPick" />
    <input ref="albumInput" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden @change="onPick" />
  </span>
</template>

<style scoped>
.snap { display: inline-flex; gap: 8px; align-items: center; }
</style>
