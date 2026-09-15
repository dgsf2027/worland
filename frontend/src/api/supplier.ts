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
  noInterest: boolean
  canSingleBuy: boolean
  /** 代表供货项(履约评分/价格构成取此行) */
  primary: boolean
  remark?: string
  scoreTotal?: number
}

export interface LinkedAsset {
  id: number
  serialNo: string
  category?: string
  model?: string
  status?: string
  purchasePrice?: number
  currentHolderName?: string
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
  primarySupplyId?: number
  /** 履约评分权重 quality/delivery/service/price/term */
  scoreWeights: Record<string, number>
  /** 设备租赁台账里供应商为本供应商的设备 */
  linkedAssets: LinkedAsset[]
}

export interface SupplierImportResult {
  supplierRows: number
  suppliersCreated: number
  suppliersUpdated: number
  supplyRows: number
  suppliesCreated: number
  suppliesUpdated: number
  skipped: number
  messages: { sheet: string; row: number; name?: string; level: 'info' | 'warn'; message: string }[]
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
export function deleteSupplier(id: number): Promise<void> {
  return request.delete(`/rent/suppliers/${id}`)
}
export function updateSupplierScores(id: number, body: Record<string, number | null>): Promise<void> {
  return request.put(`/rent/suppliers/${id}/scores`, body)
}
export function updateSupplierPrice(id: number, body: Record<string, number | null>): Promise<void> {
  return request.put(`/rent/suppliers/${id}/price-composition`, body)
}
export function addSupply(id: number, body: Record<string, any>): Promise<number> {
  return request.post(`/rent/suppliers/${id}/supplies`, body)
}
export function updateSupply(supplyId: number, body: Record<string, any>): Promise<void> {
  return request.put(`/rent/suppliers/supplies/${supplyId}`, body)
}
export function deleteSupply(supplyId: number): Promise<void> {
  return request.delete(`/rent/suppliers/supplies/${supplyId}`)
}

/** 导出供应商(供应商 + 供货矩阵两张表);template=true 只下载表头模板。 */
export async function exportSuppliers(template = false) {
  const blob: Blob = await request.get('/rent/suppliers/export', { params: { template }, responseType: 'blob', timeout: 0 })
  const d = new Date()
  const ymd = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = template ? '供应商上游-模板.xlsx' : `供应商上游-${ymd}.xlsx`
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60000)
}

export function importSuppliers(file: File): Promise<SupplierImportResult> {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/rent/suppliers/import', fd, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 0 })
}

// ---- 供应商考察 ----
export const INSPECTION_SCOPES = ['货架', '阁楼', '播种墙'] as const
export const INSPECTION_ARCHIVE_EXTS = ['zip', 'rar', '7z']
export const INSPECTION_ARCHIVE_MAX_MB = 1024

export interface InspectionItem {
  id: number
  /** 序号(列表排序) */
  sortNo?: number
  companyName: string
  legalPerson?: string
  /** 注册资本(万元·原文,如 200 / 60*6) */
  registeredCapitalWan?: string
  establishedDate?: string
  businessScope: string[]
  address?: string
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

export interface InspectionImportResult {
  total: number
  created: number
  updated: number
  passed: number
  failed: number
  skipped: number
  messages: { row: number; companyName?: string; level: 'info' | 'warn'; message: string }[]
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

/** 导出「厂家考察汇总表」;template=true 只下载表头空模板。 */
export async function exportInspections(template = false) {
  const blob: Blob = await request.get('/rent/supplier-inspections/export', {
    params: { template }, responseType: 'blob', timeout: 0,
  })
  const d = new Date()
  const ymd = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = template ? '厂家考察汇总表-模板.xlsx' : `厂家考察汇总表-${ymd}.xlsx`
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60000)
}

/** 导入「厂家考察汇总表」(.xls/.xlsx),按公司名称新增或更新。 */
export function importInspections(file: File): Promise<InspectionImportResult> {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/rent/supplier-inspections/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0,
  })
}
