<script setup lang="ts">
import { computed } from 'vue'
import type { TermRow } from '@/api/asset'

const props = defineProps<{
  modelValue: TermRow[]
  /** 预计付款基数(集采价);为空只校验比例 */
  basePrice?: number | null
  disabled?: boolean
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: TermRow[]): void }>()

const rows = computed(() => props.modelValue)
const totalPct = computed(() => Math.round(rows.value.reduce((s, r) => s + Number(r.ratioPct || 0), 0) * 100) / 100)
const balanced = computed(() => Math.abs(totalPct.value - 100) < 0.01)

/** 预计付款金额:前 N-1 段四舍五入到分,末段补差(与后端同口径) */
const amounts = computed(() => {
  const price = props.basePrice
  if (price == null) return rows.value.map(() => null)
  let acc = 0
  return rows.value.map((r, i) => {
    const v = i === rows.value.length - 1
      ? Math.round((price - acc) * 100) / 100
      : Math.round(price * Number(r.ratioPct || 0)) / 100
    acc += v
    return v
  })
})

function update(next: TermRow[]) {
  emit('update:modelValue', next)
}
function addRow() {
  const left = Math.max(0, Math.round((100 - totalPct.value) * 100) / 100)
  update([...rows.value, { stageName: '', ratioPct: left || null, triggerPoint: '入库', dueDays: 0, dueRule: 'after_days', dueDayOfMonth: null }])
}
function removeRow(i: number) {
  update(rows.value.filter((_, idx) => idx !== i))
}
function applyTemplate(kind: 'three' | 'two' | 'full' | 'monthly') {
  if (kind === 'monthly') {
    update([
      { stageName: '首笔验收款', ratioPct: 40, triggerPoint: '审核通过', dueRule: 'month_end_days', dueDays: 30, dueDayOfMonth: null },
      { stageName: '次月进度款', ratioPct: 30, triggerPoint: '审核通过', dueRule: 'next_month_day', dueDays: 0, dueDayOfMonth: 30 },
      { stageName: '次次月尾款', ratioPct: 30, triggerPoint: '审核通过', dueRule: 'next_2_month_day', dueDays: 0, dueDayOfMonth: 30 },
    ])
  } else if (kind === 'three') {
    update([
      { stageName: '首付', ratioPct: 30, triggerPoint: '下单', dueRule: 'after_days', dueDays: 0, dueDayOfMonth: null },
      { stageName: '验收', ratioPct: 60, triggerPoint: '入库', dueRule: 'after_days', dueDays: 0, dueDayOfMonth: null },
      { stageName: '尾款', ratioPct: 10, triggerPoint: '入库', dueRule: 'after_days', dueDays: 90, dueDayOfMonth: null },
    ])
  } else if (kind === 'two') {
    update([
      { stageName: '预付', ratioPct: 50, triggerPoint: '下单', dueRule: 'after_days', dueDays: 0, dueDayOfMonth: null },
      { stageName: '到货', ratioPct: 50, triggerPoint: '入库', dueRule: 'after_days', dueDays: 30, dueDayOfMonth: null },
    ])
  } else {
    update([{ stageName: '全款', ratioPct: 100, triggerPoint: '入库', dueRule: 'after_days', dueDays: 0, dueDayOfMonth: null }])
  }
}
function isDayOfMonthRule(rule?: string) {
  return rule === 'next_month_day' || rule === 'next_2_month_day'
}
function money(v: number | null) {
  return v == null ? '—' : '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<template>
  <div class="pte">
    <div class="pte-bar">
      <span class="tip">快速套用：</span>
      <el-button link type="primary" size="small" :disabled="disabled" @click="applyTemplate('monthly')">审核月结40/次月30/次次月30</el-button>
      <el-button link type="primary" size="small" :disabled="disabled" @click="applyTemplate('three')">首付30/验收60/尾款10</el-button>
      <el-button link type="primary" size="small" :disabled="disabled" @click="applyTemplate('two')">预付50/到货50</el-button>
      <el-button link type="primary" size="small" :disabled="disabled" @click="applyTemplate('full')">全款</el-button>
    </div>
    <el-table :data="rows" size="small" border>
      <el-table-column label="阶段名称" min-width="105">
        <template #default="{ row }"><el-input v-model="row.stageName" size="small" maxlength="16" placeholder="如 首笔款" :disabled="disabled" /></template>
      </el-table-column>
      <el-table-column label="比例(%)" width="90">
        <template #default="{ row }">
          <el-input-number v-model="row.ratioPct" size="small" :min="0" :max="100" :precision="2" :controls="false" style="width:100%" :disabled="disabled" />
        </template>
      </el-table-column>
      <el-table-column label="触发时点" width="115">
        <template #default="{ row }">
          <el-select v-model="row.triggerPoint" size="small" :disabled="disabled">
            <el-option label="下单" value="下单" />
            <el-option label="入库" value="入库" />
            <el-option label="验收合格" value="验收合格" />
            <el-option label="审核通过" value="审核通过" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="到期规则" width="135">
        <template #default="{ row }">
          <el-select v-model="row.dueRule" size="small" :disabled="disabled" placeholder="触发日+N天">
            <el-option label="触发日+N天" value="after_days" />
            <el-option label="当月月底+N天" value="month_end_days" />
            <el-option label="次月固定日" value="next_month_day" />
            <el-option label="次次月固定日" value="next_2_month_day" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="天数/固定日" width="95">
        <template #default="{ row }">
          <el-input-number
            v-if="isDayOfMonthRule(row.dueRule)"
            v-model="row.dueDayOfMonth"
            size="small" :min="1" :max="31" :precision="0" :controls="false"
            placeholder="月末" style="width:100%" :disabled="disabled"
          />
          <el-input-number
            v-else
            v-model="row.dueDays"
            size="small" :min="0" :precision="0" :controls="false"
            style="width:100%" :disabled="disabled"
          />
        </template>
      </el-table-column>
      <el-table-column label="预计付款金额" width="120" align="right">
        <template #default="{ $index }">{{ money(amounts[$index]) }}</template>
      </el-table-column>
      <el-table-column label="" width="46">
        <template #default="{ $index }">
          <el-button link type="danger" size="small" :disabled="disabled || rows.length <= 1" @click="removeRow($index)">删</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="pte-foot">
      <el-button size="small" :disabled="disabled" @click="addRow">＋ 加一段</el-button>
      <span :class="['sum', balanced ? 'ok' : 'bad']">比例合计 {{ totalPct }}%{{ balanced ? ' ✓' : '（须为 100%）' }}</span>
      <span v-if="basePrice != null" class="tip">预计付款合计 {{ money(basePrice) }}（= 集采价，末段补差）</span>
    </div>
    <div class="tip">支持 下单 / 入库 / 验收合格 / 审核通过 四种触发时点及月结账期规则（月底+N天、次月/次次月固定日，如不填固定日则取当月月底）。</div>
  </div>
</template>

<style scoped>
.pte { width: 100%; }
.pte-bar { display: flex; align-items: center; gap: 4px; margin-bottom: 6px; flex-wrap: wrap; }
.pte-foot { display: flex; align-items: center; gap: 12px; margin-top: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; line-height: 1.6; }
.sum { font-size: 13px; font-weight: 600; }
.sum.ok { color: #2f9e44; }
.sum.bad { color: #e0533d; }
</style>
