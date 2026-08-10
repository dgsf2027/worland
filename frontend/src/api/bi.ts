import request from '@/utils/request'

export interface MatrixRow {
  dimKey: string
  value: number
  numerator?: number
  denominator?: number
  weight?: number
}

export interface MatrixResp {
  metric: string
  metricLabel: string
  dimension: string
  unit: string
  overall: number
  overallDesc: string
  rows: MatrixRow[]
  source: string
}

export interface AgingBucket {
  bucket: string
  amount: number
  count: number
}

export interface AgingResp {
  dimension: string
  totalOverdue: number
  buckets: AgingBucket[]
  rows: MatrixRow[]
  source: string
}

export interface TrendPoint {
  period: string
  value: number
}

export interface TrendResp {
  metric: string
  metricLabel: string
  unit: string
  points: TrendPoint[]
  source: string
}

export const biApi = {
  occupancy: (dim?: string) => request.get<any, MatrixResp>('/rent/bi/occupancy', { params: { dim } }),
  weightedReturn: (dim?: string) => request.get<any, MatrixResp>('/rent/bi/weighted-return', { params: { dim } }),
  receivableAging: () => request.get<any, AgingResp>('/rent/bi/receivable-aging'),
  assetTurnover: (dim?: string) => request.get<any, MatrixResp>('/rent/bi/asset-turnover', { params: { dim } }),
  trend: (metric?: string, months?: number) =>
    request.get<any, TrendResp>('/rent/bi/trend', { params: { metric, months } }),
}
