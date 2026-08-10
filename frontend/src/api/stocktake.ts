import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface StocktakeItem {
  id: number
  no: string
  scope?: string
  status: string
  bookCount?: number
  scannedCount?: number
  diffCount?: number
  bizTime?: string
  closedAt?: string
  remark?: string
}

export interface DiffItem {
  id: number
  assetId: number
  serialNo?: string
  bookStatus?: string
  actualStatus?: string
  diffType: string
  adjusted?: boolean
  adjustEventId?: number
  remark?: string
}

export interface StocktakeDetail {
  stocktake: StocktakeItem
  diffs: DiffItem[]
}

export interface CloseResult {
  stocktakeId: number
  status: string
  adjusted: number
  profitCount: number
  lossCount: number
  mismatchCount: number
  impact: string[]
}

export function fetchStocktakes(params: Record<string, any>): Promise<PageResult<StocktakeItem>> {
  return request.get('/rent/stocktake', { params })
}
export function fetchStocktakeDetail(id: number): Promise<StocktakeDetail> {
  return request.get(`/rent/stocktake/${id}`)
}
export function createStocktake(body: Record<string, any>): Promise<number> {
  return request.post('/rent/stocktake', body)
}
export function scanStocktake(id: number, body: Record<string, any>): Promise<StocktakeDetail> {
  return request.post(`/rent/stocktake/${id}/scan`, body)
}
export function closeStocktake(id: number): Promise<CloseResult> {
  return request.post(`/rent/stocktake/${id}/close`, {})
}
