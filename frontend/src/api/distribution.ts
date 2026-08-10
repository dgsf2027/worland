import request from '@/utils/request'

// ---------- 结账分配(M3-03/04) ----------

export interface InvestorItem {
  id: number
  name: string
  role: string          // GP/LP
  amount: number
  ratio: number
  self?: boolean
}

export interface ShareItem {
  investorId: number
  name: string
  role: string
  amount: number
  ratio: number
  cashShare: number
  rollShare: number
  mgmtFee: number
  totalGain: number
  self?: boolean
}

export interface DistributionItem {
  id: number
  distributionNo: string
  period: string
  bizDate?: string
  totalCapital: number
  profitBefore: number
  returnRate: number
  mgmtFeeRate: number
  mgmtFee: number
  distributable: number
  cash50: number
  roll50: number
  reserveFloor: number
  reserveAfter: number
  reserveSufficient?: boolean
  status: string        // active/reversed
  isReversal?: boolean
  reversesId?: number
  createTime?: string
}

export interface DistributionDetail {
  distribution: DistributionItem
  shares: ShareItem[]
  steps: string[]
}

export interface ReverseImpact {
  originalId: number
  originalNo: string
  reversalId: number
  reversalNo: string
  period: string
  distributable: number
  items: string[]
}

export function runDistribution(body: Record<string, any>): Promise<DistributionDetail> {
  return request.post('/rent/distribution/run', body)
}
export function reverseDistribution(id: number, body: Record<string, any>): Promise<ReverseImpact> {
  return request.post(`/rent/distribution/${id}/reverse`, body)
}
export function fetchDistributions(params: Record<string, any>): Promise<DistributionItem[]> {
  return request.get('/rent/distribution', { params })
}
export function fetchDistributionDetail(id: number): Promise<DistributionDetail> {
  return request.get(`/rent/distribution/${id}`)
}
export function fetchInvestors(): Promise<InvestorItem[]> {
  return request.get('/rent/investors')
}

// ---------- 现金流驾驶舱(M3-05) ----------

export interface Bucket { within1Year: number; beyond1Year: number; total: number; count: number }
export interface MonthFlow { period: string; inflow: number; outflow: number; net: number; cumulative: number }
export interface Leverage { ownCapital: number; supplierCredit: number; financing: number; total: number; note: string }
export interface Cashflow {
  asOf: string
  receivable: Bucket
  payable: Bucket
  forecast: MonthFlow[]
  leverage: Leverage
  netPosition: number
}

export function fetchCashflow(months?: number): Promise<Cashflow> {
  return request.get('/rent/cashflow', { params: months ? { months } : {} })
}

// ---------- 账期兑付缺口预警(M3-06 · P0-H) ----------

export interface GapItem {
  payableId: number
  purchaseInId?: number
  stage: string
  dueDate: string
  daysToDue: number
  payableAmount: number
  cumInflow: number
  cumOutflow: number
  projectedCash: number
  gap: number
  red: boolean
  arbiter?: string
  fundingSources?: string[]
}
export interface CoverageGapReport {
  asOf: string
  tminusDays: number
  usableReserve: number
  hasRedAlert: boolean
  redCount: number
  items: GapItem[]
}

export function fetchCoverageGap(tMinusDays?: number): Promise<CoverageGapReport> {
  return request.get('/rent/cashflow/coverage-gap', { params: tMinusDays ? { tMinusDays } : {} })
}

// ---------- 回报四源(M3-09) ----------

export interface AttributionSource {
  key: string
  label: string
  weight: number
  irrContribution: number
  source: string
}
export interface ReturnAttribution {
  customerType: string
  totalIrr: number
  sources: AttributionSource[]
  sumCheck: number
  reconciled: boolean
  note: string
}

export function fetchReturnAttribution(params: Record<string, any>): Promise<ReturnAttribution> {
  return request.get('/rent/analytics/return-attribution', { params })
}
