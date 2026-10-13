// Run with Node 24+: node --test frontend/src/utils/dashboardCharts.test.mjs
import assert from 'node:assert/strict'
import test from 'node:test'
import { alignTrends, buildDonutSegments, isNumber, money, niceScale } from './dashboardCharts.ts'

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

test('donuts omit invalid and negative amounts but retain valid zeros', () => {
  const result = buildDonutSegments([
    { label: '已收', value: 30, color: 'green' },
    { label: '未收', value: 70, color: 'orange' },
    { label: '合法零', value: 0, color: 'blue' },
    { label: '缺失', value: null, color: 'gray' },
    { label: '负数', value: -8, color: 'red' },
    { label: '错误', value: Infinity, color: 'red' },
  ])
  assert.equal(result.total, 100)
  assert.deepEqual(result.segments.map(({ label, value, share, offset }) => ({ label, value, share, offset })), [
    { label: '已收', value: 30, share: 0.3, offset: 0 },
    { label: '未收', value: 70, share: 0.7, offset: 0.3 },
    { label: '合法零', value: 0, share: 0, offset: 1 },
  ])
  assert.deepEqual(buildDonutSegments([]), { total: 0, segments: [] })
  const zero = buildDonutSegments([{ label: '空', value: 0, color: 'gray' }])
  assert.equal(zero.total, 0)
  assert.equal(zero.segments[0].share, 0)
  assert.equal(zero.segments[0].offset, 0)
})
