import request from '@/utils/request'

export interface RosterAccount {
  accountId: number
  username: string
  displayName: string
  role: string
  costVisible: boolean
  ownerScoped: boolean
  active: boolean
  pendingActivation: boolean
  loginSource: string
  lastLoginAt?: string
}

export interface AccountUpdateRequest {
  role?: string
  active?: boolean
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

export function fetchRoster(): Promise<RosterAccount[]> {
  return request.get('/rent/roster/accounts')
}
export function updateRole(accountId: number, body: AccountUpdateRequest): Promise<RosterAccount> {
  return request.put(`/rent/roster/accounts/${accountId}`, body)
}
export function computeCommission(period: string): Promise<ComputeResult> {
  return request.post('/rent/commission/compute', null, { params: { period } })
}
export function fetchCommission(period: string, userId?: number): Promise<CommissionSummary[]> {
  return request.get('/rent/commission', { params: { period, userId } })
}
