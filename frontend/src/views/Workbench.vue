<script setup lang="ts">
import BrandLockup from '@/components/BrandLockup.vue'
import HandoverOverview from '@/components/HandoverOverview.vue'

/**
 * 企业文化文案（《标志标准规范 V1.0》里的 slogan + 价值观条）。
 *
 * slogan 取自规范的门头招牌用法；VALUES 三条目前是占位，
 * 要改只改这里一处，页面自动跟着变。
 */
const SLOGAN = '淬炼于火 · 锋利如曜'
/** 愿景 / 使命：品牌级表述，放在品牌头里 slogan 之下 */
const MISSION = [
  { k: '愿景', v: '科技赋能云仓' },
  { k: '使命', v: '为云仓提效降本' },
]
const VALUES = [
  { k: '先签约后采购', v: '不压货、不赌行情，每一台设备都对着一份合同' },
  { k: '一台设备一条命', v: '逐件建档，从采购到转让处置全程可追溯' },
  { k: '账实相符', v: '钱该动没动有稽核，单据与台账、凭证三方对得上' },
]

import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchWorkbench, type Workbench } from '@/api/workbench'

const router = useRouter()
const wb = ref<Workbench | null>(null)
const loading = ref(false)
const failed = ref(false)
const updatedAt = ref('')

const levelTag: Record<string, string> = { 红灯: 'danger', 超限: 'danger', 预警: 'warning', 正常: 'success' }
const prioTag: Record<string, string> = { 高: 'danger', 中: 'warning', 低: 'info' }

async function load() {
  if (loading.value) return
  loading.value = true
  failed.value = false
  try {
    wb.value = await fetchWorkbench()
    updatedAt.value = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)
defineExpose({ load })
</script>

<template>
  <div class="wb">
    <!-- 品牌头（曜石智能 · 标志标准规范 V1.0：深色版标志 + slogan） -->
    <div class="brand-hero">
      <div class="bh-main">
        <div class="bh-left">
          <BrandLockup variant="stack" :size="34" />
          <div class="bh-slogan">{{ SLOGAN }}</div>
        </div>
        <div class="bh-right">
          <div class="bh-sub">租赁板块工作台</div>
          <div class="bh-scope">
            <template v-if="wb">{{ wb.userName || '未登录' }}<template v-if="wb.role">（{{ wb.role }}）</template><template v-if="wb.scopeNote"> · {{ wb.scopeNote }}</template></template>
            <template v-else-if="loading">加载中…</template>
            <template v-else>—（无数据）</template>
          </div>
          <el-button class="bh-refresh" size="small" :loading="loading" @click="load">刷新数据</el-button>
        </div>
      </div>
      <!-- 愿景 / 使命 -->
      <div class="bh-mission">
        <div v-for="m in MISSION" :key="m.k" class="bh-m">
          <span class="bh-m-k">{{ m.k }}</span>
          <span class="bh-m-v">{{ m.v }}</span>
        </div>
      </div>

      <!-- 价值观条 -->
      <div class="bh-values">
        <div v-for="it in VALUES" :key="it.k" class="bh-value">
          <b>{{ it.k }}</b><span>{{ it.v }}</span>
        </div>
      </div>
    </div>

    <HandoverOverview :data="wb" :loading="loading" :failed="failed" :updated-at="updatedAt" />
    <el-alert v-if="failed" class="load-error" type="warning" :closable="false" show-icon :title="wb ? '刷新失败，以下保留上次成功数据。请点击刷新数据重试。' : '经营数据暂时无法加载，请点击刷新数据重试。业务地图仍可浏览。'" />

    <el-row :gutter="16">
      <!-- 亮灯红点 -->
      <el-col :xs="24" :lg="10" id="workbench-alerts" class="detail-column">
        <el-card shadow="never">
          <template #header><b>⚠️ 该处理的事（亮灯红点）</b></template>
          <div v-if="!wb?.redPoints?.length" class="empty">{{ loading ? '正在加载提醒…' : !wb ? '暂无可见数据' : '当前账号暂无提醒，请继续核对业务单据' }}</div>
          <RouterLink v-for="r in wb?.redPoints" :key="r.key" class="redpoint" :to="r.link">
            <div class="rp-left">
              <el-tag :type="levelTag[r.level] || 'warning'" effect="dark" size="small">{{ r.level }}</el-tag>
              <span class="rp-label">{{ r.label }}</span>
            </div>
            <div class="rp-right"><b>{{ r.count }}</b><el-icon class="arrow">›</el-icon></div>
          </RouterLink>
        </el-card>
      </el-col>

      <!-- 我的待办 -->
      <el-col :xs="24" :lg="14" id="workbench-tasks" class="detail-column">
        <el-card shadow="never">
          <template #header>
            <b>✅ 我的待办</b>
            <el-tag size="small" type="info" style="margin-left:8px">{{ wb ? wb.myTaskCount : '—' }} 项</el-tag>
            <el-button size="small" text style="float:right" @click="router.push('/task')">进任务中心 ›</el-button>
          </template>
          <el-table :data="wb?.myTasks || []" size="small" :empty-text="loading ? '正在加载待办…' : !wb ? '暂无可见数据' : '暂无待办任务'">
            <el-table-column prop="title" label="任务" min-width="180" />
            <el-table-column prop="type" label="类型" width="110" />
            <el-table-column label="来源" width="80">
              <template #default="{ row }"><el-tag size="small" :type="row.source === '系统' ? 'warning' : 'info'">{{ row.source }}</el-tag></template>
            </el-table-column>
            <el-table-column label="优先级" width="80">
              <template #default="{ row }"><el-tag size="small" :type="prioTag[row.priority] || 'info'">{{ row.priority }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="90" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>


  </div>
</template>

<style scoped>
.wb { padding: 4px; }

/* ===== 品牌头：曜石黑底 + 金字，与标志标准规范的深色版一致 ===== */
.brand-hero {
  background: var(--brand-dark-gradient);
  border: 1px solid var(--brand-gold-line);
  border-radius: 10px;
  padding: 26px 28px 0;
  margin-bottom: 16px;
  overflow: hidden;
}
.bh-main { display: flex; align-items: center; justify-content: space-between; gap: 24px; flex-wrap: wrap; }
.bh-left { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; }
.bh-slogan {
  color: var(--brand-gold-dim);
  font-size: 13px;
  letter-spacing: 0.22em;
  text-indent: 0.22em;
}
.bh-right { text-align: right; display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.bh-sub { color: #e6e6ea; font-size: 15px; font-weight: 600; letter-spacing: 1px; }
.bh-scope { color: #8a8a92; font-size: 12px; }
/* 刷新按钮走金线描边，压在深色底上 */
.bh-refresh {
  background: transparent;
  border-color: var(--brand-gold-line);
  color: var(--brand-gold);
}
.bh-refresh:hover { background: var(--brand-graphite); border-color: var(--brand-gold); color: var(--brand-gold-bright); }

/* 愿景 / 使命：比价值观条更重，亮金字 + 竖金线分隔 */
.bh-mission {
  display: flex;
  gap: 28px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 20px;
}
.bh-m { display: flex; align-items: baseline; gap: 10px; }
.bh-m + .bh-m { padding-left: 28px; border-left: 1px solid var(--brand-gold-line); }
.bh-m-k {
  color: var(--brand-gold);
  font-size: 12px;
  letter-spacing: 0.24em;
  text-indent: 0.24em;
  white-space: nowrap;
}
.bh-m-v {
  color: var(--brand-gold-bright);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-indent: 0.12em;
  white-space: nowrap;
}

/* 价值观条：三栏，金线分隔 */
.bh-values {
  display: flex;
  gap: 0;
  margin: 20px -28px 0;
  border-top: 1px solid var(--brand-gold-line);
  flex-wrap: wrap;
}
.bh-value {
  flex: 1 1 200px;
  padding: 14px 28px;
  border-left: 1px solid rgba(201, 160, 99, 0.16);
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.bh-value:first-child { border-left: none; }
.bh-value b { color: var(--brand-gold-bright); font-size: 13px; letter-spacing: 1px; font-weight: 600; }
.bh-value span { color: #8a8a92; font-size: 12px; line-height: 1.6; }

.empty { color: #c0c4cc; padding: 12px 0; text-align: center; }
.redpoint { display: flex; justify-content: space-between; align-items: center; padding: 10px 6px; border-bottom: 1px dashed #f0f0f0; cursor: pointer; }
.redpoint:hover { background: #fafafa; }
.rp-label { margin-left: 8px; }
.rp-right b { color: #f56c6c; font-size: 18px; margin-right: 4px; }
.arrow { color: #c0c4cc; }
.load-error { margin-bottom: 16px; }
.detail-column { margin-bottom: 16px; scroll-margin-top: 20px; }
.redpoint { color: inherit; text-decoration: none; }
.redpoint:focus-visible { outline: 2px solid #85602e; outline-offset: 2px; }
@media (max-width: 640px) {
  .brand-hero { padding: 20px 16px 0; }
  .bh-right { align-items: flex-start; text-align: left; }
  .bh-values { margin-left: -16px; margin-right: -16px; }
  .bh-value { padding: 12px 16px; }
  .bh-mission { gap: 14px; }
  .bh-m + .bh-m { border: 0; padding-left: 0; }
}
</style>
