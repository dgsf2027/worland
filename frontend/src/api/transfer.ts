import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface TransferItem {
  id: number
  no: string
  contractId?: number
  contractNo?: string
  type: string
  assetCount?: number
  totalPrice?: number
  totalGain?: number
  status: string
  needApproval?: boolean
  approvalReason?: string
  approvedByName?: string
  approvedAt?: string
  bizTime?: string
  remark?: string
}

export interface TransferLineItem {
  id: number
  assetId: number
  serialNo?: string
  category?: string
  bookValue?: number
  marketPrice?: number
  transferPrice?: number
  gain?: number
  nominalFlag?: boolean
  voucherId?: number
  remark?: string
}

export interface TransferDetail {
  order: TransferItem
  lines: TransferLineItem[]
}

export interface TransferResult {
  transferOrderId?: number
  no?: string
  type: string
  status: string
  needApproval?: boolean
  totalPrice?: number
  totalGain?: number
  assetCount?: number
  nominalGuardHits?: string[]
  impact?: string[]
}

export function fetchTransfers(params: Record<string, any>): Promise<PageResult<TransferItem>> {
  return request.get('/rent/transfer', { params })
}
export function fetchTransferDetail(id: number): Promise<TransferDetail> {
  return request.get(`/rent/transfer/${id}`)
}
export function createExpiryTransfer(body: Record<string, any>): Promise<TransferResult> {
  return request.post('/rent/transfer/expiry', body)
}
export function approveTransfer(id: number, body: Record<string, any> = {}): Promise<TransferDetail> {
  return request.post(`/rent/transfer/${id}/approve`, body)
}
export function disposeAsset(body: Record<string, any>): Promise<TransferResult> {
  return request.post('/rent/transfer/dispose', body)
}
export function redeployAsset(body: Record<string, any>): Promise<TransferResult> {
  return request.post('/rent/transfer/redeploy', body)
}
