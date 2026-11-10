import type { RecognizedReceipt } from '@/api/purchaseContract'
import type { UploadedProof } from '@/api/payProof'

export interface ReceiptPaymentDraft {
  key: number
  file: File
  fileName: string
  proofFileIds: number[]
  amount?: number
  paidDate: string
  bankSerial: string
  remark: string
  error: string
  status: 'pending' | 'ready' | 'failed'
}
export function newReceiptDraft(file: File, key: number): ReceiptPaymentDraft {
  return { key, file, fileName: file.name, proofFileIds: [], paidDate: '', bankSerial: '', remark: '', error: '', status: 'pending' }
}

/** 每个文件独立识别、独立绑定附件；即使 OCR 失败也保留该行供手工补全。 */
export async function readReceiptDraft(
  row: ReceiptPaymentDraft,
  upload: (file: File) => Promise<UploadedProof>,
  recognize: (files: File[]) => Promise<RecognizedReceipt | null>,
): Promise<void> {
  row.status = 'pending'
  row.error = ''
  try {
    if (!row.proofFileIds.length) row.proofFileIds = [(await upload(row.file)).id]
    const data = await recognize([row.file])
    if (data) {
      if (data.amount != null && data.amount > 0) row.amount = data.amount
      if (data.paidDate) row.paidDate = data.paidDate
      if (data.bankSerial) row.bankSerial = data.bankSerial
      row.remark = [data.payeeName ? `收款方 ${data.payeeName}` : '', data.remark || ''].filter(Boolean).join(' · ')
    }
    row.status = 'ready'
    if (!row.amount || !row.paidDate) row.error = '金额或日期未识别完整，请对照原件补全'
  } catch {
    row.status = row.proofFileIds.length ? 'ready' : 'failed'
    row.error = row.proofFileIds.length ? '识别失败，请对照原件手工填写' : '回单上传失败，请重试或移除此行'
  }
}

export function receiptPaymentError(rows: ReceiptPaymentDraft[]): string {
  if (!rows.length) return '请先上传付款回单'
  if (rows.length > 20) return '一次最多登记 20 笔付款'
  const serials = new Set<string>()
  const files = new Set<number>()
  for (const [i, row] of rows.entries()) {
    const prefix = `第 ${i + 1} 笔：`
    if (row.status === 'pending') return prefix + '正在处理回单'
    if (!row.proofFileIds.length || row.status === 'failed') return prefix + '请先成功上传回单'
    if (!Number.isFinite(row.amount) || row.amount! <= 0) return prefix + '请填写大于 0 的付款金额'
    const date = new Date(row.paidDate + 'T00:00:00Z')
    if (!/^\d{4}-\d{2}-\d{2}$/.test(row.paidDate) || !Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== row.paidDate) return prefix + '请填写有效付款日期'
    const serial = row.bankSerial.trim()
    if (serial && serials.has(serial)) return prefix + '银行流水号与其他回单重复，请核对是否重复上传'
    if (serial) serials.add(serial)
    for (const id of row.proofFileIds) {
      if (files.has(id)) return prefix + '同一回单不能分配到多笔付款'
      files.add(id)
    }
  }
  return ''
}

export function receiptPaymentBody(rows: ReceiptPaymentDraft[]) {
  return rows.map((row) => ({ amount: row.amount, paidDate: row.paidDate,
    bankSerial: row.bankSerial.trim() || undefined, remark: row.remark.trim() || undefined,
    proofFileIds: [...row.proofFileIds],
  }))
}
