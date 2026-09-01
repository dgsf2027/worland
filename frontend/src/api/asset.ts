import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface AssetListItem {
  id: number
  serialNo: string
  category: string
  model?: string
  status: string
  marketPrice?: number
  purchasePrice?: number
  bookValue?: number
  residualValue?: number
  supplierId?: number
  supplierName?: string
  currentHolderName?: string
  sensitiveMasked?: boolean
}

export interface BomNode {
  id: number
  parentId?: number
  name: string
  qty?: number
  unitCost?: number
  subtotal?: number
  supplierId?: number
  supplierName?: string
  lifeYears?: number
  warrantyUntil?: string
  repairable?: boolean
  faultCount?: number
  residualRate?: number
  remark?: string
  children?: BomNode[]
}
export interface BomAttachment {
  id: number
  fileName: string
  contentType?: string
  size: number
  uploaderName?: string
  createTime?: string
}

export interface SignedUrl {
  id: number
  url: string
  expiresAt: number
  ttlSeconds: number
}

export interface CostItem { name: string; amount: number; ratio: number }
export interface AssetDetail {
  id: number
  serialNo: string
  category: string
  model?: string
  status: string
  marketPrice?: number
  purchasePrice?: number
  monthlyLaborValue?: number
  replaceHeadcount?: number
  supplierId?: number
  supplierName?: string
  currentHolderName?: string
  contractId?: number
  remark?: string
  sensitiveMasked?: boolean
  bookValue?: number
  residualValue?: number
  selfPurchasePayback?: number
  bom: BomNode[]
  costBreakdown?: { items: CostItem[]; total: number; purchasePrice?: number; gapVsPurchase?: number }
  residualBreakdown?: { items?: CostItem[]; bomResidualTotal?: number; categoryResidual?: number }
  faultArchive: { name: string; faultCount: number; repairable: boolean; warrantyUntil?: string; supplierName?: string; warrantyDaysLeft?: number }[]
  singleUnitReturn: { cumulativeRent?: number; allocRent?: number; inServiceDays?: number; idleDays?: number; returnRate?: number; idleAlert?: boolean }
  timeline: { eventType: string; bizTime: string; refDocType?: string; refDocId?: number; operatorName?: string; remark?: string }[]
}

export function fetchAssets(params: Record<string, any>): Promise<PageResult<AssetListItem>> {
  return request.get('/rent/assets', { params })
}
export function fetchAssetDetail(id: number): Promise<AssetDetail> {
  return request.get(`/rent/assets/${id}`)
}
export function createAsset(body: Record<string, any>): Promise<number> {
  return request.post('/rent/assets', body)
}
export function updateAsset(id: number, body: Record<string, any>): Promise<void> {
  return request.put(`/rent/assets/${id}`, body)
}
export function changeAssetStatus(id: number, body: Record<string, any>): Promise<void> {
  return request.post(`/rent/assets/${id}/status`, body)
}
export function addBom(id: number, body: Record<string, any>): Promise<number> {
  return request.post(`/rent/assets/${id}/bom`, body)
}
export function updateBom(bomId: number, body: Record<string, any>): Promise<void> {
  return request.put(`/rent/assets/bom/${bomId}`, body)
}
export function deleteBom(bomId: number): Promise<void> {
  return request.delete(`/rent/assets/bom/${bomId}`)
}

export function uploadBomAttachment(bomId: number, file: File): Promise<BomAttachment> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('bizType', 'asset_bom')
  fd.append('bizId', String(bomId))
  return request.post('/rent/files', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function fetchBomAttachments(bomId: number): Promise<BomAttachment[]> {
  return request.get('/rent/files', { params: { bizType: 'asset_bom', bizId: bomId } })
}

export function fetchFileSignedUrl(fileId: number): Promise<SignedUrl> {
  return request.get('/rent/files/' + fileId + '/signed-url')
}
