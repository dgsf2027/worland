import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface BillItem {
  id: number
  billNo: string
  contractId: number
  contractNo?: string
  customerName?: string
  periodNo: number
  dueDate?: string
  amount: number
  receivedAmount?: number
  status: string
  billKind: string
  matchedAt?: string
  accountPeriod?: string
  reversesId?: number
  voucherId?: number
  overdue?: boolean
  overdueDays?: number
  remark?: string
}

export interface BillDetail {
  bill: BillItem
  related: BillItem[]
  overdueCaseId?: number
}

export interface ReverseImpact {
  originalBillId: number
  reversalBillId: number
  originalAmount: number
  reversalAmount: number
  accountPeriod: string
  items: string[]
}

export interface GenResult {
  generated: number
  skipped: number
  horizon: string
  bills: string[]
}

export interface CashCheck {
  billNotGenerated: number
  receivedNotMatched: number
  matchedNoVoucher: number
  billNotGeneratedDetail: string[]
  receivedNotMatchedDetail: string[]
  matchedNoVoucherDetail: string[]
  allClear: boolean
}

export interface OverdueItem {
  id: number
  rentBillId: number
  billNo?: string
  contractId: number
  contractNo?: string
  customerName?: string
  step: string
  status: string
  penaltyAmount?: number
  nextAction?: string
  deadline?: string
  owner?: string
  openedAt?: string
  closedAt?: string
  remark?: string
}

export interface OverdueScanResult { opened: number; marked: number; cases: string[] }
export interface RepaymentResult { caseId: number; caseStatus: string; matchedBillId: number; assetsRestored: boolean }

// ---- 收租单 ----
export function fetchBills(params: Record<string, any>): Promise<PageResult<BillItem>> {
  return request.get('/rent/bills', { params })
}
export function fetchBillDetail(id: number): Promise<BillDetail> {
  return request.get(`/rent/bills/${id}`)
}
export function matchBill(id: number, body: Record<string, any> = {}): Promise<BillItem> {
  return request.post(`/rent/bills/${id}/match`, body)
}
export function batchMatch(ids: number[]): Promise<BillItem[]> {
  return request.post('/rent/bills/batch-match', { ids })
}
export function reverseBill(id: number, body: Record<string, any>): Promise<ReverseImpact> {
  return request.post(`/rent/bills/${id}/reverse`, body)
}
export function refundBill(id: number, body: Record<string, any>): Promise<BillItem> {
  return request.post(`/rent/bills/${id}/refund`, body)
}
export function runGen(): Promise<GenResult> {
  return request.post('/rent/bills/gen/run', {})
}
export function fetchCashCheck(): Promise<CashCheck> {
  return request.get('/rent/audit/cash-check')
}

// ---- 逾期案 ----
export function fetchOverdue(params: Record<string, any>): Promise<PageResult<OverdueItem>> {
  return request.get('/rent/overdue', { params })
}
export function overdueExtend(id: number, body: Record<string, any>): Promise<OverdueItem> {
  return request.post(`/rent/overdue/${id}/extend`, body)
}
export function overduePenalty(id: number, body: Record<string, any>): Promise<OverdueItem> {
  return request.post(`/rent/overdue/${id}/penalty`, body)
}
export function overdueLock(id: number, body: Record<string, any>): Promise<OverdueItem> {
  return request.post(`/rent/overdue/${id}/lock`, body)
}
export function overdueRepossess(id: number, body: Record<string, any>): Promise<OverdueItem> {
  return request.post(`/rent/overdue/${id}/repossess`, body)
}
export function overdueRepay(id: number, body: Record<string, any> = {}): Promise<RepaymentResult> {
  return request.post(`/rent/overdue/${id}/repay`, body)
}
export function runScan(): Promise<OverdueScanResult> {
  return request.post('/rent/overdue/scan/run', {})
}
