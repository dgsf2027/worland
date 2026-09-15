import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export const CUSTOMER_SCOPES = ['货架', '阁楼', '播种墙'] as const

export interface CustomerPoolItem {
  id: number
  /** 公司名称 */
  name: string
  legalPerson?: string
  /** 注册资本(元) */
  registeredCapital?: number
  businessScope: string[]
  contact?: string
  phone?: string
  industry?: string
  /** 可在租合同数(状态=生效) */
  activeContractCount: number
  contractTotal: number
  activeAssetCount: number
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
  id: number
  method: string
  content: string
  result?: string
  userId?: number
  userName?: string
  followTime: string
  nextFollowDate?: string
  contractId?: number
  contractNo?: string
}
export interface IntendedAsset {
  id: number
  serialNo: string
  category?: string
  model?: string
  status?: string
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
export interface CustomerAssetRow {
  id: number
  serialNo?: string
  category?: string
  model?: string
  status?: string
  allocRent?: number
}
export interface CustomerContractRow {
  id: number
  no: string
  status: string
  termMonths?: number
  monthRent?: number
  signDate?: string
  startDate?: string
  endDate?: string
  assets: CustomerAssetRow[]
}
export interface CustomerContractSummary {
  activeCount: number
  total: number
  activeAssetCount: number
  rows: CustomerContractRow[]
}
export interface CustomerDetail {
  id: number
  name: string
  legalPerson?: string
  registeredCapital?: number
  businessScope: string[]
  contact?: string
  phone?: string
  industry?: string
  phase: string
  valueTier?: string
  ownerUser?: number
  ownerName?: string
  sensitiveMasked?: boolean
  contracts: CustomerContractSummary
  /** 意向承接设备(未签约) */
  intendedAssets: IntendedAsset[]
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
export function updateFollowup(followupId: number, body: Record<string, any>): Promise<void> {
  return request.put(`/rent/customers/followups/${followupId}`, body)
}
export function deleteFollowup(followupId: number): Promise<void> {
  return request.delete(`/rent/customers/followups/${followupId}`)
}
export function updateCredit(id: number, body: Record<string, number | null>): Promise<void> {
  return request.put(`/rent/customers/${id}/credit`, body)
}
export function updateCustomerValue(id: number, body: Record<string, number | null>): Promise<void> {
  return request.put(`/rent/customers/${id}/value`, body)
}
export function addFollowup(id: number, body: Record<string, any>): Promise<number> {
  return request.post(`/rent/customers/${id}/followup`, body)
}
export function runAdmission(id: number, body: Record<string, any>): Promise<Admission> {
  return request.post(`/rent/customers/${id}/admission`, body)
}
export function createCustomer(body: Record<string, any>): Promise<number> {
  return request.post('/rent/customers', body)
}
export function updateCustomer(id: number, body: Record<string, any>): Promise<void> {
  return request.put(`/rent/customers/${id}`, body)
}

/** 客户信息一键导出(筛选条件同客户池),触发浏览器保存 xlsx。 */
export async function exportCustomers(params: Record<string, any>) {
  const blob: Blob = await request.get('/rent/customers/export', { params, responseType: 'blob', timeout: 0 })
  const d = new Date()
  const ymd = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `客户信息-${ymd}.xlsx`
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60000)
}
