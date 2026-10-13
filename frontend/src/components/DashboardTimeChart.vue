<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { isNumber, money, niceScale } from '@/utils/dashboardCharts'
const props = defineProps<{
  points: { period: string; first: number | null; second: number | null }[]
  firstLabel: string; secondLabel: string; title: string; mode?: 'bar' | 'line'
}>()
const selected = ref<number | null>(null)
watch(() => props.points, () => { selected.value = null })
const scale = computed(() => niceScale(props.points.flatMap(p => [p.first, p.second].filter(isNumber)), true))
const unit = computed(() => Math.max(Math.abs(scale.value.max), Math.abs(scale.value.min)) >= 10000 ? 10000 : 1)
const plot = { left: 65, top: 20, width: 535, height: 190 }
const step = computed(() => plot.width / Math.max(1, props.points.length))
const x = (index: number) => plot.left + step.value * (index + .5)
const y = (value: number) => plot.top + plot.height * (scale.value.max - value) / (scale.value.max - scale.value.min)
const barWidth = computed(() => Math.min(19, step.value * .27))
const allZero = computed(() => props.points.length > 0 && props.points.every(p => p.first === 0 && p.second === 0))
const active = computed(() => selected.value == null ? null : props.points[selected.value])
function path(key: 'first' | 'second') {
  let penDown = false
  return props.points.map((p, index) => {
    const value = p[key]
    if (!isNumber(value)) { penDown = false; return '' }
    const command = penDown ? 'L' : 'M'
    penDown = true
    return `${command}${x(index)},${y(value)}`
  }).join(' ')
}
const tickLabel = (value: number) => (value / unit.value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
</script>

<template>
  <div class="time-chart">
    <div class="chart-key"><span><i class="key-first"></i>{{ firstLabel }}</span><span><i class="key-second"></i>{{ secondLabel }}</span><small>单位：{{ unit === 10000 ? '万元' : '元' }}</small></div>
    <div class="chart-scroll">
      <svg viewBox="0 0 630 245" role="group" :aria-label="`${title}，可聚焦月份查看金额`">
        <g v-for="tick in scale.ticks" :key="tick"><line :x1="plot.left" :x2="plot.left + plot.width" :y1="y(tick)" :y2="y(tick)" :class="['grid-line', { baseline: tick === 0 }]"/><text :x="plot.left - 10" :y="y(tick) + 4" text-anchor="end" class="axis-label">{{ tickLabel(tick) }}</text></g>
        <template v-if="mode === 'line'"><path :d="path('first')" class="line first-line"/><path :d="path('second')" class="line second-line"/></template>
        <g v-for="(point, index) in points" :key="point.period" class="month-point" tabindex="0" role="button" :aria-label="`${point.period}，${firstLabel}${money(point.first, 2)}元，${secondLabel}${money(point.second, 2)}元`" @mouseenter="selected = index" @focus="selected = index" @click="selected = index" @keydown.enter="selected = index" @keydown.space.prevent="selected = index">
          <rect :x="x(index) - step / 2 + 1" :y="plot.top" :width="Math.max(0, step - 2)" :height="plot.height + 27" :class="['hit-area', { active: selected === index }]"/>
          <template v-if="mode !== 'line'">
            <rect v-if="isNumber(point.first)" :x="x(index) - barWidth - 2" :y="Math.min(y(point.first), y(0))" :width="barWidth" :height="Math.abs(y(point.first) - y(0))" rx="2" class="first-bar"/>
            <rect v-if="isNumber(point.second)" :x="x(index) + 2" :y="Math.min(y(point.second), y(0))" :width="barWidth" :height="Math.abs(y(point.second) - y(0))" rx="2" class="second-bar"/>
          </template>
          <template v-else><circle v-if="isNumber(point.first)" :cx="x(index)" :cy="y(point.first)" r="4" class="first-bar"/><circle v-if="isNumber(point.second)" :cx="x(index)" :cy="y(point.second)" r="4" class="second-bar"/></template>
          <text :x="x(index)" y="234" text-anchor="middle" class="axis-label">{{ point.period.slice(2) }}</text>
        </g>
        <text v-if="allZero" x="333" y="100" text-anchor="middle" class="zero-label">所选期间暂无金额记录</text>
      </svg>
    </div>
    <div class="chart-readout" aria-live="polite"><template v-if="active"><b>{{ active.period }}</b><span>{{ firstLabel }} <strong>¥{{ money(active.first, 2) }}</strong></span><span>{{ secondLabel }} <strong>¥{{ money(active.second, 2) }}</strong></span></template><template v-else>点击或悬停月份查看金额明细<span class="mobile-hint"> · 图表可左右滑动</span></template></div>
    <details class="data-table"><summary>查看数据表</summary><table><thead><tr><th>月份</th><th>{{ firstLabel }}（元）</th><th>{{ secondLabel }}（元）</th></tr></thead><tbody><tr v-for="point in points" :key="point.period"><th>{{ point.period }}</th><td>{{ money(point.first, 2) }}</td><td>{{ money(point.second, 2) }}</td></tr></tbody></table></details>
  </div>
</template>

<style scoped>
.chart-key { display:flex; gap:18px; align-items:center; flex-wrap:wrap; font-size:12px; color:#535c58; margin:14px 0 4px; }
.chart-key > span { display:flex; align-items:center; gap:7px; }
.chart-key i { width:10px; height:10px; border-radius:2px; display:inline-block; }
.key-first, .first-bar { fill:#bc904b; background:#bc904b; }
.key-second, .second-bar { fill:#328279; background:#328279; }
.chart-key small { margin-left:auto; font-size:11px; color:#707672; }
.chart-scroll { overflow-x:auto; scrollbar-color:#c4b79f #f4f1ea; scrollbar-width:thin; }
svg { width:100%; min-width:500px; display:block; overflow:visible; }
.grid-line { stroke:#e8e9e4; stroke-dasharray:3 4; }
.grid-line.baseline { stroke:#bfc5bc; stroke-dasharray:none; }
.axis-label { fill:#64706a; font-size:11px; font-variant-numeric:tabular-nums; }
.hit-area { fill:transparent; cursor:pointer; }
.hit-area.active { fill:#b9c4b7; fill-opacity:.1; }
.month-point:focus { outline:none; }
.month-point:focus-visible .hit-area { stroke:#7e622f; stroke-width:1.5; }
.line { fill:none; stroke-width:2.5; }
.first-line { stroke:#bc904b; }
.second-line { stroke:#328279; stroke-dasharray:6 3; }
.zero-label { fill:#707672; font-size:13px; }
.chart-readout { min-height:38px; display:flex; flex-wrap:wrap; gap:8px 18px; align-items:center; font-size:11px; color:#69746b; padding:8px 0; }
.chart-readout b, .chart-readout strong { color:#27372e; font-variant-numeric:tabular-nums; }
.mobile-hint { display:none; }
.data-table { font-size:11px; color:#64706a; }
summary { cursor:pointer; width:max-content; padding:4px 0; }
summary:focus-visible { outline:2px solid #8a6e3c; outline-offset:3px; }
table { width:100%; border-collapse:collapse; color:#33443b; margin-top:8px; font-variant-numeric:tabular-nums; }
th,td { padding:7px; border-bottom:1px solid #e5e8e2; text-align:right; }
th:first-child { text-align:left; }
@media(max-width:620px) { .mobile-hint { display:inline; } }
</style>
