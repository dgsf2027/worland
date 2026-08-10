import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

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
