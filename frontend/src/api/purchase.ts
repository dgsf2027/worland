import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface PurchaseListItem {
  id: number
  no: string
  status: string
  contractId?: number
  contractNo?: string
  customerName?: string
  supplierId?: number
  supplierName?: string
  totalAmount?: number
  itemCount?: number
  orderDate?: string
  receiveDate?: string
  payableOutstanding?: number
  sensitiveMasked?: boolean
}

export interface PurchaseItemLine {
  id: number
  serialNo: string
  category: string
  model?: string
  marketPrice?: number
  purchasePrice?: number
  supplierName?: string
  assetId?: number
  assetStatus?: string
}

export interface PayableLine {
  id: number
  stage: string
  dueDate?: string
  amount?: number
  status: string
  paidDate?: string
  remark?: string
}

export interface PurchaseDetail {
  id: number
  no: string
  status: string
  contractId?: number
  contractNo?: string
  customerName?: string
  supplierId?: number
  supplierName?: string
  totalAmount?: number
  orderDate?: string
  receiveDate?: string
  remark?: string
  sensitiveMasked?: boolean
  payableOutstanding?: number
  items: PurchaseItemLine[]
  payables: PayableLine[]
}

export function fetchPurchases(params: Record<string, any>): Promise<PageResult<PurchaseListItem>> {
  return request.get('/rent/purchase', { params })
}
export function fetchPurchaseDetail(id: number): Promise<PurchaseDetail> {
  return request.get(`/rent/purchase/${id}`)
}
export function createPurchaseOrder(body: Record<string, any>): Promise<number> {
  return request.post('/rent/purchase', body)
}
export function receivePurchase(id: number): Promise<void> {
  return request.post(`/rent/purchase/${id}/receive`)
}
export function returnPurchase(id: number, body: Record<string, any>): Promise<void> {
  return request.post(`/rent/purchase/${id}/return`, body)
}
