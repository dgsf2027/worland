<script setup lang="ts">
/**
 * 按月柱状图（工作台总览专用）。
 * - 全部 sign=1 的系列 → 同月并排柱（如 应收 / 已收）
 * - sign=-1 的系列画在零线下方 → 收付对照（如 计划收款向上 / 计划付款向下），可叠加净额圆点
 * 按容器真实宽度绘制，文字不随缩放变形；悬停或键盘聚焦月份出提示，table=true 时改为数据表。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { compactMoney, isNumber, niceScale, splitPeriod, yuan } from '@/utils/dashboardCharts'

export interface ColumnSeries { key: string; label: string; color: string; sign?: 1 | -1 }
export interface ColumnRow { period: string; values: Record<string, number | null> }

const props = withDefaults(defineProps<{
  rows: ColumnRow[]
  series: ColumnSeries[]
  net?: { key: string; label: string } | null
  title: string
  height?: number
  table?: boolean
}>(), { net: null, height: 232, table: false })

const root = ref<HTMLElement | null>(null)
const width = ref(560)
let observer: ResizeObserver | null = null
onMounted(() => {
  if (!root.value) return
  width.value = root.value.clientWidth || width.value
  observer = new ResizeObserver(entries => {
    const next = Math.round(entries[0].contentRect.width)
    if (next > 0) width.value = next
  })
  observer.observe(root.value)
})
onBeforeUnmount(() => observer?.disconnect())

const active = ref<number | null>(null)
watch(() => props.rows, () => { active.value = null })

const up = computed(() => props.series.filter(s => (s.sign ?? 1) === 1))
const down = computed(() => props.series.filter(s => s.sign === -1))
const signed = (s: ColumnSeries, v: number) => (s.sign === -1 ? -v : v)

const scale = computed(() => {
  const all: number[] = []
  for (const row of props.rows) {
    for (const s of props.series) {
      const v = row.values[s.key]
      if (isNumber(v)) all.push(signed(s, v))
    }
    if (props.net) {
      const n = row.values[props.net.key]
      if (isNumber(n)) all.push(n)
    }
  }
  return niceScale(all, true)
})

// 左侧刻度宽度按最长标签估算（中文字符约 11px，其余约 6.6px）
const labelWidth = (text: string) => [...text].reduce((w, ch) => w + (/[一-鿿]/.test(ch) ? 11 : 6.6), 0)
const tickText = (v: number) => (v === 0 ? '0' : compactMoney(v))
const margin = computed(() => ({
  left: Math.ceil(Math.max(...scale.value.ticks.map(t => labelWidth(tickText(t))), 10)) + 12,
  right: 6,
  top: 18,
  bottom: 38,
}))
const plotW = computed(() => Math.max(60, width.value - margin.value.left - margin.value.right))
const plotH = computed(() => props.height - margin.value.top - margin.value.bottom)
const band = computed(() => plotW.value / Math.max(1, props.rows.length))
const cx = (i: number) => margin.value.left + band.value * (i + 0.5)
const y = (v: number) => {
  const { min, max } = scale.value
  return margin.value.top + plotH.value * (max - v) / (max - min || 1)
}
const zeroY = computed(() => y(0))

const GAP = 2
const barW = computed(() => {
  const perSide = Math.max(up.value.length, down.value.length, 1)
  return Math.max(4, Math.min(22, (band.value * 0.58 - GAP * (perSide - 1)) / perSide))
})

interface Bar { key: string; x: number; y: number; w: number; h: number; dir: 1 | -1; color: string; value: number; path: string }
function barPath(x: number, top: number, w: number, h: number, dir: 1 | -1): string {
  // 数据端 4px 圆角，零线端方角
  const r = Math.min(4, w / 2, h)
  if (dir === 1) {
    const b = top + h
    return `M${x},${b}V${top + r}Q${x},${top} ${x + r},${top}H${x + w - r}Q${x + w},${top} ${x + w},${top + r}V${b}Z`
  }
  const b = top + h
  return `M${x},${top}V${b - r}Q${x},${b} ${x + r},${b}H${x + w - r}Q${x + w},${b} ${x + w},${b - r}V${top}Z`
}
const bars = computed<Bar[][]>(() => props.rows.map((row, i) => {
  const out: Bar[] = []
  for (const group of [up.value, down.value]) {
    const total = group.length * barW.value + (group.length - 1) * GAP
    group.forEach((s, j) => {
      const v = row.values[s.key]
      if (!isNumber(v) || v === 0) return
      const x = cx(i) - total / 2 + j * (barW.value + GAP)
      const sv = signed(s, v)
      // 极小的非零值至少留 2px，让人看得见「有」
      const h = Math.max(Math.abs(y(sv) - zeroY.value), 2)
      const dir: 1 | -1 = sv > 0 ? 1 : -1
      const top = dir === 1 ? zeroY.value - h : zeroY.value
      out.push({ key: s.key, x, y: top, w: barW.value, h, dir, color: s.color, value: sv, path: barPath(x, top, barW.value, h, dir) })
    })
  }
  return out
}))

const netDots = computed(() => {
  if (!props.net) return []
  const key = props.net.key
  return props.rows.map((row, i) => ({ i, v: row.values[key] })).filter((d): d is { i: number; v: number } => isNumber(d.v))
    .map(d => ({ ...d, cx: cx(d.i), cy: y(d.v) }))
})

// 只给全图绝对值最大的一根柱子直接标数，其余靠刻度、提示与数据表
const peak = computed(() => {
  let best: Bar | null = null
  for (const group of bars.value) for (const b of group) if (!best || Math.abs(b.value) > Math.abs(best.value)) best = b
  if (!best) return null
  const text = compactMoney(Math.abs(best.value))
  // 向上的柱标在柱顶；向下的柱标在柱底右侧，避免压到月份轴
  return best.dir === 1
    ? { x: best.x + best.w / 2, y: best.y - 6, anchor: 'middle', text }
    : { x: best.x + best.w + 5, y: best.y + best.h - 2, anchor: 'start', text }
})

const allEmpty = computed(() => props.rows.every(row => props.series.every(s => !isNumber(row.values[s.key]) || row.values[s.key] === 0)))
const labelStep = computed(() => (band.value < 30 ? 2 : 1))
const xLabels = computed(() => props.rows.map((row, i) => {
  const p = splitPeriod(row.period)
  const showYear = i === 0 || p.month === '1月'
  return { i, x: cx(i), month: p.month, year: showYear ? p.year : '', show: i % labelStep.value === 0 || showYear }
}))

const tip = computed(() => {
  if (active.value == null) return null
  const row = props.rows[active.value]
  if (!row) return null
  const p = splitPeriod(row.period)
  const items: { label: string; color: string; value: number | null | undefined; kind: 'line' | 'dot' }[] =
    props.series.map(s => ({ label: s.label, color: s.color, value: row.values[s.key], kind: 'line' }))
  if (props.net) items.push({ label: props.net.label, color: 'var(--viz-ink)', value: row.values[props.net.key], kind: 'dot' })
  const left = cx(active.value)
  const flip = left > width.value - 190
  return { title: p.year ? `${p.year}年${p.month}` : p.month, items, left, flip }
})

function onKey(e: KeyboardEvent, i: number) {
  const next = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : null
  if (next == null || next < 0 || next >= props.rows.length) return
  e.preventDefault()
  const el = root.value?.querySelector<SVGGElement>(`[data-band="${next}"]`)
  el?.focus()
}
function ariaFor(row: ColumnRow): string {
  const parts = props.series.map(s => `${s.label}${yuan(row.values[s.key])}`)
  if (props.net) parts.push(`${props.net.label}${yuan(row.values[props.net.key])}`)
  return `${row.period}：${parts.join('，')}`
}
</script>

<template>
  <div ref="root" class="mcc">
    <template v-if="!table">
      <svg :width="width" :height="height" :viewBox="`0 0 ${width} ${height}`" role="group" :aria-label="`${title}，按左右方向键切换月份`" @pointerleave="active = null">
        <g aria-hidden="true">
          <g v-for="t in scale.ticks" :key="t">
            <line :x1="margin.left" :x2="margin.left + plotW" :y1="y(t)" :y2="y(t)" :class="t === 0 ? 'baseline' : 'grid'" />
            <text :x="margin.left - 8" :y="y(t) + 4" text-anchor="end" class="tick">{{ tickText(t) }}</text>
          </g>
        </g>
        <g v-for="(row, i) in rows" :key="row.period" :data-band="i" class="band" tabindex="0" role="img" :aria-label="ariaFor(row)"
           @pointerenter="active = i" @focus="active = i" @blur="active = null" @keydown="onKey($event, i)">
          <rect :x="margin.left + band * i" :y="margin.top - 8" :width="band" :height="plotH + 8 + margin.bottom" :class="['hit', { on: active === i }]" />
          <path v-for="b in bars[i]" :key="b.key" :d="b.path" :fill="b.color" :class="{ dim: active != null && active !== i }" />
        </g>
        <g aria-hidden="true" pointer-events="none">
          <circle v-for="d in netDots" :key="d.i" :cx="d.cx" :cy="d.cy" r="4.5" class="net-dot" :class="{ dim: active != null && active !== d.i }" />
          <text v-if="peak && !allEmpty" :x="peak.x" :y="peak.y" :text-anchor="peak.anchor" class="peak">{{ peak.text }}</text>
          <template v-for="l in xLabels" :key="l.i">
            <text v-if="l.show" :x="l.x" :y="height - margin.bottom + 17" text-anchor="middle" :class="['month', { on: active === l.i }]">{{ l.month }}</text>
            <text v-if="l.year" :x="l.x" :y="height - margin.bottom + 31" text-anchor="middle" class="year">{{ l.year }}</text>
          </template>
          <text v-if="allEmpty" :x="margin.left + plotW / 2" :y="margin.top + plotH / 2 - 6" text-anchor="middle" class="empty-note">所选期间暂无金额记录</text>
        </g>
      </svg>
      <div v-if="tip" class="tip" :class="{ flip: tip.flip }" :style="{ left: `${tip.left}px` }" role="presentation">
        <div class="tip-title">{{ tip.title }}</div>
        <div v-for="it in tip.items" :key="it.label" class="tip-row">
          <i :class="it.kind === 'line' ? 'k-line' : 'k-dot'" :style="{ background: it.color }"></i>
          <span class="tip-label">{{ it.label }}</span>
          <b>{{ yuan(it.value) }}</b>
        </div>
      </div>
    </template>
    <div v-else class="table-wrap">
      <table>
        <caption class="sr-only">{{ title }}（元）</caption>
        <thead><tr><th scope="col">月份</th><th v-for="s in series" :key="s.key" scope="col">{{ s.label }}</th><th v-if="net" scope="col">{{ net.label }}</th></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.period">
            <th scope="row">{{ row.period }}</th>
            <td v-for="s in series" :key="s.key">{{ yuan(row.values[s.key], 2) }}</td>
            <td v-if="net">{{ yuan(row.values[net.key], 2) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.mcc { position: relative; width: 100%; min-width: 0; }
svg { display: block; overflow: visible; font-family: inherit; }
.grid { stroke: var(--viz-grid); stroke-width: 1; shape-rendering: crispEdges; }
.baseline { stroke: var(--viz-axis); stroke-width: 1; shape-rendering: crispEdges; }
.tick { fill: var(--viz-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.month { fill: var(--viz-secondary); font-size: 12px; }
.month.on { fill: var(--viz-ink); font-weight: 600; }
.year { fill: var(--viz-muted); font-size: 10.5px; }
.peak { fill: var(--viz-secondary); font-size: 11.5px; font-weight: 600; font-variant-numeric: tabular-nums; }
.empty-note { fill: var(--viz-muted); font-size: 13px; }
.band { outline: none; cursor: default; }
.hit { fill: transparent; }
.hit.on { fill: var(--viz-wash); }
.band:focus-visible .hit { stroke: var(--viz-focus); stroke-width: 1.5; }
path, .net-dot { transition: opacity .15s ease; }
.dim { opacity: .45; }
.net-dot { fill: var(--viz-ink); stroke: var(--viz-surface); stroke-width: 2; }
.tip {
  position: absolute; top: 4px; transform: translateX(14px); pointer-events: none; z-index: 2;
  min-width: 168px; padding: 9px 11px; border-radius: 8px; background: var(--viz-surface);
  border: 1px solid var(--viz-border); box-shadow: 0 6px 20px rgba(15, 18, 24, .10); font-size: 12px;
}
.tip.flip { transform: translateX(calc(-100% - 14px)); }
.tip-title { color: var(--viz-secondary); margin-bottom: 6px; }
.tip-row { display: grid; grid-template-columns: 12px 1fr auto; align-items: center; gap: 8px; line-height: 1.9; }
.tip-row b { color: var(--viz-ink); font-weight: 600; font-variant-numeric: tabular-nums; }
.tip-label { color: var(--viz-secondary); }
.k-line { width: 12px; height: 3px; border-radius: 2px; display: block; }
.k-dot { width: 8px; height: 8px; border-radius: 50%; display: block; margin-left: 2px; }
.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: 12.5px; font-variant-numeric: tabular-nums; color: var(--viz-ink); }
th, td { padding: 8px 10px; border-bottom: 1px solid var(--viz-grid); text-align: right; white-space: nowrap; }
thead th { color: var(--viz-secondary); font-weight: 500; }
th:first-child { text-align: left; }
tbody th { font-weight: 500; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
</style>
