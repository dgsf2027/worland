import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface TransferItem {
  id: number
  no: string
  contractId?: number
  contractNo?: string
  /** 合同客户（到期转让必有；二手/报废按台处置时为空） */
  customerName?: string
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
  /** 设备显示名：品类 · 型号（设备已删则为「#id」） */
  assetLabel?: string
  serialNo?: string
  category?: string
  model?: string
  /** 设备当前台账状态（设备已删为空） */
  assetStatus?: string
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
