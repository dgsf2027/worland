import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface ContractListItem {
  id: number
  no: string
  customerId: number
  customerName?: string
  status: string
  termMonths: number
  monthRent: number
  /** 设备总价(含税)= 合同清单合计 */
  equipmentTotal?: number
  endTransferPrice?: number
  assetCount?: number
  startDate?: string
  elapsedPeriods?: number
}

export interface Reconciliation {
  periods: number
  monthRent: number
  rentTotal: number
  scheduleSum: number
  endTransferPrice: number
  customerTotal: number
  scheduleBalanced: boolean
  balanced: boolean
  deposit: number
}
export interface ScheduleLine {
  periodNo: number
  dueDate: string
  amount: number
  planStatus: string
  rentBillId?: number
  dueState: string
}
export interface RentComposition {
  monthRent: number
  principal: number
  capitalCost: number
  residualReserve: number
  margin: number
  note?: string
}
export interface Pnl {
  rentTotal: number
  transferPrice: number
  purchaseCost: number
  capitalCost: number
  badDebtReserve: number
  netProfit: number
  netMargin: number
  note?: string
}
/** 合同清单行(《工程量清单计价表》9 列 + 生成设备品类) */
export interface BoqLine {
  id?: number
  seq?: number
  name: string
  model?: string | null
  spec?: string | null
  unit?: string | null
  qty?: number | null
  /** 单价(含税) */
  unitPrice?: number | null
  /** 金额(含税);null = 表格里的「-」(赠送/不计价) */
  amount?: number | null
  /** true=金额手填(赠送/优惠行) */
  amountManual?: boolean
  /** 生成设备的品类;空=本行不生成设备 */
  assetCategory?: string | null
  /** 已生成设备台数(只读) */
  generatedCount?: number
  /** 还可生成台数(只读) */
  pendingCount?: number
  remark?: string | null
}

export interface Boq {
  lines: BoqLine[]
  totalWithTax?: number
  totalWithoutTax?: number
  taxAmount?: number
  taxRate?: number
  totalUpper?: string
  linked?: boolean
  generatedAssets?: number
  pendingAssets?: number
}

export interface BoqImportResult {
  total: number
  imported: number
  skipped: number
  totalWithTax?: number
  messages: string[]
}

export interface BoqGenerateResult {
  created: number
  messages: string[]
}

/** 清单行可生成设备的品类 */
export const BOQ_ASSET_CATEGORIES = ['播种墙', '货架', '阁楼', '配件']

export interface ContractDetail {
  id: number
  no: string
  customerId: number
  customerName?: string
  status: string
  nature: string
  termMonths: number
  monthRent: number
  deposit: number
  endTransferPrice: number
  targetIrr?: number
  /** 合同税率(0-1) */
  taxRate?: number
  /** 设备总价(含税)= 合同清单合计 */
  equipmentTotal?: number
  signDate?: string
  startDate?: string
  remark?: string
  sensitiveMasked?: boolean
  assets: { assetId: number; serialNo?: string; category?: string; model?: string; assetStatus?: string; allocRent: number }[]
  reconciliation: Reconciliation
  repayment: { totalPeriods: number; elapsedPeriods: number; billedPeriods: number; progressRatio: number }
  schedule: ScheduleLine[]
  rentComposition?: RentComposition
  pnl?: Pnl
  depositLedger: { direction: string; amount: number; bizTime: string; remark?: string }[]
  changes: { changeType: string; isReverse: boolean; detail?: string; bizTime: string; operatorName?: string }[]
  /** 合同清单(《工程量清单计价表》格式;合计=设备总价) */
  boq: Boq
}

/** 保存合同清单(整表;合计回写设备总价) */
export function saveContractBoq(id: number, lines: BoqLine[]): Promise<Boq> {
  return request.put(`/rent/contracts/${id}/boq`, { lines })
}
/** 导入合同清单(《工程量清单计价表》.xls/.xlsx,整表替换) */
export function importContractBoq(id: number, file: File): Promise<BoqImportResult> {
  const fd = new FormData()
  fd.append('file', file)
  return request.post(`/rent/contracts/${id}/boq/import`, fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0,
  })
}
/** 按清单行数量一键生成设备(lineIds 为空=所有填了品类的行) */
export function generateAssetsFromBoq(id: number, lineIds: number[] = []): Promise<BoqGenerateResult> {
  return request.post(`/rent/contracts/${id}/boq/generate-assets`, { lineIds })
}
/** 导出合同清单;template=true 只下载表头模板 */
export async function exportContractBoq(id: number, contractNo: string, template = false) {
  const blob: Blob = await request.get(`/rent/contracts/${id}/boq/export`, {
    params: { template }, responseType: 'blob', timeout: 0,
  })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `工程量清单计价表-${contractNo}${template ? '-模板' : ''}.xlsx`
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60000)
}

export function fetchContracts(params: Record<string, any>): Promise<PageResult<ContractListItem>> {
  return request.get('/rent/contracts', { params })
}
export function fetchContractDetail(id: number): Promise<ContractDetail> {
  return request.get(`/rent/contracts/${id}`)
}
export function signContract(body: Record<string, any>): Promise<number> {
  return request.post('/rent/contracts', body)
}
export function voidContract(id: number, body: Record<string, any>): Promise<void> {
  return request.post(`/rent/contracts/${id}/void`, body)
}
export function renewContract(id: number, body: Record<string, any>): Promise<void> {
  return request.post(`/rent/contracts/${id}/renew`, body)
}
export function changeContract(id: number, body: Record<string, any>): Promise<void> {
  return request.post(`/rent/contracts/${id}/change`, body)
}

/** 编辑合同要素:草稿直接改;生效合同保留已出单期次、其余重排、押金差额补收/退回(财务/老板) */
export function editContract(id: number, body: Record<string, any>): Promise<void> {
  return request.put(`/rent/contracts/${id}`, body)
}

/** 合同附件:压缩文件(zip/rar/7z),单个 1GB */
export const CONTRACT_ATTACHMENT_EXTS = ['zip', 'rar', '7z']
export const CONTRACT_ATTACHMENT_MAX_MB = 1024

export interface ContractFile {
  id: number
  fileName: string
  size: number
  uploaderName?: string
  createTime?: string
}
export function fetchContractFiles(id: number): Promise<ContractFile[]> {
  return request.get('/rent/files', { params: { bizType: 'contract', bizId: id } })
}
export function uploadContractFile(id: number, file: File, onProgress?: (p: number) => void): Promise<ContractFile> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('bizType', 'contract')
  fd.append('bizId', String(id))
  return request.post('/rent/files', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0,
    onUploadProgress: (e) => { if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100)) },
  })
}
