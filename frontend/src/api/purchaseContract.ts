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
  /** 主合同(客户租赁合同)信息 */
  rentContractNo?: string
  customerName?: string
  rentTermMonths?: number
  rentMonthRent?: number
  rentStartDate?: string
  rentStatus?: string
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
  /** 合同原件张数、各发票原件张数 */
  contractProofCount: number
  invoiceProofCounts: Record<number, number>
  purchases: PcLinkedPurchase[]
  terms: PcTerm[]
  payments: PcPayment[]
  invoices: PcInvoice[]
}

/** 拍照识别结果(只用于预填,字段缺失为 null) */
export type RecognizeKind = 'contract' | 'receipt' | 'invoice'
export interface RecognizedContract {
  contractNo?: string | null
  supplierName?: string | null
  mainContractNo?: string | null
  signedDate?: string | null
  totalAmount?: number | null
  taxRate?: number | null
  terms: { stageName?: string | null; ratio?: number | null; amount?: number | null; conditionText?: string | null }[]
}
export interface RecognizedReceipt {
  amount?: number | null
  paidDate?: string | null
  bankSerial?: string | null
  payerName?: string | null
  payeeName?: string | null
  remark?: string | null
}
export interface RecognizedInvoice {
  invoiceNo?: string | null
  invoiceType?: string | null
  issueDate?: string | null
  sellerName?: string | null
  buyerName?: string | null
  amountExTax?: number | null
  taxAmount?: number | null
  totalAmount?: number | null
  taxRate?: number | null
}
/** images=要看图识别的图片；text=从 PDF/文本里提取的文字(可空，二者至少给一项) */
export function recognizePcDoc(kind: RecognizeKind, images: File[], text?: string): Promise<any> {
  const fd = new FormData()
  fd.append('kind', kind)
  images.forEach((f) => fd.append('files', f))
  if (text) fd.append('text', text)
  return request.post('/rent/purchase-contracts/recognize', fd, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 })
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
