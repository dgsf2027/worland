import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

/** 共用的下载动作:后端已带 Content-Disposition，这里只负责落盘。 */
async function download(url: string, params: Record<string, any>, fallbackName: string) {
  const blob: Blob = await request.get(url, { params, responseType: 'blob', timeout: 0 })
  const href = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = href
  a.download = fallbackName
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(href), 60000)
}

function ymd() {
  const d = new Date()
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
}

export interface VoucherItem {
  id: number
  voucherNo: string
  sourceDocType: string
  sourceDocId?: number
  book: string          // tax/ops
  period: string
  bizDate?: string
  totalAmount: number
  entryType: string
  summary?: string
  isReversal?: boolean
  reversesId?: number
  balanced?: boolean
  createTime?: string
}

export interface LineItem {
  id: number
  accountCode: string
  accountName: string
  direction: string     // dr/cr
  amount: number
  remark?: string
}

export interface VoucherDetail {
  voucher: VoucherItem
  lines: LineItem[]
  debitTotal: number
  creditTotal: number
  balanced: boolean
  siblingBooks: VoucherItem[]
  reversedByVoucherId?: number
}

export interface VoucherReverseImpact {
  originalVoucherId: number
  originalVoucherNo: string
  reversalVoucherId: number
  reversalVoucherNo: string
  book: string
  period: string
  amount: number
  items: string[]
}

export interface BackfillResult {
  scanned: number
  posted: number
  voucherCount: number
  details: string[]
}

export interface MonthRevenue { period: string; taxRevenue: number; opsRevenue: number }
export interface TaxThreshold {
  year: number
  book: string
  threshold: number
  warnRatio: number
  currentRevenue: number
  remaining: number
  usedRatio: number
  level: string          // 正常/预警/超限
  opsRevenue: number
  byMonth: MonthRevenue[]
}

export interface DepreciationRunResult {
  period: string
  assetsScanned: number
  linesGenerated: number
  skipped: number
  vouchersPosted: number
  totalDepr: number
  details: string[]
}

// ---- 凭证 ----
/**
 * 导出凭证 Excel(凭证 / 凭证分录 / 折旧明细 / 营收红线 四张表)。
 * 筛选口径与列表一致 —— 页面上筛成什么样，导出就是什么样。
 *
 * 凭证只导出不导入:凭证是收租核销/采购应付/折旧计提/转让处置在同一事务里生成的结果，
 * 从表格灌凭证会让它和来源单据对不上，也绕开红冲留痕。
 */
export async function exportVouchers(params: Record<string, any>) {
  await download('/rent/vouchers/export', params, `凭证双账折旧-${ymd()}.xlsx`)
}

export function fetchVouchers(params: Record<string, any>): Promise<PageResult<VoucherItem>> {
  return request.get('/rent/vouchers', { params })
}
export function fetchVoucherDetail(id: number): Promise<VoucherDetail> {
  return request.get(`/rent/vouchers/${id}`)
}
export function reverseVoucher(id: number, body: Record<string, any>): Promise<VoucherReverseImpact> {
  return request.post(`/rent/vouchers/${id}/reverse`, body)
}
export function backfillRentIncome(): Promise<BackfillResult> {
  return request.post('/rent/vouchers/backfill-rent-income', {})
}

// ---- 500万营收红线 ----
export function fetchTaxThreshold(year?: number): Promise<TaxThreshold> {
  return request.get('/rent/tax/threshold', { params: year ? { year } : {} })
}

// ---- 折旧 ----
export function runDepreciation(bizDate?: string): Promise<DepreciationRunResult> {
  return request.post('/rent/depreciation/run', {}, { params: bizDate ? { bizDate } : {} })
}
