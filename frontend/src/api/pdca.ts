import request from '@/utils/request'

export interface Indicator {
  metricKey: string
  label: string
  value: number | null
  target: number | null
  compareOp: string
  unit: string
  light: string
  note: string
  source: string
}

export interface BoardResp {
  indicators: Indicator[]
  redCount: number
  greenCount: number
  aiText: string
  llmCallId?: number
  model?: string
  mock: boolean
  cacheHit: boolean
}

export interface ItemRow {
  id: number
  no: string
  metricScene: string
  issue: string
  action: string
  metricKey?: string
  metricParam?: string
  targetValue?: number
  compareOp?: string
  baselineValue?: number
  verifyValue?: number
  verifyResult?: string
  verifyNote?: string
  recheckDate?: string
  dueOrOverdue: boolean
  ownerRole?: string
  ownerUserName?: string
  status: string
  aiDraft?: number
  creatorName?: string
  createTime?: string
}

export interface ItemSaveReq {
  metricScene: string
  issue: string
  action: string
  metricKey?: string
  metricParam?: string
  targetValue?: number
  compareOp?: string
  recheckDate?: string
  ownerRole?: string
  ownerUserId?: number
  ownerUserName?: string
  remark?: string
}

export interface MetricDefRow {
  key: string
  label: string
  unit: string
  compareOp: string
  defaultTarget?: number
  currentValue?: number
  note: string
}

export interface RecheckResp {
  id: number
  targetValue?: number
  verifyValue?: number
  verifyResult: string
  newStatus: string
  note: string
}

export interface RecheckBatchResp {
  total: number
  passed: number
  failed: number
  manual: number
  results: RecheckResp[]
}

export const pdcaApi = {
  board: (force = false) => request.get<any, BoardResp>('/rent/pdca/board', { params: { force } }),
  items: (status?: string, scene?: string) =>
    request.get<any, ItemRow[]>('/rent/pdca/items', { params: { status, scene } }),
  create: (req: ItemSaveReq) => request.post<any, number>('/rent/pdca/items', req),
  update: (id: number, req: ItemSaveReq) => request.put<any, void>(`/rent/pdca/items/${id}`, req),
  close: (id: number, note?: string) => request.post<any, void>(`/rent/pdca/items/${id}/close`, { note }),
  recheck: (id: number) => request.post<any, RecheckResp>(`/rent/pdca/items/${id}/recheck`),
  recheckDue: () => request.post<any, RecheckBatchResp>('/rent/pdca/items/recheck-due'),
  metrics: () => request.get<any, MetricDefRow[]>('/rent/pdca/metrics'),
}
