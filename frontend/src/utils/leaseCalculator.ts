/** Extracted from levy1688888/equipment-lease-calculator app.html @316cc706a4d3aa118c2be49ed00d6db53de645c7.
 * Amounts in yuan; input rates in percent. No persistence or network calls. */
export interface LeaseInput {
  contractPrice: number; purchasePrice: number; depositPercent: number; residualPercent: number
  annualRatePercent: number; years: number; frequency: 12 | 4 | 2 | 1; mode: 'a' | 'b' | 'c'; refundDeposit: boolean
}
export interface LeasePeriod {
  period: number; openingBalance: number; rent: number; interest: number; principal: number
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

export function validateLeaseInput(q: LeaseInput): string {
  if (![q.contractPrice, q.purchasePrice, q.depositPercent, q.residualPercent, q.annualRatePercent, q.years, q.frequency].every(Number.isFinite)) return '请完整填写合同、成本、利率和期限。'
  if (q.contractPrice <= 0 || q.purchasePrice <= 0) return '合同总价和实际采购价须大于 0。'
  if (q.contractPrice > 1e12 || q.purchasePrice > 1e12) return '金额不得超过一万亿元。'
  if (q.depositPercent < 0 || q.depositPercent > 100 || q.residualPercent < 0 || q.residualPercent > 100) return '押金比例和残值比例须在 0% 至 100% 之间。'
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
  const deposit = q.contractPrice * q.depositPercent / 100, residual = q.contractPrice * q.residualPercent / 100
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
    rows.push({ period: t, openingBalance, rent, interest, principal, closingBalance: balance,
      residual: t === periods ? residual : 0, depositRefund: t === periods && q.refundDeposit ? deposit : 0,
      cashFlow: cashFlows[t], cumulativeCashFlow: roundMoney(cumulative) })
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
