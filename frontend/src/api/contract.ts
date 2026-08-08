import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface ContractListItem {
  id: number
  no: string
  customerId: number
  customerName?: string
  status: string
  termMonths: number
  monthRent: number
  endTransferPrice?: number
  assetCount?: number
  startDate?: string
  elapsedPeriods?: number
}

export interface Reconciliation {
  periods: number
  monthRent: number
  rentTotal: number
  scheduleSum: number
  endTransferPrice: number
  customerTotal: number
  scheduleBalanced: boolean
  balanced: boolean
  deposit: number
}
export interface ScheduleLine {
  periodNo: number
  dueDate: string
  amount: number
  planStatus: string
  rentBillId?: number
  dueState: string
}
export interface RentComposition {
  monthRent: number
  principal: number
  capitalCost: number
  residualReserve: number
  margin: number
  note?: string
}
export interface Pnl {
  rentTotal: number
  transferPrice: number
  purchaseCost: number
  capitalCost: number
  badDebtReserve: number
  netProfit: number
  netMargin: number
  note?: string
}
export interface ContractDetail {
  id: number
  no: string
  customerId: number
  customerName?: string
  status: string
  nature: string
  termMonths: number
  monthRent: number
  deposit: number
  endTransferPrice: number
  targetIrr?: number
  signDate?: string
  startDate?: string
  remark?: string
  sensitiveMasked?: boolean
  assets: { assetId: number; serialNo?: string; category?: string; model?: string; assetStatus?: string; allocRent: number }[]
  reconciliation: Reconciliation
  repayment: { totalPeriods: number; elapsedPeriods: number; billedPeriods: number; progressRatio: number }
  schedule: ScheduleLine[]
  rentComposition?: RentComposition
  pnl?: Pnl
  depositLedger: { direction: string; amount: number; bizTime: string; remark?: string }[]
  changes: { changeType: string; isReverse: boolean; detail?: string; bizTime: string; operatorName?: string }[]
}

export function fetchContracts(params: Record<string, any>): Promise<PageResult<ContractListItem>> {
  return request.get('/rent/contracts', { params })
}
export function fetchContractDetail(id: number): Promise<ContractDetail> {
  return request.get(`/rent/contracts/${id}`)
}
export function signContract(body: Record<string, any>): Promise<number> {
  return request.post('/rent/contracts', body)
}
export function voidContract(id: number, body: Record<string, any>): Promise<void> {
  return request.post(`/rent/contracts/${id}/void`, body)
}
export function renewContract(id: number, body: Record<string, any>): Promise<void> {
  return request.post(`/rent/contracts/${id}/renew`, body)
}
export function changeContract(id: number, body: Record<string, any>): Promise<void> {
  return request.post(`/rent/contracts/${id}/change`, body)
}
