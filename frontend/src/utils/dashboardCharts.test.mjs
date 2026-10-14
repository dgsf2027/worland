// Run with Node 24+: node --test frontend/src/utils/dashboardCharts.test.mjs
import assert from 'node:assert/strict'
import test from 'node:test'
import { alignTrends, compactMoney, compactYuan, isNumber, money, niceScale, splitPeriod, yuan } from './dashboardCharts.ts'

test('number and money helpers distinguish a genuine zero from missing or invalid data', () => {
  assert.equal(isNumber(0), true)
  for (const value of [null, undefined, '', '12', NaN, Infinity, -Infinity]) {
    assert.equal(isNumber(value), false)
    assert.equal(money(value), '—')
  }
  assert.equal(money(0), '0')
  assert.equal(money(1234567.89, 2), '1,234,567.89')
  assert.equal(money(-1240), '-1,240')
})

test('empty and all-zero scales have a finite nonzero span', () => {
  for (const values of [[], [0, 0], [NaN, Infinity]]) {
    const result = niceScale(values)
    assert.equal(result.min, 0)
    assert.ok(result.max > 0)
    assert.ok(result.ticks.every(Number.isFinite))
    assert.ok(result.ticks.length >= 4 && result.ticks.length <= 8)
  }
})

test('signed scales cover positive and negative results and retain a zero baseline', () => {
  for (const values of [[-38, 69], [-438, -90], [-0.04, 0.01], [-1e308, 1e308]]) {
    const result = niceScale(values, true)
    assert.ok(result.min <= Math.min(...values, 0))
    assert.ok(result.max >= Math.max(...values, 0))
    assert.ok(result.ticks.includes(0))
    assert.ok(result.ticks.every(Number.isFinite))
    assert.ok(result.ticks.every((value, index) => index === 0 || value > result.ticks[index - 1]))
  }
})

test('large amounts and small fractions produce usable tick ranges', () => {
  for (const value of [123456789012345, 0.000032, Number.MAX_VALUE, Number.MIN_VALUE]) {
    const result = niceScale([value])
    assert.equal(result.min, 0)
    assert.ok(result.max >= value)
    assert.ok(Number.isFinite(result.max))
    assert.ok(result.ticks.length > 1 && result.ticks.length <= 12)
  }
})

test('trends join by month across unordered and mismatched ranges without filling gaps with zero', () => {
  const first = [
    { period: '2026-03', value: 10 },
    { period: '2025-12', value: 0 },
    { period: '2026-01', value: null },
    { period: '2026-13', value: 900 },
  ]
  const second = [
    { period: '2026-02', value: 22 },
    { period: '2026-03', value: 0 },
    { period: '2026-04', value: NaN },
  ]
  assert.deepEqual(alignTrends(first, second), [
    { period: '2025-12', first: 0, second: null },
    { period: '2026-01', first: null, second: null },
    { period: '2026-02', first: null, second: 22 },
    { period: '2026-03', first: 10, second: 0 },
    { period: '2026-04', first: null, second: null },
  ])
  assert.deepEqual(alignTrends([], []), [])
})

test('short amounts switch to 万 and 亿 and keep the sign in front of the currency symbol', () => {
  assert.equal(compactMoney(0), '0')
  assert.equal(compactMoney(8648), '8,648')
  assert.equal(compactMoney(10000), '1万')
  assert.equal(compactMoney(1531975), '153.2万')
  assert.equal(compactMoney(-250000), '−25万')
  assert.equal(compactMoney(320000000), '3.2亿')
  assert.equal(compactYuan(-1294148), '−¥129.41万')
  assert.equal(yuan(-1240), '−¥1,240')
  assert.equal(yuan(912474.5, 2), '¥912,474.50')
  for (const value of [null, undefined, NaN, '12']) {
    assert.equal(compactMoney(value), '—')
    assert.equal(yuan(value), '—')
  }
})

test('periods split into a month label and a year', () => {
  assert.deepEqual(splitPeriod('2026-10'), { month: '10月', year: '2026' })
  assert.deepEqual(splitPeriod('2027-01'), { month: '1月', year: '2027' })
  assert.deepEqual(splitPeriod('本月'), { month: '本月', year: '' })
})
