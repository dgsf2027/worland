import request from '@/utils/request'
import axios from 'axios'

// ---------- 月度报表包(M3-07) ----------

export interface RentLedgerRow {
  billId: number; billNo: string; contractNo?: string; periodNo?: number
  dueDate?: string; amount: number; receivedAmount: number; status: string; billKind?: string; overdue: boolean
}
export interface RentLedger {
  period: string; rows: RentLedgerRow[]
  totalReceivable: number; totalReceived: number; totalOutstanding: number
  billCount: number; matchedCount: number; overdueCount: number; collectionRate: number
}
export interface PlRow { key: string; label: string; amount: number; subtotal: boolean; note?: string }
export interface ProfitStatement { period: string; locked: boolean; rows: PlRow[]; operatingProfit: number }
export interface CashflowSheet {
  period: string; inflow: number; outflow: number; net: number; inCount: number; outCount: number
  netPosition: number; ownCapital: number; supplierCredit: number; financing: number
}
export interface PayableRow { payableId: number; purchaseInId?: number; stage?: string; dueDate?: string; amount: number; overdue: boolean }
export interface DueSheet {
  asOf?: string
  receivableWithin1Y: number; receivableBeyond1Y: number; receivableTotal: number; receivableCount: number
  payableWithin1Y: number; payableBeyond1Y: number; payableTotal: number; payableCount: number
  payables: PayableRow[]
}
export interface StatusCount { status: string; count: number }
export interface AssetSnapshot {
  period: string; total: number; rentedCount: number; idleCount: number; pendingDisposal: number
  rentedRatio: number; marketPriceTotal: number; bookValueTotal: number; byStatus: StatusCount[]
}
export interface ShareRow {
  name: string; role: string; ratio: number; cashShare: number; rollShare: number; mgmtFee: number; totalGain: number; self: boolean
}
export interface DistributionSheet {
  period: string; present: boolean; distributable: number; mgmtFee: number
  cash50: number; roll50: number; reserveAfter: number; shares: ShareRow[]; scopeNote?: string
}
export interface MonthlyPackage {
  period: string; periods: string[]; dataAsOf?: string; locked: boolean
  rentLedger: RentLedger; profit: ProfitStatement; cashflow: CashflowSheet
  dueSheet: DueSheet; asset: AssetSnapshot
  distribution: DistributionSheet | null; distributionVisible: boolean; distributionMaskNote?: string
}

export interface DataPoint { label: string; value: string; source: string }
export interface AnalysisSection { key: string; title: string; narrative: string; dataPoints: DataPoint[] }
export interface AnalysisReport {
  period: string; periods: string[]; locked: boolean
  aiText: string; llmCallId?: number; cacheHit: boolean; model: string; mock: boolean
  sections: AnalysisSection[]
}

export interface CalendarDay { day: number; financeAction: string; systemAuto: string; state: string; note: string }
export interface CalendarBoard { period: string; status: string; days: CalendarDay[]; pendingCount: number }

export interface LlmCallLog {
  id: number; scene: string; model: string; reasoning: string; outputText: string
  confidence: number; confidenceSource: string; inputDigest: string; callStatus: string
}

export function fetchMonthlyPackage(period?: string) {
  return request.get<any, MonthlyPackage>('/rent/monthly-report', { params: { period } })
}
export function fetchMonthlyAnalysis(period?: string, force = false) {
  return request.get<any, AnalysisReport>('/rent/monthly-report/analysis', { params: { period, force } })
}
export function fetchMonthlyCalendar(period?: string) {
  return request.get<any, CalendarBoard>('/rent/monthly-report/calendar', { params: { period } })
}
export function generateMonthlyReport(period?: string) {
  return request.post<any, any>('/rent/monthly-report/generate', null, { params: { period } })
}
export function fetchLlmCall(id: number) {
  return request.get<any, LlmCallLog>(`/rent/ai/call/${id}`)
}

// 导出:blob 直下(绕开统一响应拦截器,带 X-User 头保证分配表按角色导出)
export async function downloadMonthly(period: string | undefined, kind: 'xlsx' | 'docx') {
  const name = localStorage.getItem('rent_user_name') || '老板'
  const role = localStorage.getItem('rent_user_role') || '老板'
  const resp = await axios.get((import.meta.env.VITE_API_BASE_URL || '/api') + '/rent/monthly-report', {
    params: { period, export: kind },
    responseType: 'blob',
    headers: { 'X-User-Name': encodeURIComponent(name), 'X-User-Role': encodeURIComponent(role) },
  })
  const url = window.URL.createObjectURL(new Blob([resp.data]))
  const a = document.createElement('a')
  a.href = url
  a.download = (kind === 'xlsx' ? '月度报表包-' : '月度经营分析报告-') + (period || 'latest') + '.' + kind
  a.click()
  window.URL.revokeObjectURL(url)
}
