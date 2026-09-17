<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { checkUploadFile, downloadFileBlob } from '@/api/asset'
import { deleteInvPhoto, fetchInvPhotos, uploadInvPhoto, PHOTO_EXTS, PHOTO_MAX_MB, type PhotoFile, type PhotoBizType } from '@/api/inventory'

/** 图片附件(资产照片 / 出入库现场照片 / 考察产品图片):缩略图预览 + 拍照上传 + 删除 */
const props = defineProps<{
  bizType: PhotoBizType
  bizId: number
  editable?: boolean
}>()
const emit = defineEmits<{ (e: 'changed', count: number): void }>()

const files = ref<PhotoFile[]>([])
const urls = ref<Record<number, string>>({})
const loading = ref(false)
const uploading = ref(false)
const input = ref<HTMLInputElement>()

function revokeAll() {
  Object.values(urls.value).forEach((u) => URL.revokeObjectURL(u))
  urls.value = {}
}

async function load() {
  loading.value = true
  try {
    files.value = await fetchInvPhotos(props.bizType, props.bizId)
    revokeAll()
    for (const f of files.value) {
      try {
        const blob = await downloadFileBlob(f.id)
        urls.value = { ...urls.value, [f.id]: URL.createObjectURL(blob) }
      } catch { /* 单张失败不影响其它 */ }
    }
  } finally {
    loading.value = false
  }
}

async function onPick(e: Event) {
  const picked = Array.from((e.target as HTMLInputElement).files || [])
  ;(e.target as HTMLInputElement).value = ''
  if (!picked.length) return
  for (const f of picked) {
    const err = checkUploadFile(f, PHOTO_EXTS, PHOTO_MAX_MB)
    if (err) { ElMessage.warning(err); return }
  }
  uploading.value = true
  try {
    for (const f of picked) await uploadInvPhoto(props.bizType, props.bizId, f)
    ElMessage.success(`已上传 ${picked.length} 张`)
    await load()
    emit('changed', files.value.length)
  } finally {
    uploading.value = false
  }
}

async function onDelete(f: PhotoFile) {
  await ElMessageBox.confirm(`删除照片「${f.fileName}」？`, '删除照片', { type: 'warning' })
  await deleteInvPhoto(f.id)
  ElMessage.success('已删除')
  await load()
  emit('changed', files.value.length)
}

const previewList = () => files.value.map((f) => urls.value[f.id]).filter(Boolean)

watch(() => [props.bizType, props.bizId], load, { immediate: true })
onBeforeUnmount(revokeAll)
defineExpose({ reload: load })
</script>

<template>
  <div class="pg" v-loading="loading">
    <div v-for="(f, i) in files" :key="f.id" class="pg-cell">
      <el-image v-if="urls[f.id]" :src="urls[f.id]" fit="cover" class="pg-img" :preview-src-list="previewList()" :initial-index="i" preview-teleported />
      <div v-else class="pg-img pg-ph">{{ f.fileName.split('.').pop()?.toUpperCase() }}</div>
      <el-button v-if="editable" class="pg-del" type="danger" circle size="small" @click="onDelete(f)">×</el-button>
    </div>
    <div v-if="editable" class="pg-cell pg-add" @click="input?.click()">
      <span v-if="!uploading">＋<br /><small>拍照/上传</small></span>
      <span v-else>上传中…</span>
    </div>
    <span v-if="!editable && !files.length && !loading" class="pg-empty">暂无照片</span>
    <input ref="input" type="file" accept="image/*" multiple hidden @change="onPick" />
  </div>
</template>

<style scoped>
.pg { display: flex; flex-wrap: wrap; gap: 8px; min-height: 40px; }
.pg-cell { position: relative; width: 88px; height: 88px; }
.pg-img { width: 88px; height: 88px; border-radius: 6px; border: 1px solid #ebeef5; }
.pg-ph { display: flex; align-items: center; justify-content: center; color: #909399; font-size: 12px; background: #f5f7fa; }
.pg-del { position: absolute; top: -6px; right: -6px; }
.pg-add { border: 1px dashed #c0c4cc; border-radius: 6px; display: flex; align-items: center; justify-content: center; text-align: center; color: #909399; cursor: pointer; line-height: 1.3; }
.pg-add:hover { border-color: #409eff; color: #409eff; }
.pg-empty { color: #909399; font-size: 12px; align-self: center; }
</style>
