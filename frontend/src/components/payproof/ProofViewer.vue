<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadFileBlob } from '@/api/asset'
import { fetchProofs, supplementProofs, type ProofBizType, type ProofView } from '@/api/payProof'
import ProofUploader from './ProofUploader.vue'

/** 查看某张单据的回单;有权限时可补传(历史单据「无回单」用) */
const props = defineProps<{ bizType: ProofBizType; bizId: number; title?: string; canSupplement?: boolean }>()
const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ (e: 'changed', count: number): void }>()

const list = ref<ProofView[]>([])
const loading = ref(false)
const newIds = ref<number[]>([])
const saving = ref(false)

async function load() {
  loading.value = true
  try { list.value = await fetchProofs(props.bizType, props.bizId) } finally { loading.value = false }
}
async function open(p: ProofView) {
  const blob = await downloadFileBlob(p.fileId)
  window.open(URL.createObjectURL(blob), '_blank')
}
async function supplement() {
  saving.value = true
  try {
    await supplementProofs(props.bizType, props.bizId, newIds.value)
    ElMessage.success('回单已补传')
    newIds.value = []
    await load()
    emit('changed', list.value.length)
  } finally { saving.value = false }
}
watch(visible, (v) => { if (v) { newIds.value = []; load() } })
</script>

<template>
  <el-dialog v-model="visible" :title="title || '回单'" width="520px" destroy-on-close>
    <div v-loading="loading">
      <el-empty v-if="!list.length" description="历史无回单" :image-size="56" />
      <div v-for="p in list" :key="p.proofId" class="pv-row">
        <el-link type="primary" @click="open(p)">📎 {{ p.fileName }}</el-link>
        <span class="pv-meta">{{ p.action }} · {{ p.createByName || '—' }} · {{ (p.createTime || '').replace('T', ' ').slice(0, 16) }}</span>
      </div>
      <template v-if="canSupplement">
        <el-divider />
        <ProofUploader v-model="newIds" />
      </template>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button v-if="canSupplement" type="primary" :disabled="!newIds.length" :loading="saving" @click="supplement">补传</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.pv-row { display: flex; flex-direction: column; padding: 6px 0; border-bottom: 1px dashed #ebeef5; }
.pv-meta { font-size: 12px; color: #909399; }
</style>
