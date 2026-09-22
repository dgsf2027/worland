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
  /** 收租对照：该合同已收租金净额 */
  rentCollected?: number
  /** 收租对照：该合同逾期未收 */
  rentOverdueAmount?: number
  rentOverdueCount?: number
  sensitiveMasked?: boolean
}

/** 收租对照：本单货款靠这份合同的哪些租金还（按 contractId 反查收租单/逾期案） */
export interface RentCoverage {
  contractId?: number
  contractNo?: string
  customerName?: string
  /** 本单货款（敏感·GP/LP 为空） */
  purchaseTotal?: number
  paidAmount?: number
  unpaidAmount?: number
  /** 该合同租金 */
  collectedAmount?: number
  pendingAmount?: number
  overdueAmount?: number
  overdueCount?: number
  billCount?: number
  nextDueDate?: string
  nextDueAmount?: number
  openCaseCount?: number
  openCaseStep?: string
  /** 已收租金 ÷ 本单货款（敏感） */
  coverageRatio?: number
}

export interface PurchaseItemLine {
  id: number
  /** 关联的设备(设备租赁台账) */
  assetId?: number
  /** 设备显示名:品类 · 型号 */
  assetLabel?: string
  assetStatus?: string
  /** 供应商(取自设备台账) */
  supplierName?: string
  /** 付款条件摘要 */
  paymentTerms?: string
  /** 预计付款金额(= 设备合同价) */
  expectedAmount?: number
  /** 已生成应付 / 其中待付 */
  payableAmount?: number
  payableOutstanding?: number
  remark?: string
}

export interface PayableLine {
  /** 逐台应付对应的设备(旧版整单应付为空) */
  assetId?: number
  /** 设备显示名:品类 · 型号 */
  assetLabel?: string
  /** 供应商(取自设备台账) */
  supplierName?: string
  serialNo?: string
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
  rentCoverage?: RentCoverage
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
