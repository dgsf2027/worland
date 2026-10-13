export function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

export function money(value: unknown, digits = 0): string {
  if (!isNumber(value)) return '—'
  const precision = isNumber(digits) ? Math.max(0, Math.min(20, Math.trunc(digits))) : 0
  return value.toLocaleString('zh-CN', {
    minimumFractionDigits: precision,
    maximumFractionDigits: precision,
  })
}

export interface ChartScale {
  min: number
  max: number
  ticks: number[]
}

/** Use includeNegative for signed measures such as profit or net cash flow. */
export function niceScale(values: readonly number[], includeNegative = false): ChartScale {
  const valid = values.filter(value => isNumber(value) && (includeNegative || value >= 0))
  let minimum = 0
  let maximum = 0
  for (const value of valid) {
    minimum = Math.min(minimum, value)
    maximum = Math.max(maximum, value)
  }
  if (minimum === 0 && maximum === 0) return { min: 0, max: 1, ticks: [0, 0.2, 0.4, 0.6, 0.8, 1] }

  // Divide before subtracting so large signed values do not overflow the span.
  const rawStep = maximum / 5 - minimum / 5
  const magnitude = 10 ** Math.floor(Math.log10(rawStep || Number.MIN_VALUE)) || Number.MIN_VALUE
  const ratio = rawStep / magnitude
  const factor = [1, 2, 2.5, 5, 10].find(candidate => candidate >= ratio) ?? 10
  const step = magnitude * factor
  const firstIndex = Math.floor(minimum / step)
  const lastIndex = Math.ceil(maximum / step)
  const clean = (value: number) => value === 0 ? 0 : Number(value.toPrecision(14))
  const bounded = (value: number) => Math.max(-Number.MAX_VALUE, Math.min(Number.MAX_VALUE, value))
  const min = bounded(clean(firstIndex * step))
  const max = bounded(clean(lastIndex * step))
  const ticks: number[] = []
  for (let index = firstIndex; index <= lastIndex; index += 1) {
    const tick = bounded(clean(index * step))
    if (ticks[ticks.length - 1] !== tick) ticks.push(tick)
  }
  return { min, max, ticks }
}

export interface TrendPoint {
  period: string
  value: unknown
}

export interface AlignedTrendPoint {
  period: string
  first: number | null
  second: number | null
}

/** Align calendar months, retaining missing values as gaps. Last duplicate wins. */
export function alignTrends(first: readonly TrendPoint[], second: readonly TrendPoint[]): AlignedTrendPoint[] {
  const toMap = (points: readonly TrendPoint[]) => {
    const result = new Map<string, number | null>()
    for (const point of points) {
      if (/^\d{4}-(0[1-9]|1[0-2])$/.test(point.period)) {
        result.set(point.period, isNumber(point.value) ? point.value : null)
      }
    }
    return result
  }
  const firstByMonth = toMap(first)
  const secondByMonth = toMap(second)
  return [...new Set([...firstByMonth.keys(), ...secondByMonth.keys()])]
    .sort()
    .map(period => ({
      period,
      first: firstByMonth.get(period) ?? null,
      second: secondByMonth.get(period) ?? null,
    }))
}

export interface DonutItem {
  label: string
  value: unknown
  color: string
}

export interface DonutSegment {
  label: string
  value: number
  color: string
  /** Fraction of the total, in [0, 1]. */
  share: number
  /** Starting fraction around the circumference, in [0, 1]. */
  offset: number
}

export function buildDonutSegments(items: readonly DonutItem[]): { total: number; segments: DonutSegment[] } {
  const valid = items.filter((item): item is DonutItem & { value: number } => isNumber(item.value) && item.value >= 0)
  const total = valid.reduce((sum, item) => sum + item.value, 0)
  let offset = 0
  const segments = valid.map(item => {
    const share = total > 0 ? item.value / total : 0
    const segment = { ...item, share, offset }
    offset += share
    return segment
  })
  return { total, segments }
}
