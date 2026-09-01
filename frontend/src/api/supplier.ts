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
  contact?: string
  companyAccount?: string
  openingBank?: string
  status: string
  mainCategory?: string
  itemDesc?: string
  quotePrice?: number
  firstPayRatio?: number
  scoreTotal?: number
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
  companyAccount?: string
  openingBank?: string
  status: string
  mainCategory?: string
  remark?: string
  scoreRadar?: ScoreRadar
  supplyMatrix: SupplyRow[]
  priceComposition?: PriceComposition
  costMasked?: boolean
}

export interface DependencyAlert {
  minPerCategory: number
  risks: { category: string; available: number; message: string }[]
}

export function fetchSupplierPool(params: Record<string, any>): Promise<PageResult<SupplierPoolItem>> {
  return request.get('/rent/suppliers', { params })
}
export function createSupplier(body: Record<string, any>): Promise<number> {
  return request.post('/rent/suppliers', body)
}
export function fetchSupplierDetail(id: number): Promise<SupplierDetail> {
  return request.get(`/rent/suppliers/${id}`)
}
export function fetchDependencyAlert(): Promise<DependencyAlert> {
  return request.get('/rent/suppliers/dependency-alert')
}
export function retireSupplier(id: number, reason: string): Promise<void> {
  return request.post(`/rent/suppliers/${id}/retire`, { reason })
}
