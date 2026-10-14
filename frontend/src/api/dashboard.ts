import { ref } from 'vue'
import request from '@/utils/request'

export type ResourceStatus = 'idle' | 'loading' | 'ready' | 'error' | 'restricted'
export interface Resource<T> { data: T | null; status: ResourceStatus; source: string }
export interface CustomerStage { phase: string; count: number }
export interface InventorySummary { itemCount: number; activeRentalCount: number; overdueRentalCount: number }
export interface RentTrend { points: { period: string; receivable: number; collected: number }[] }
export interface AmountBucket { within1Year: number; beyond1Year: number; total: number; count: number }
export interface CashflowSummary {
  asOf: string
  receivable: AmountBucket
  payable: AmountBucket
  forecast: { period: string; inflow: number; outflow: number; net: number }[]
}
export interface DashboardData {
  months: number
  customerStages: Resource<CustomerStage[]>
  inventory: Resource<InventorySummary>
  rentTrend: Resource<RentTrend>
  cashflow: Resource<CashflowSummary>
}
const empty = <T>(status: ResourceStatus = 'idle'): Resource<T> => ({ data: null, status, source: '' })
function initial(status: ResourceStatus = 'idle'): DashboardData {
  return { months: 6, customerStages: empty(status), inventory: empty(status), rentTrend: empty(status), cashflow: empty(status) }
}
/** 刷新时保留上一次的图表，新数据到了再整体替换，避免闪成空白。 */
export function useDashboardData() {
  const charts = ref<DashboardData>(initial())
  let generation = 0
  async function load(months: number) {
    const current = ++generation
    try {
      const data = await request.get<any, DashboardData>('/rent/workbench/charts', { params: { months } })
      if (current === generation) charts.value = data
    } catch {
      if (current === generation) charts.value = initial('error')
    }
  }
  return { charts, load }
}
