import request from '@/utils/request'

export interface PageResult<T> {
  total: number
  page: number
  size: number
  records: T[]
}

export interface SupplierPoolItem {
  id: number
  name: string
  fullName?: string
  contact?: string
  status: string
  mainCategory?: string
  itemDesc?: string
  quotePrice?: number
  firstPayRatio?: number
  scoreTotal?: number
  /** 收款资料是否齐(公司全称+开户行+银行账号) */
  billingComplete?: boolean
}

export interface ScoreRadar {
  quality: number
  delivery: number
  service: number
  price: number
  term: number
  total: number
}

export interface SupplyRow {
  id: number
  itemType: string
  itemName: string
  category?: string
  quotePrice?: number
  firstPayRatio?: number
  accountDays?: number
  canSingleBuy: boolean
  scoreTotal?: number
}

export interface PriceComposition {
  material?: number
  processing?: number
  profit?: number
  quote?: number
  bomEstimate?: number
  verdict?: string
}

export interface SupplierDetail {
  id: number
  name: string
  contact?: string
  phone?: string
  status: string
  mainCategory?: string
  remark?: string
  // 工商 / 开票 / 收款
  fullName?: string
  taxNo?: string
  regAddress?: string
  regPhone?: string
  bankName?: string
  bankAccount?: string
  accountName?: string
  invoiceType?: string
  bankMasked?: boolean
  scoreRadar?: ScoreRadar
  supplyMatrix: SupplyRow[]
  priceComposition?: PriceComposition
  costMasked?: boolean
}

export interface SupplyItemInput {
  itemType?: string
  itemName: string
  category?: string
  quotePrice?: number | null
  firstPayRatio?: number | null
  accountDays?: number | null
  noInterest?: number
  canSingleBuy?: number
  scoreQuality?: number | null
  scoreDelivery?: number | null
  scoreService?: number | null
  scorePrice?: number | null
  scoreTerm?: number | null
  costMaterial?: number | null
  costProcessing?: number | null
  profitAmount?: number | null
  bomEstimate?: number | null
  isPrimary?: number
  remark?: string
}

export interface SupplierSaveRequest {
  name: string
  fullName?: string
  taxNo?: string
  regAddress?: string
  regPhone?: string
  bankName?: string
  bankAccount?: string
  accountName?: string
  invoiceType?: string
  contact?: string
  phone?: string
  mainCategory?: string
  status?: string
  remark?: string
  /** 编辑时全量覆盖供货矩阵;不传则保持原样 */
  supplies?: SupplyItemInput[]
}

export interface DependencyAlert {
  minPerCategory: number
  risks: { category: string; available: number; message: string }[]
}

export function fetchSupplierPool(params: Record<string, any>): Promise<PageResult<SupplierPoolItem>> {
  return request.get('/rent/suppliers', { params })
}
export function fetchSupplierDetail(id: number): Promise<SupplierDetail> {
  return request.get(`/rent/suppliers/${id}`)
}
export function fetchDependencyAlert(): Promise<DependencyAlert> {
  return request.get('/rent/suppliers/dependency-alert')
}
export function createSupplier(body: SupplierSaveRequest): Promise<number> {
  return request.post('/rent/suppliers', body)
}
export function updateSupplier(id: number, body: SupplierSaveRequest): Promise<void> {
  return request.put(`/rent/suppliers/${id}`, body)
}
export function retireSupplier(id: number, reason: string): Promise<void> {
  return request.post(`/rent/suppliers/${id}/retire`, { reason })
}
