import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface TransferLogItem {
  from?: string
  to?: string
  at?: string
  by?: string
  reason?: string
}

export interface TaskItem {
  id: number
  no: string
  title: string
  type: string
  assigneeRole?: string
  assigneeUserId?: number
  assigneeUserName?: string
  source: string
  status: string
  priority: string
  bizType?: string
  bizId?: number
  dueDate?: string
  overdue?: boolean
  verifyRequired?: boolean
  verifyEvidence?: string
  creatorName?: string
  finishedAt?: string
  remark?: string
  transferLog?: TransferLogItem[]
  createTime?: string
}

export interface SystemScanResult {
  overdueOpened: number
  coverageGapOpened: number
  expiryOpened: number
  total: number
}

export function fetchTasks(params: Record<string, any>): Promise<PageResult<TaskItem>> {
  return request.get('/rent/tasks', { params })
}
export function dispatchTask(body: Record<string, any>): Promise<number> {
  return request.post('/rent/tasks', body)
}
export function transferTask(id: number, body: Record<string, any>): Promise<TaskItem> {
  return request.post(`/rent/tasks/${id}/transfer`, body)
}
export function startTask(id: number): Promise<TaskItem> {
  return request.post(`/rent/tasks/${id}/start`)
}
export function completeTask(id: number, body: Record<string, any> = {}): Promise<TaskItem> {
  return request.post(`/rent/tasks/${id}/complete`, body)
}
export function systemScan(): Promise<SystemScanResult> {
  return request.post('/rent/tasks/system-scan')
}
