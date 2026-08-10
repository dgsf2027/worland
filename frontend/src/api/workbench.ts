import request from '@/utils/request'

export interface WorkbenchKpi {
  rentedRate?: number
  rentedCount?: number
  activeAssetCount?: number
  receivableTotal?: number
  monthDistribution?: number
  weightedReturn?: number
  period?: string
}

export interface RedPoint {
  key: string
  label: string
  count: number
  level: string
  link: string
}

export interface TaskBrief {
  id: number
  no: string
  title: string
  type: string
  source: string
  status: string
  priority: string
  overdue?: boolean
  dueDate?: string
}

export interface Workbench {
  role: string
  userName: string
  scopeNote: string
  kpi: WorkbenchKpi
  redPoints: RedPoint[]
  myTasks: TaskBrief[]
  myTaskCount: number
}

export function fetchWorkbench(): Promise<Workbench> {
  return request.get('/rent/workbench')
}
