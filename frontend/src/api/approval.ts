import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface ApprovalItem {
  id: number
  no: string
  type: string
  bizType?: string
  bizId?: number
  subject?: string
  amount?: number
  principalReturnRate?: number
  targetRate?: number
  selfLimit?: number
  decisionMode: string
  status: string
  applicantName?: string
  approverName?: string
  approvedAt?: string
  decisionReason?: string
  createTime?: string
}

export function fetchApprovals(params: Record<string, any>): Promise<PageResult<ApprovalItem>> {
  return request.get('/rent/approvals', { params })
}
export function initiateApproval(body: Record<string, any>): Promise<number> {
  return request.post('/rent/approvals', body)
}
export function approveApproval(id: number, body: Record<string, any> = {}): Promise<ApprovalItem> {
  return request.post(`/rent/approvals/${id}/approve`, body)
}
export function rejectApproval(id: number, body: Record<string, any> = {}): Promise<ApprovalItem> {
  return request.post(`/rent/approvals/${id}/reject`, body)
}
