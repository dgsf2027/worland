<script setup lang="ts">
import BrandLockup from '@/components/BrandLockup.vue'
import OperationsDashboard from '@/components/OperationsDashboard.vue'
import { useDashboardData } from '@/api/dashboard'

/**
 * 企业文化文案（《标志标准规范 V1.0》里的 slogan + 愿景 / 使命）。
 * 要改只改这里一处，页面自动跟着变。
 */
const SLOGAN = '淬炼于火 · 锋利如曜'
const MISSION = [
  { k: '愿景', v: '科技赋能云仓' },
  { k: '使命', v: '为云仓提效降本' },
]

import { onMounted, ref } from 'vue'
import { fetchWorkbench, type Workbench } from '@/api/workbench'

const wb = ref<Workbench | null>(null)
const loading = ref(false)
const chartsLoading = ref(false)
const failed = ref(false)
const updatedAt = ref('')
const months = ref(6)
const dashboard = useDashboardData()
const charts = dashboard.charts

const now = () => new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })

/** 工作台与图表互不依赖，并行取数；任一失败不拖垮另一块。 */
async function load() {
  if (loading.value) return
  loading.value = true
  chartsLoading.value = true
  failed.value = false
  const [result] = await Promise.allSettled([fetchWorkbench(), dashboard.load(months.value)])
  if (result.status === 'fulfilled') {
    wb.value = result.value
    updatedAt.value = now()
  } else {
    wb.value = null
    failed.value = true
  }
  loading.value = false
  chartsLoading.value = false
}
/** 切换月份只重取图表 */
async function changeMonths(value: number) {
  if (chartsLoading.value) return
  months.value = value
  chartsLoading.value = true
  await dashboard.load(value)
  chartsLoading.value = false
}
onMounted(load)
defineExpose({ load })
</script>

<template>
  <div class="wb">
    <!-- 品牌条（曜石智能 · 标志标准规范 V1.0：深色版标志 + slogan + 愿景 / 使命） -->
    <div class="brand-strip">
      <div class="bs-left">
        <BrandLockup variant="inline" :size="18" :en="false" />
        <span class="bs-slogan">{{ SLOGAN }}</span>
      </div>
      <div class="bs-mission">
        <span v-for="m in MISSION" :key="m.k" class="bs-m"><span class="bs-m-k">{{ m.k }}</span>{{ m.v }}</span>
      </div>
    </div>

    <OperationsDashboard :workbench="wb" :charts="charts" :loading="loading" :charts-loading="chartsLoading" :failed="failed"
      :updated-at="updatedAt" :months="months" @change-months="changeMonths" @refresh="load" />
  </div>
</template>

<style scoped>
.wb { max-width: 1480px; margin: 0 auto; padding: 2px 4px 24px; }

/* 品牌条：曜石黑底 + 金字，与标志标准规范的深色版一致；压成一行，不抢数据的位置 */
.brand-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px 28px;
  flex-wrap: wrap;
  padding: 12px 20px;
  margin-bottom: 18px;
  border-radius: 12px;
  background: var(--brand-dark-gradient);
  border: 1px solid var(--brand-gold-line);
}
.bs-left { display: flex; align-items: center; gap: 18px; min-width: 0; }
.bs-slogan {
  color: var(--brand-gold-dim);
  font-size: 12px;
  letter-spacing: 0.22em;
  padding-left: 18px;
  border-left: 1px solid var(--brand-gold-line);
  white-space: nowrap;
}
.bs-mission { display: flex; align-items: center; gap: 22px; }
.bs-m { display: inline-flex; align-items: baseline; gap: 10px; color: var(--brand-gold-bright); font-size: 14px; font-weight: 600; letter-spacing: 0.12em; white-space: nowrap; }
.bs-m + .bs-m { padding-left: 22px; border-left: 1px solid var(--brand-gold-line); }
.bs-m-k { color: var(--brand-gold); font-size: 11.5px; font-weight: 400; letter-spacing: 0.24em; }
@media (max-width: 760px) {
  .brand-strip { padding: 10px 14px; margin-bottom: 14px; }
  .bs-slogan, .bs-mission { display: none; }
}
</style>
