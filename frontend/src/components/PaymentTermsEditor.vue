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
  update([...rows.value, { stageName: '', ratioPct: left || null, triggerPoint: '入库', dueDays: 0 }])
}
function removeRow(i: number) {
  update(rows.value.filter((_, idx) => idx !== i))
}
function applyTemplate(kind: 'three' | 'two' | 'full') {
  if (kind === 'three') {
    update([
      { stageName: '首付', ratioPct: 30, triggerPoint: '下单', dueDays: 0 },
      { stageName: '验收', ratioPct: 60, triggerPoint: '入库', dueDays: 0 },
      { stageName: '尾款', ratioPct: 10, triggerPoint: '入库', dueDays: 90 },
    ])
  } else if (kind === 'two') {
    update([
      { stageName: '预付', ratioPct: 50, triggerPoint: '下单', dueDays: 0 },
      { stageName: '到货', ratioPct: 50, triggerPoint: '入库', dueDays: 30 },
    ])
  } else {
    update([{ stageName: '全款', ratioPct: 100, triggerPoint: '入库', dueDays: 0 }])
  }
}
function money(v: number | null) {
  return v == null ? '—' : '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<template>
  <div class="pte">
    <div class="pte-bar">
      <span class="tip">快速套用：</span>
      <el-button link type="primary" size="small" :disabled="disabled" @click="applyTemplate('three')">首付30/验收60/尾款10</el-button>
      <el-button link type="primary" size="small" :disabled="disabled" @click="applyTemplate('two')">预付50/到货50</el-button>
      <el-button link type="primary" size="small" :disabled="disabled" @click="applyTemplate('full')">全款</el-button>
    </div>
    <el-table :data="rows" size="small" border>
      <el-table-column label="阶段名称" min-width="110">
        <template #default="{ row }"><el-input v-model="row.stageName" size="small" maxlength="16" placeholder="如 预付" :disabled="disabled" /></template>
      </el-table-column>
      <el-table-column label="比例(%)" width="110">
        <template #default="{ row }">
          <el-input-number v-model="row.ratioPct" size="small" :min="0" :max="100" :precision="2" :controls="false" style="width:100%" :disabled="disabled" />
        </template>
      </el-table-column>
      <el-table-column label="触发时点" width="120">
        <template #default="{ row }">
          <el-select v-model="row.triggerPoint" size="small" :disabled="disabled">
            <el-option label="下单" value="下单" />
            <el-option label="入库" value="入库" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="到期天数" width="100">
        <template #default="{ row }">
          <el-input-number v-model="row.dueDays" size="small" :min="0" :precision="0" :controls="false" style="width:100%" :disabled="disabled" />
        </template>
      </el-table-column>
      <el-table-column label="预计付款金额" width="130" align="right">
        <template #default="{ $index }">{{ money(amounts[$index]) }}</template>
      </el-table-column>
      <el-table-column label="" width="50">
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
    <div class="tip">触发时点=下单：采购下单时生成应付，到期日 = 下单日 + 到期天数；=入库：入库时生成，到期日 = 入库日 + 到期天数。</div>
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
