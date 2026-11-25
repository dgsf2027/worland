/** Extracted from levy1688888/equipment-lease-calculator app.html @316cc706a4d3aa118c2be49ed00d6db53de645c7.
 * Amounts in yuan; input rates in percent. No persistence or network calls. */
export interface LeaseInput {
  contractPrice: number; purchasePrice: number; depositPercent: number; residualPercent: number
  annualRatePercent: number; years: number; frequency: 12 | 4 | 2 | 1; mode: 'a' | 'b' | 'c'; refundDeposit: boolean
  /** 留购价输入模式：percent=按残值比例%(默认)，amount=按固定金额(如1.00元名义留购) */
  residualMode?: 'percent' | 'amount'
  residualAmount?: number
  /** 起租日 (YYYY-MM-DD, 可选) */
  startDate?: string
  /** 首期付款日 (YYYY-MM-DD, 可选，优先用于推算逐期到期日) */
  firstPayDate?: string
  /** 每月固定付款日 (1-31, 可选) */
  payDayOfMonth?: number
}
export interface LeasePeriod {
  period: number; dueDate?: string; openingBalance: number; rent: number; interest: number; principal: number
  closingBalance: number; residual: number; depositRefund: number; cashFlow: number; cumulativeCashFlow: number
}
export const INTEREST_MODES = [
  { value: 'a', label: 'A · 扣残值后等额本息', description: '计息本金 = 合同总价 − 期末留购价，期末另收留购价。' },
  { value: 'b', label: 'B · 全额等额本息＋留购', description: '按合同总价计算等额本息，期末另收留购价。' },
  { value: 'c', label: 'C · 平息', description: '总利息 = 合同总价 × 名义年化利率 × 租期年数，按期均摊。' },
] as const
export const PAYMENT_FREQUENCIES = [
  { value: 12, label: '月付', unit: '月' }, { value: 4, label: '季付', unit: '季' },
  { value: 2, label: '半年付', unit: '半年' }, { value: 1, label: '年付', unit: '年' },
] as const
const roundMoney = (v: number) => Math.round((v + Number.EPSILON) * 100) / 100
const finiteOrNull = (v: number) => Number.isFinite(v) ? v : null

function parseIsoDate(s?: string): { y: number; m: number; d: number } | null {
  if (!s) return null
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s.trim())
  if (!m) return null
  return { y: Number(m[1]), m: Number(m[2]), d: Number(m[3]) }
}

function addMonthsClamped(base: { y: number; m: number; d: number }, addMonths: number, fixedDay?: number): string {
  const totalMonths = (base.y * 12 + (base.m - 1)) + addMonths
  const y = Math.floor(totalMonths / 12)
  const m = (totalMonths % 12) + 1
  const maxDay = new Date(y, m, 0).getDate()
  const targetDay = fixedDay && fixedDay >= 1 ? Math.min(fixedDay, maxDay) : Math.min(base.d, maxDay)
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(targetDay).padStart(2, '0')}`
}

function computeDueDate(q: LeaseInput, periodIndex: number): string | undefined {
  const stepMonths = Math.round(12 / q.frequency)
  const first = parseIsoDate(q.firstPayDate)
  if (first) {
    const day = q.payDayOfMonth && q.payDayOfMonth >= 1 ? q.payDayOfMonth : first.d
    return addMonthsClamped(first, (periodIndex - 1) * stepMonths, day)
  }
  const start = parseIsoDate(q.startDate)
  if (start) {
    const day = q.payDayOfMonth && q.payDayOfMonth >= 1 ? q.payDayOfMonth : start.d
    return addMonthsClamped(start, periodIndex * stepMonths, day)
  }
  return undefined
}

export function validateLeaseInput(q: LeaseInput): string {
  const useFixedResidual = q.residualMode === 'amount'
  const baseNums = useFixedResidual
    ? [q.contractPrice, q.purchasePrice, q.depositPercent, q.residualAmount ?? NaN, q.annualRatePercent, q.years, q.frequency]
    : [q.contractPrice, q.purchasePrice, q.depositPercent, q.residualPercent, q.annualRatePercent, q.years, q.frequency]
  if (!baseNums.every(Number.isFinite)) return '请完整填写合同、成本、利率和期限。'
  if (q.contractPrice <= 0 || q.purchasePrice <= 0) return '合同总价和实际采购价须大于 0。'
  if (q.contractPrice > 1e12 || q.purchasePrice > 1e12) return '金额不得超过一万亿元。'
  if (q.depositPercent < 0 || q.depositPercent > 100) return '押金比例须在 0% 至 100% 之间。'
  if (useFixedResidual) {
    const rAmt = q.residualAmount ?? 0
    if (rAmt < 0 || rAmt > q.contractPrice) return '固定留购价须在 0 至合同总价之间。'
  } else if (q.residualPercent < 0 || q.residualPercent > 100) {
    return '押金比例和残值比例须在 0% 至 100% 之间。'
  }
  if (q.annualRatePercent < 0 || q.annualRatePercent > 1000) return '名义年化利率须在 0% 至 1000% 之间。'
  if (!PAYMENT_FREQUENCIES.some(f => f.value === q.frequency)) return '请选择有效的付款频率。'
  if (!INTEREST_MODES.some(m => m.value === q.mode)) return '请选择有效的计息口径。'
  if (typeof q.refundDeposit !== 'boolean') return '请选择押金到期处理方式。'
  const periods = q.years * q.frequency
  if (q.years <= 0 || periods < 1 || periods > 1200) return '租期须对应 1 至 1200 个付款周期。'
  // Never silently round the rental term, unlike the standalone app.
  if (Math.abs(periods - Math.round(periods)) > 1e-8) return '租期与付款频率须组成整数期数，请调整租期或付款频率。'
  return ''
}
export function leaseNpv(cashFlows: number[], rate: number): number {
  return cashFlows.reduce((sum, cash, t) => sum + cash / Math.pow(1 + rate, t), 0)
}
function findRoot(flows: number[], start: number, end: number, steps: number): number | null {
  let previousRate = start, previousValue = leaseNpv(flows, start)
  if (previousValue === 0) return start
  for (let k = 1; k <= steps; k++) {
    const rate = start + (end - start) * k / steps, value = leaseNpv(flows, rate)
    if (value === 0) return rate
    if (Number.isFinite(previousValue) && Number.isFinite(value) && previousValue * value < 0) {
      let left = previousRate, right = rate, leftValue = previousValue
      for (let j = 0; j < 160; j++) {
        const mid = (left + right) / 2, midValue = leaseNpv(flows, mid)
        if (midValue === 0) return mid
        if (midValue * leftValue > 0) { left = mid; leftValue = midValue } else right = mid
      }
      return (left + right) / 2
    }
    previousRate = rate; previousValue = value
  }
  return null
}
export function leaseIrr(cashFlows: number[]): number | null {
  if (!cashFlows.some(v => v > 0) || !cashFlows.some(v => v < 0)) return null
  const flows = cashFlows[0] > 0 ? cashFlows.map(v => -v) : cashFlows
  return findRoot(flows, 0, 3, 600) ?? findRoot(flows, -0.5, 0, 300)
}
export function calculateLease(input: LeaseInput) {
  const error = validateLeaseInput(input)
  if (error) throw new Error(error)
  const q = { ...input }, periods = Math.round(q.years * q.frequency)
  const deposit = q.contractPrice * q.depositPercent / 100
  const residual = q.residualMode === 'amount'
    ? roundMoney(q.residualAmount ?? 0)
    : q.contractPrice * q.residualPercent / 100
  const annualRate = q.annualRatePercent / 100, periodRate = annualRate / q.frequency
  const base = q.mode === 'a' ? q.contractPrice - residual : q.contractPrice
  const exactRent = q.mode === 'c' ? q.contractPrice * (1 + annualRate * q.years) / periods
    : periodRate === 0 ? base / periods : base * periodRate / (1 - Math.pow(1 + periodRate, -periods))
  const rent = roundMoney(exactRent)
  const cashFlows = [-q.purchasePrice + deposit, ...Array<number>(periods).fill(rent)]
  cashFlows[periods] += residual - (q.refundDeposit ? deposit : 0)
  const customerCashFlows = [q.contractPrice - deposit, ...Array<number>(periods).fill(-rent)]
  customerCashFlows[periods] += -residual + (q.refundDeposit ? deposit : 0)
  const rows: LeasePeriod[] = []
  let balance = roundMoney(base), sumInterest = 0, sumPrincipal = 0, cumulative = cashFlows[0]
  for (let t = 1; t <= periods; t++) {
    const openingBalance = balance
    let interest = roundMoney(q.mode === 'c' ? q.contractPrice * annualRate * q.years / periods : balance * periodRate)
    let principal: number
    if (t === periods) { principal = roundMoney(balance); interest = roundMoney(rent - principal); balance = 0 }
    else { principal = roundMoney(rent - interest); balance = Math.max(0, roundMoney(balance - principal)) }
    cumulative += cashFlows[t]
    rows.push({
      period: t,
      dueDate: computeDueDate(q, t),
      openingBalance, rent, interest, principal, closingBalance: balance,
      residual: t === periods ? residual : 0, depositRefund: t === periods && q.refundDeposit ? deposit : 0,
      cashFlow: cashFlows[t], cumulativeCashFlow: roundMoney(cumulative),
    })
    sumInterest += interest; sumPrincipal += principal
  }
  const periodIrr = leaseIrr(cashFlows), customerPeriodIrr = leaseIrr(customerCashFlows)
  const profit = roundMoney(cashFlows.reduce((sum, cash) => sum + cash, 0)), equity = q.purchasePrice - deposit
  const nonzeroFlows = cashFlows.filter(v => v !== 0)
  return {
    input: q, periods, deposit, residual, base, periodRate, exactRent, rent, rows, cashFlows, customerCashFlows,
    totalRent: roundMoney(rent * periods), totalInterest: roundMoney(sumInterest), totalPrincipal: roundMoney(sumPrincipal), profit, equity,
    returnOnEquity: equity > 0 ? profit / equity : null, periodIrr,
    effectiveAnnualIrr: periodIrr === null ? null : finiteOrNull(Math.pow(1 + periodIrr, q.frequency) - 1),
    nominalAnnualIrr: periodIrr === null ? null : finiteOrNull(periodIrr * q.frequency),
    customerAnnualIrr: customerPeriodIrr === null ? null : finiteOrNull(Math.pow(1 + customerPeriodIrr, q.frequency) - 1),
    multipleSignChanges: nonzeroFlows.slice(1).filter((v, i) => Math.sign(v) !== Math.sign(nonzeroFlows[i])).length > 1,
  }
}
