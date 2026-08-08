<script setup lang="ts">
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const health = ref('检查中...')
const me = ref<any>(null)

onMounted(async () => {
  try {
    health.value = await request.get('/v1/health')
    me.value = await request.get('/v1/whoami')
  } catch (e) {
    health.value = '后端未连通'
  }
})
</script>

<template>
  <div>
    <el-alert type="success" :closable="false" title="冲刺0 地基已就位" show-icon>
      报价测算器已上线;后端 8082 / 库 worland_dev / 前缀 yc_rent_。M1 业务模块待建。
    </el-alert>
    <el-descriptions :column="1" border style="margin-top: 16px; max-width: 520px">
      <el-descriptions-item label="后端健康">{{ health }}</el-descriptions-item>
      <el-descriptions-item label="当前身份(占位头)">
        <span v-if="me">{{ me.userName }}（{{ me.role }}） · userId={{ me.userId }}</span>
        <span v-else>—</span>
      </el-descriptions-item>
    </el-descriptions>
  </div>
</template>
