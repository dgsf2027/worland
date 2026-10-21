import request from '@/utils/request'

/** 采购合同 → 付款 → 进项发票(与供应商签的合同;V124)。金额单位元。 */
export interface PcSummary {
  total: number
  paid: number
  invoiced: number
  unpaid: number
  uninvoiced: number
  /** 已付但未收票 */
  paidNotInvoiced: number
}
export interface PcContract {
  id: number
  contractNo: string
  supplierId: number
  supplierName?: string
  rentContractId?: number
  totalAmount: number
  taxRate: number
  signedDate?: string
  status: string
  remark?: string
  summary: PcSummary
}
export interface PcTerm {
  id: number
  sortNo: number
  stageName: string
  ratio?: number
  amount: number
  paid: number
  unpaid: number
  conditionText?: string
}
export interface PcPayment {
  id: number
  amount: number
  paidDate: string
  bankSerial?: string
  voucherId?: number
  status: string
  remark?: string
  allocs: { termId: number; amount: number }[]
  proofCount: number
}
export interface PcInvoice {
  id: number
  invoiceNo: string
  invoiceType: string
  issueDate: string
  sellerName?: string
  amountExTax: number
  taxAmount: number
  totalAmount: number
  taxRate: number
  status: string
  remark?: string
}
export interface PcLinkedPurchase {
  id: number
  no: string
  status: string
  totalAmount?: number
  /** 该采购单仍待付的应付合计 */
  pendingPayable: number
}
export interface PcDetail {
  contract: PcContract
  purchases: PcLinkedPurchase[]
  terms: PcTerm[]
  payments: PcPayment[]
  invoices: PcInvoice[]
}

export function fetchPurchaseContracts(): Promise<PcContract[]> {
  return request.get('/rent/purchase-contracts')
}
export function fetchPurchaseContract(id: number): Promise<PcDetail> {
  return request.get(`/rent/purchase-contracts/${id}`)
}
export function createPurchaseContract(body: Record<string, any>): Promise<number> {
  return request.post('/rent/purchase-contracts', body)
}
export function payPurchaseContract(id: number, body: Record<string, any>): Promise<number> {
  return request.post(`/rent/purchase-contracts/${id}/payments`, body)
}
export function voidPcPayment(paymentId: number, reason?: string): Promise<void> {
  return request.post(`/rent/purchase-contracts/payments/${paymentId}/void`, { reason })
}
export function linkPcPurchases(id: number, purchaseInIds: number[]): Promise<void> {
  return request.post(`/rent/purchase-contracts/${id}/purchases`, { purchaseInIds })
}
export function unlinkPcPurchase(id: number, purchaseInId: number): Promise<void> {
  return request.post(`/rent/purchase-contracts/${id}/purchases/${purchaseInId}/unlink`)
}
export function addPcInvoice(id: number, body: Record<string, any>): Promise<number> {
  return request.post(`/rent/purchase-contracts/${id}/invoices`, body)
}
export function voidPcInvoice(invoiceId: number, reason?: string): Promise<void> {
  return request.post(`/rent/purchase-contracts/invoices/${invoiceId}/void`, { reason })
}
