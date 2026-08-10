import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface MaintenanceItem {
  id: number
  no: string
  assetId: number
  serialNo?: string
  assetCategory?: string
  bomId?: number
  bomName?: string
  type: string
  status: string
  faultDesc?: string
  inWarranty?: boolean
  responsibleParty?: string
  supplierId?: number
  supplierName?: string
  cost?: number
  ourCost?: number
  handleNote?: string
  reportedAt?: string
  assignedAt?: string
  finishedAt?: string
  remark?: string
}

export interface SparePartAlert {
  assetId: number
  serialNo?: string
  bomId?: number
  bomName?: string
  faultCount?: number
  repairable?: boolean
  suggestion?: string
}
export interface SparePartAlertResponse {
  threshold: number
  alertCount: number
  items: SparePartAlert[]
}

export function fetchMaintenances(params: Record<string, any>): Promise<PageResult<MaintenanceItem>> {
  return request.get('/rent/maintenance', { params })
}
export function fetchMaintenanceDetail(id: number): Promise<MaintenanceItem> {
  return request.get(`/rent/maintenance/${id}`)
}
export function createMaintenance(body: Record<string, any>): Promise<number> {
  return request.post('/rent/maintenance', body)
}
export function assignMaintenance(id: number, body: Record<string, any>): Promise<MaintenanceItem> {
  return request.post(`/rent/maintenance/${id}/assign`, body)
}
export function handleMaintenance(id: number, body: Record<string, any>): Promise<MaintenanceItem> {
  return request.post(`/rent/maintenance/${id}/handle`, body)
}
export function fetchSpareAlert(): Promise<SparePartAlertResponse> {
  return request.get('/rent/maintenance/spare-alert')
}
