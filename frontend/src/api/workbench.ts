import request from '@/utils/request'

export interface WorkbenchKpi {
  rentedRate?: number
  rentedCount?: number
  activeAssetCount?: number
  receivableTotal?: number
  monthDistribution?: number
  weightedReturn?: number
  period?: string
}

export interface RedPoint {
  key: string
  label: string
  count: number
  level: string
  link: string
  subtitle?: string
}

export interface TaskBrief {
  id: number
  no: string
  title: string
  type: string
  source: string
  status: string
  priority: string
  overdue?: boolean
  dueDate?: string
}

export interface Workbench {
  role: string
  userName: string
  scopeNote: string
  kpi: WorkbenchKpi
  redPoints: RedPoint[]
  myTasks: TaskBrief[]
  myTaskCount: number
}

export interface WaterlinePoint {
  month: string
  plannedInflow: number
  plannedOutflow: number
  netFlow: number
  endingBalance: number
  shortfall: number
}

export interface CashWaterline {
  costVisible: boolean
  asOf: string
  months: number
  savedCashBalance?: number
  openingCashBalance?: number
  overdueReceivable?: number
  overduePayable?: number
  duePayable30dCount: number
  duePayable30dAmount?: number
  totalInflow?: number
  totalOutflow?: number
  totalNetFlow?: number
  minBalance?: number
  minBalanceMonth?: string
  firstShortfallMonth?: string | null
  maxShortfall?: number
  points: WaterlinePoint[]
}

export interface HealthItem {
  key: string
  label: string
  count: number
  level: 'ok' | 'warn' | 'critical'
  suggestion: string
  link: string
}

export interface DataHealth {
  staleTaskCount: number
  realOverdueBillCount: number
  realOverdueBillAmount: number
  pendingInvoiceCount: number
  overdueInvoiceCount: number
  unlinkedPurchaseContractCount: number
  unlinkedPurchaseInCount: number
  healthy: boolean
  items: HealthItem[]
}

export interface CleanupResult {
  cleanedCount: number
  details: string[]
}

export function fetchWorkbench(): Promise<Workbench> {
  return request.get('/rent/workbench')
}

export function fetchCashWaterline(months: number, cashBalance?: number | null): Promise<CashWaterline> {
  const params: Record<string, any> = { months }
  if (cashBalance != null && Number.isFinite(cashBalance)) params.cashBalance = cashBalance
  return request.get('/rent/workbench/cash-waterline', { params })
}

export function saveCashBalance(balance: number, remark?: string): Promise<number> {
  return request.put('/rent/workbench/cash-balance', { balance, remark })
}

export function fetchDataHealth(): Promise<DataHealth> {
  return request.get('/rent/workbench/data-health')
}

export function cleanStaleTasks(): Promise<CleanupResult> {
  return request.post('/rent/workbench/data-health/clean-stale-tasks')
}
