import request from '@/utils/request'

export interface RosterItem {
  id: number
  userId: number
  userName: string
  role: string
  dataScope?: string
  costVisible?: boolean
  ownerScoped?: boolean
  projectId?: number
  active?: boolean
  remark?: string
}

export interface CommissionLine {
  id: number
  period: string
  userId: number
  userName: string
  role?: string
  type: string
  baseAmount?: number
  rate?: number
  commissionAmount?: number
  fundedFrom?: string
  sourceRef?: string
  orderCount?: number
  createTime?: string
}

export interface CommissionSummary {
  period: string
  userId: number
  userName: string
  role?: string
  costCutOrders: number
  costCutBase?: number
  costCutCommission?: number
  dealOrders: number
  dealBase?: number
  dealCommission?: number
  totalCommission?: number
  lines: CommissionLine[]
}

export interface ComputeResult {
  period: string
  costCutRows: number
  dealRows: number
  skipped: number
  totalCommission?: number
}

export function fetchRoster(): Promise<RosterItem[]> {
  return request.get('/rent/roster')
}
export function updateRole(id: number, body: Record<string, any>): Promise<RosterItem> {
  return request.put(`/rent/roster/${id}`, body)
}
export function computeCommission(period: string): Promise<ComputeResult> {
  return request.post('/rent/commission/compute', null, { params: { period } })
}
export function fetchCommission(period: string, userId?: number): Promise<CommissionSummary[]> {
  return request.get('/rent/commission', { params: { period, userId } })
}
