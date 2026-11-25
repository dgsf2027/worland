import { describe, expect, it } from 'vitest'
import { calculateLease, leaseIrr, leaseNpv, validateLeaseInput, type LeaseInput } from './leaseCalculator'
import reference from './fixtures/leaseCalculator.json'

const base: LeaseInput = { contractPrice: 280000, purchasePrice: 280000, depositPercent: 10,
  residualPercent: 10, annualRatePercent: 25, years: 5, frequency: 12, mode: 'a', refundDeposit: true }
describe('提取的设备租赁面板计算逻辑', () => {
  it.each(reference.cases)('与原仓库三种口径/四种频率/押金处理结果一致：$input', ({ input, expected }) => {
    const r = calculateLease(input as LeaseInput)
    for (const key of ['rent', 'totalRent', 'totalInterest', 'totalPrincipal', 'profit', 'equity',
      'effectiveAnnualIrr', 'nominalAnnualIrr', 'customerAnnualIrr'] as const) {
      expect(r[key]).toBeCloseTo(expected[key], 7)
    }
    expect(r.rows[r.rows.length - 1].interest).toBeCloseTo(expected.lastInterest, 8)
    expect(r.rows[r.rows.length - 1].closingBalance).toBe(0)
    expect(r.totalInterest + r.totalPrincipal).toBeCloseTo(r.totalRent, 8)
    expect(leaseNpv(r.cashFlows, r.periodIrr!)).toBeCloseTo(0, 6)
  })
  it('零利率、无押金无残值：每期归还本金，IRR 为零', () => {
    const r = calculateLease({ ...base, contractPrice: 120000, purchasePrice: 120000,
      depositPercent: 0, residualPercent: 0, annualRatePercent: 0, years: 1 })
    expect(r.rent).toBe(10000)
    expect(r.profit).toBe(0)
    expect(r.effectiveAnnualIrr).toBe(0)
    expect(r.rows.every(row => row.interest === 0)).toBe(true)
  })
  it('押金到期退还只影响占用及收益率，不改变净收益', () => {
    const withDeposit = calculateLease(base), noDeposit = calculateLease({ ...base, depositPercent: 0 })
    expect(withDeposit.profit).toBe(noDeposit.profit)
    expect(withDeposit.equity).toBe(noDeposit.equity - 28000)
    expect(withDeposit.rows[withDeposit.rows.length - 1].depositRefund).toBe(28000)
    expect(calculateLease({ ...base, refundDeposit: false }).profit).toBeCloseTo(withDeposit.profit + 28000)
  })
  it('成本变化仅影响出租方，不改变租金与承租方年化', () => {
    const a = calculateLease(base), b = calculateLease({ ...base, purchasePrice: 250000 })
    expect(b.rent).toBe(a.rent)
    expect(b.customerAnnualIrr).toBe(a.customerAnnualIrr)
    expect(b.profit - a.profit).toBe(30000)
    expect(b.effectiveAnnualIrr!).toBeGreaterThan(a.effectiveAnnualIrr!)
  })
  it('单期现金流同时收租金/留购价、退还押金', () => {
    const r = calculateLease({ ...base, years: 1, frequency: 1 })
    expect(r.rows).toHaveLength(1)
    expect(r.rows[0].cashFlow).toBeCloseTo(r.rent + r.residual - r.deposit)
    expect(r.rows[0].cumulativeCashFlow).toBeCloseTo(r.profit)
  })
  it('零占用总回报率和无解 IRR 显示空值；亏损可返回负收益率', () => {
    expect(calculateLease({ ...base, depositPercent: 100 }).returnOnEquity).toBeNull()
    expect(leaseIrr([0, 0, 0])).toBeNull()
    expect(leaseIrr([100, 200])).toBeNull()
    expect(leaseIrr([-100, 90])).toBeCloseTo(-0.1, 9)
  })
  it.each([
    { contractPrice: NaN }, { purchasePrice: 0 }, { purchasePrice: Infinity }, { depositPercent: -1 },
    { residualPercent: 101 }, { annualRatePercent: -1 }, { years: 0 }, { years: 101 },
    { frequency: 1, years: 0.5 }, { frequency: 3 }, { mode: 'x' },
  ])('阻止无效输入及非整数期数 %j', (change) => {
    const q = { ...base, ...change } as LeaseInput
    expect(validateLeaseInput(q)).not.toBe('')
    expect(() => calculateLease(q)).toThrow()
  })
  it('真实合同测算：云山快仓28万/20%/60期/0押金/1元固定留购价/每月15日', () => {
    const r = calculateLease({
      contractPrice: 280000, purchasePrice: 280000, depositPercent: 0,
      residualPercent: 0, residualMode: 'amount', residualAmount: 1,
      annualRatePercent: 20, years: 5, frequency: 12, mode: 'b', refundDeposit: true,
      startDate: '2026-10-01', firstPayDate: '2026-11-15', payDayOfMonth: 15,
    })
    expect(r.periods).toBe(60)
    expect(r.rent).toBe(7418.29)
    expect(r.residual).toBe(1)
    expect(r.totalRent).toBe(445097.4)
    expect(r.totalPrincipal).toBe(280000)
    expect(r.totalInterest).toBe(165097.4)
    expect(r.profit).toBe(165098.4)
    expect(r.rows[0].dueDate).toBe('2026-11-15')
    expect(r.rows[0].interest).toBe(4666.67)
    expect(r.rows[0].principal).toBe(2751.62)
    expect(r.rows[59].dueDate).toBe('2031-10-15')
    expect(r.rows[59].closingBalance).toBe(0)
  })
})
