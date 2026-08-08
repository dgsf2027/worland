import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface CustomerPoolItem {
  id: number
  name: string
  phase: string
  ownerName?: string
  ownerUser?: number
  valueTier?: string
  rating?: string
  ratingPredicted?: boolean
  exposureOrOppAmount?: number
  receivableOverdue?: number
  nextFollowDate?: string
  followStatus?: string
  inPublicPool?: boolean
  sensitiveMasked?: boolean
}

export interface CreditProfile {
  profit: number
  cashflow: number
  stability: number
  history: number
  industry: number
  compositeScore?: number
  rating?: string
}
export interface ValueExposure {
  contractCount: number
  cumulativeRent: number
  cumulativeProfit?: number
  renewRate?: number
  exposureAmount: number
  receivableOverdue: number
  concentration: number
}
export interface FollowupItem {
  method: string
  content: string
  result?: string
  userName?: string
  followTime: string
  nextFollowDate?: string
}
export interface Admission {
  suggestCreditLimit?: number
  suggestDepositMonths?: number
  suggestTargetIrr?: number
  approvedCreditLimit?: number
  approvedDepositMonths?: number
  approvedTargetIrr?: number
  note?: string
}
export interface CustomerDetail {
  id: number
  name: string
  contact?: string
  phone?: string
  industry?: string
  phase: string
  valueTier?: string
  ownerName?: string
  sensitiveMasked?: boolean
  creditProfile?: CreditProfile
  valueExposure: ValueExposure
  timeline: FollowupItem[]
  admission: Admission
}

export interface PipelineColumn {
  phase: string
  count: number
  cards: { customerId: number; name: string; ownerName?: string; amount?: number; tag?: string }[]
}
export interface Pipeline {
  weightedForecast?: number
  columns: PipelineColumn[]
}

export function fetchCustomerPool(params: Record<string, any>): Promise<PageResult<CustomerPoolItem>> {
  return request.get('/rent/customers', { params })
}
export function fetchCustomerDetail(id: number): Promise<CustomerDetail> {
  return request.get(`/rent/customers/${id}`)
}
export function fetchPipeline(): Promise<Pipeline> {
  return request.get('/rent/customers/pipeline')
}
export function addFollowup(id: number, body: Record<string, any>): Promise<number> {
  return request.post(`/rent/customers/${id}/followup`, body)
}
export function runAdmission(id: number, body: Record<string, any>): Promise<Admission> {
  return request.post(`/rent/customers/${id}/admission`, body)
}
