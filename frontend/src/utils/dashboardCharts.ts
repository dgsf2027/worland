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

const MINUS = '−'

/** 金额短写：1 万以下千分位，1 万起「万」，1 亿起「亿」，最多两位小数并去掉尾零；负数用真正的减号。 */
export function compactMoney(value: unknown): string {
  if (!isNumber(value)) return '—'
  const abs = Math.abs(value)
  const sign = value < 0 ? MINUS : ''
  const trim = (n: number) => n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
  if (abs >= 1e8) return `${sign}${trim(abs / 1e8)}亿`
  if (abs >= 1e4) return `${sign}${trim(abs / 1e4)}万`
  return `${sign}${trim(abs)}`
}

/** 带货币符号的金额，符号放在 ¥ 前面：−¥1,240。digits 为小数位。 */
export function yuan(value: unknown, digits = 0): string {
  if (!isNumber(value)) return '—'
  return `${value < 0 ? MINUS : ''}¥${money(Math.abs(value), digits)}`
}

/** 带货币符号的短写金额：−¥129.41万。 */
export function compactYuan(value: unknown): string {
  if (!isNumber(value)) return '—'
  return `${value < 0 ? MINUS : ''}¥${compactMoney(Math.abs(value))}`
}

/** 'YYYY-MM' → { month: '10月', year: '2026' }；格式不对时原样作为 month 返回。 */
export function splitPeriod(period: string): { month: string; year: string } {
  const match = /^(\d{4})-(\d{2})$/.exec(period)
  if (!match) return { month: period, year: '' }
  return { month: `${Number(match[2])}月`, year: match[1] }
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
