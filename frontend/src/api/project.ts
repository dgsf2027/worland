import request from '@/utils/request'
import { saveFile } from '@/api/asset'
import type { ProjectPanorama } from '@/api/contract'

export interface ProjectStepInfo {
  stepNo: number
  key: string
  title: string
  /** done / active / pending */
  status: 'done' | 'active' | 'pending'
  summary?: string
  dateText?: string
}

export interface ProjectListItem {
  id: number
  projectNo: string
  name: string
  assetMode?: string
  stage: string
  currentStepNo: number
  customerId?: number
  customerName?: string
  supplierId?: number
  supplierName?: string
  rentContractId?: number
  rentContractNo?: string
  purchaseContractId?: number
  purchaseContractNo?: string
  primaryAssetId?: number
  primaryAssetNo?: string
  palletSlots?: number
  plannedTons?: number
  actualWeighTons?: number
  warehouseZone?: string
  acceptanceDate?: string
  warrantyEndDate?: string
  costVisible: boolean
  purchaseTotal?: number
  receivedRent?: number
  actualNetCashflow?: number
  expectedGrossProfit?: number
  paybackProgress?: number
  paybackPeriodNo?: number
  steps: ProjectStepInfo[]
}

export interface ProjectDetail {
  id: number
  projectNo: string
  name: string
  assetMode?: string
  stage: string
  currentStepNo: number
  customerId?: number
  customerName?: string
  supplierId?: number
  supplierName?: string
  rentContractId?: number
  rentContractNo?: string
  rentContractStatus?: string
  purchaseContractId?: number
  purchaseContractNo?: string
  purchaseContractStatus?: string
  primaryAssetId?: number
  primaryAssetNo?: string
  primaryAssetLabel?: string
  palletSlots?: number
  plannedTons?: number
  actualWeighTons?: number
  weighDiffPct?: number
  weighWithinTolerance?: boolean
  warehouseZone?: string
  deliveryAddress?: string
  deliveryContact?: string
  productionStartDate?: string
  shippedDate?: string
  installDoneDate?: string
  loadTestDate?: string
  acceptanceDate?: string
  auditPassDate?: string
  warrantyYears?: number
  warrantyEndDate?: string
  quotePrincipal?: number
  quoteAnnualRate?: number
  quoteTermMonths?: number
  quoteMonthRent?: number
  quoteBuyoutPrice?: number
  remark?: string
  costVisible: boolean
  steps: ProjectStepInfo[]
  panorama?: ProjectPanorama
}

export interface ProjectFileItem {
  id: number
  fileName: string
  contentType?: string
  size: number
  uploaderName?: string
  createTime?: string
}

export function fetchProjects(params?: { stage?: string; keyword?: string }): Promise<ProjectListItem[]> {
  return request.get('/rent/projects', { params })
}

export function fetchProjectDetail(id: number): Promise<ProjectDetail> {
  return request.get(`/rent/projects/${id}`)
}

export function createProject(body: Record<string, any>): Promise<number> {
  return request.post('/rent/projects', body)
}

export function updateProject(id: number, body: Record<string, any>): Promise<ProjectDetail> {
  return request.put(`/rent/projects/${id}`, body)
}

export function updateProjectDelivery(id: number, body: Record<string, any>): Promise<ProjectDetail> {
  return request.put(`/rent/projects/${id}/delivery`, body)
}

export function createProjectKitAsset(id: number, body?: Record<string, any>): Promise<number> {
  return request.post(`/rent/projects/${id}/kit-asset`, body || {})
}

export function syncProjectsFromContracts(): Promise<{ createdCount: number; createdNos: string[] }> {
  return request.post('/rent/projects/sync-from-contracts')
}

export function fetchBizFiles(bizType: string, bizId: number): Promise<ProjectFileItem[]> {
  return request.get('/rent/files', { params: { bizType, bizId } })
}

export function uploadBizFile(bizType: string, bizId: number, file: File): Promise<{ id: number; fileName: string }> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('bizType', bizType)
  fd.append('bizId', String(bizId))
  return request.post('/rent/files', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0,
  })
}

export async function downloadBizFile(fileId: number, fileName: string): Promise<void> {
  await saveFile(fileId, fileName)
}

export function deleteBizFile(fileId: number): Promise<void> {
  return request.delete(`/rent/files/${fileId}`)
}
