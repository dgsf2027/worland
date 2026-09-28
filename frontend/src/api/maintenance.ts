import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface MaintenanceItem {
  id: number
  no: string
  /** 对象类型：asset 设备租赁台账 / inv_item 资产管理仓库物品 */
  targetType?: string
  /** 对象显示名：设备「品类 · 序列号」/ 物品「名称 · 编号」 */
  targetLabel?: string
  /** 仓库物品 id（设备工单为空） */
  invItemId?: number
  /** 送修数量（仓库物品工单） */
  qty?: number
  assetId?: number
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
