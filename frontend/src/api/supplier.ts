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
  /** 关联的合格考察记录;非考察建档为空 */
  inspectionId?: number
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
  /** 关联的合格考察记录 */
  inspection?: InspectionItem
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

// ---- 供应商考察 ----
export const INSPECTION_SCOPES = ['货架', '阁楼', '播种墙'] as const
export const INSPECTION_ARCHIVE_EXTS = ['zip', 'rar', '7z']
export const INSPECTION_ARCHIVE_MAX_MB = 1024

export interface InspectionItem {
  id: number
  companyName: string
  legalPerson?: string
  /** 注册资本(元) */
  registeredCapital?: number
  businessScope: string[]
  contact?: string
  phone?: string
  result: '待考察' | '合格' | '不合格'
  conclusion?: string
  decidedByName?: string
  decidedAt?: string
  supplierId?: number
  supplierName?: string
  supplierStatus?: string
  archiveCount: number
  remark?: string
  createTime?: string
}

export interface InspectionDecideResult {
  result: string
  supplierId?: number
  supplierCreated?: boolean
  message: string
}

export interface FileItem {
  id: number
  fileName: string
  contentType?: string
  size: number
  uploaderName?: string
  createTime?: string
}

export function fetchInspections(params: Record<string, any>): Promise<PageResult<InspectionItem>> {
  return request.get('/rent/supplier-inspections', { params })
}
export function createInspection(body: Record<string, any>): Promise<number> {
  return request.post('/rent/supplier-inspections', body)
}
export function updateInspection(id: number, body: Record<string, any>): Promise<void> {
  return request.put(`/rent/supplier-inspections/${id}`, body)
}
export function deleteInspection(id: number): Promise<void> {
  return request.delete(`/rent/supplier-inspections/${id}`)
}
export function decideInspection(id: number, result: '合格' | '不合格', conclusion: string): Promise<InspectionDecideResult> {
  return request.post(`/rent/supplier-inspections/${id}/decide`, { result, conclusion })
}

export function uploadInspectionArchive(id: number, file: File, onProgress?: (percent: number) => void): Promise<FileItem> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('bizType', 'supplier_inspection')
  fd.append('bizId', String(id))
  return request.post('/rent/files', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0, // 压缩包可达 1GB,不走全局 15s 超时
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
}
export function fetchInspectionArchives(id: number): Promise<FileItem[]> {
  return request.get('/rent/files', { params: { bizType: 'supplier_inspection', bizId: id } })
}
