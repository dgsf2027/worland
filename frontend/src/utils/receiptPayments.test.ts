import { describe, expect, it, vi } from 'vitest'
import { newReceiptDraft, readReceiptDraft, receiptPaymentBody, receiptPaymentError } from './receiptPayments'

const file = (name: string) => ({ name, size: 100 } as File)
describe('按回单逐笔登记', () => {
  it('两张回单分别识别，保留各自实际金额/流水/附件，不按节点或合计拆分', async () => {
    const a = file('a.pdf'), b = file('b.pdf')
    const rows = [newReceiptDraft(a, 1), newReceiptDraft(b, 2)]
    const upload = vi.fn().mockResolvedValueOnce({ id: 11 }).mockResolvedValueOnce({ id: 12 })
    const recognize = vi.fn().mockResolvedValueOnce({ amount: 100000, paidDate: '2026-10-16', bankSerial: 'BANK-A' })
      .mockResolvedValueOnce({ amount: 30000, paidDate: '2026-10-17', bankSerial: 'BANK-B' })
    for (const row of rows) await readReceiptDraft(row, upload, recognize)
    expect(recognize.mock.calls.map((call) => call[0])).toEqual([[a], [b]])
    expect(receiptPaymentError(rows)).toBe('')
    expect(receiptPaymentBody(rows)).toEqual([
      { amount: 100000, paidDate: '2026-10-16', bankSerial: 'BANK-A', remark: undefined, proofFileIds: [11] },
      { amount: 30000, paidDate: '2026-10-17', bankSerial: 'BANK-B', remark: undefined, proofFileIds: [12] },
    ])
  })
  it('OCR 失败仍保留附件，必须手工补金额日期才能提交', async () => {
    const row = newReceiptDraft(file('unknown.png'), 1)
    await readReceiptDraft(row, async () => ({ id: 1, fileName: 'unknown.png', size: 100 }), async () => null)
    expect(row.proofFileIds).toEqual([1])
    expect(receiptPaymentError([row])).toContain('金额')
    row.amount = 500
    row.paidDate = '2026-10-16'
    expect(receiptPaymentError([row])).toBe('')
  })
  it('上传失败不会生成可提交的付款', async () => {
    const row = newReceiptDraft(file('a.pdf'), 1)
    const recognize = vi.fn()
    await readReceiptDraft(row, async () => { throw new Error('upload failed') }, recognize)
    expect(recognize).not.toHaveBeenCalled()
    expect(row.status).toBe('failed')
    expect(receiptPaymentError([row])).toContain('上传')
  })
  it('阻止重复流水、重复附件和非法日期', () => {
    const row = { ...newReceiptDraft(file('a.pdf'), 1), status: 'ready' as const, amount: 100, paidDate: '2026-10-16', bankSerial: 'BANK-A', proofFileIds: [1] }
    expect(receiptPaymentError([row, { ...row, proofFileIds: [2] }])).toContain('流水号')
    expect(receiptPaymentError([row, { ...row, bankSerial: 'BANK-B' }])).toContain('同一回单')
    expect(receiptPaymentError([{ ...row, paidDate: '2026-02-30' }])).toContain('有效付款日期')
    expect(receiptPaymentError([{ ...row, paidDate: '2026-99-30' }])).toContain('有效付款日期')
  })
})
