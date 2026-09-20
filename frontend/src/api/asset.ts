import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface AssetListItem {
  id: number
  /** 系统内部序列号(自动生成) */
  serialNo: string
  /** 所属合同编号(来自关联合同,只读) */
  contractNo?: string
  contractId?: number
  category: string
  model?: string
  status: string
  marketPrice?: number
  purchasePrice?: number
  bookValue?: number
  residualValue?: number
  supplierId?: number
  supplierName?: string
  currentHolderCustomerId?: number
  currentHolderName?: string
  intendedCustomerId?: number
  intendedCustomerName?: string
  sensitiveMasked?: boolean
}

export interface BomNode {
  id: number
  parentId?: number
  /** 序号(与合同清单同格式) */
  seq?: number
  name: string
  model?: string
  spec?: string
  unit?: string
  qty?: number
  unitCost?: number
  subtotal?: number
  subtotalOverride?: number | null
  supplierId?: number
  supplierName?: string
  lifeYears?: number
  warrantyUntil?: string
  repairable?: boolean
  faultCount?: number
  residualRate?: number
  remark?: string
  children?: BomNode[]
}
export interface BomAttachment {
  id: number
  fileName: string
  contentType?: string
  size: number
  uploaderName?: string
  createTime?: string
}

export interface SignedUrl {
  id: number
  url: string
  expiresAt: number
  ttlSeconds: number
}

export interface CostItem { name: string; amount: number; ratio: number }
export interface PaymentTermInput {
  stageName: string
  /** 0-1 */
  ratio: number
  triggerPoint: '下单' | '入库'
  dueDays: number
}
export interface PaymentTermLine {
  id: number
  seq: number
  stageName: string
  ratio: number
  triggerPoint: '下单' | '入库'
  dueDays: number
  expectedAmount?: number
  payableId?: number
  payableAmount?: number
  payableDueDate?: string
  payableStatus?: string
}
export interface PaymentPlan {
  purchaseInId?: number
  purchaseNo?: string
  purchaseStatus?: string
  basePrice?: number
  terms: PaymentTermLine[]
  ratioTotal: number
  expectedTotal?: number
  pendingTotal?: number
  paidTotal?: number
  defaultTemplate: PaymentTermInput[]
  note?: string
}
export interface FaultItem {
  bomId: number
  name: string
  faultCount: number
  repairable: boolean
  warrantyUntil?: string
  supplierId?: number
  supplierName?: string
  warrantyDaysLeft?: number
}

/** 工程量清单附件:Word/Excel/PPT/PDF/压缩文件,单个 50MB(后端同口径校验) */
export const BOM_ATTACHMENT_EXTS = ['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'pdf', 'zip', 'rar', '7z']
export const BOM_ATTACHMENT_MAX_MB = 50

/** 按扩展名与大小校验待上传文件;不通过返回提示文案,通过返回 null。 */
export function checkUploadFile(file: File, exts: string[], maxMb: number): string | null {
  const dot = file.name.lastIndexOf('.')
  const ext = dot < 0 ? '' : file.name.slice(dot + 1).toLowerCase()
  if (!exts.includes(ext)) return `「${file.name}」格式不支持，仅支持 ${exts.join('/')}`
  if (file.size > maxMb * 1024 * 1024) return `「${file.name}」超过 ${maxMb}MB 上限`
  return null
}
export interface AssetDetail {
  id: number
  serialNo: string
  /** 所属合同编号(来自关联合同,只读) */
  contractNo?: string
  category: string
  model?: string
  status: string
  marketPrice?: number
  purchasePrice?: number
  monthlyLaborValue?: number
  replaceHeadcount?: number
  supplierId?: number
  supplierName?: string
  currentHolderCustomerId?: number
  currentHolderName?: string
  /** 意向承接客户(未签约设备预设) */
  intendedCustomerId?: number
  intendedCustomerName?: string
  contractId?: number
  remark?: string
  sensitiveMasked?: boolean
  /** 由哪一行合同清单生成(空=非清单生成) */
  boqLineId?: number
  bookValue?: number
  residualValue?: number
  selfPurchasePayback?: number
  bom: BomNode[]
  costBreakdown?: { items: CostItem[]; total: number; purchasePrice?: number; gapVsPurchase?: number }
  residualBreakdown?: { items?: CostItem[]; bomResidualTotal?: number; categoryResidual?: number }
  faultArchive: FaultItem[]
  /** 合同付款条件 + 预计付款 + 逐台应付 */
  paymentPlan: PaymentPlan
  singleUnitReturn: {
    cumulativeRent?: number; allocRent?: number; inServiceDays?: number; idleDays?: number; returnRate?: number; idleAlert?: boolean
    /** 手工覆盖的字段:allocRent/cumulativeRent/returnRate/inServiceDays/idleDays */
    manualFields: string[]
  }
  timeline: { eventType: string; bizTime: string; refDocType?: string; refDocId?: number; operatorName?: string; remark?: string }[]
}

export function fetchAssets(params: Record<string, any>): Promise<PageResult<AssetListItem>> {
  return request.get('/rent/assets', { params })
}
export function fetchAssetDetail(id: number): Promise<AssetDetail> {
  return request.get(`/rent/assets/${id}`)
}
export function createAsset(body: Record<string, any>): Promise<number> {
  return request.post('/rent/assets', body)
}
export function updateAsset(id: number, body: Record<string, any>): Promise<void> {
  return request.put(`/rent/assets/${id}`, body)
}
export function changeAssetStatus(id: number, body: Record<string, any>): Promise<void> {
  return request.post(`/rent/assets/${id}/status`, body)
}
export function addBom(id: number, body: Record<string, any>): Promise<number> {
  return request.post(`/rent/assets/${id}/bom`, body)
}
export function updateBom(bomId: number, body: Record<string, any>): Promise<void> {
  return request.put(`/rent/assets/bom/${bomId}`, body)
}
export function deleteBom(bomId: number): Promise<void> {
  return request.delete(`/rent/assets/bom/${bomId}`)
}
/** 配件 BOM 改数量/单价(合价恢复自动计算;BOM 不参与合同价) */
export function updateBomPricing(bomId: number, body: { qty: number; unitCost: number | null }): Promise<void> {
  return request.put(`/rent/assets/bom/${bomId}/pricing`, body)
}
/** 设置合同付款条件(自定义多段,合计 100%) */
export function updatePaymentTerms(id: number, terms: PaymentTermInput[]): Promise<void> {
  return request.put(`/rent/assets/${id}/payment-terms`, { terms })
}
/** 编辑器行(比例以百分数编辑) */
export interface TermRow {
  stageName: string
  ratioPct: number | null
  triggerPoint: '下单' | '入库'
  dueDays: number | null
}
/** 编辑器行 → 接口入参(比例转 0-1) */
export function toTermInputs(rows: TermRow[]): PaymentTermInput[] {
  return rows.map((r) => ({
    stageName: String(r.stageName || '').trim(),
    ratio: Math.round(Number(r.ratioPct || 0) * 1000000) / 100000000,
    triggerPoint: r.triggerPoint,
    dueDays: Number(r.dueDays || 0),
  }))
}
export function toTermRows(terms: { stageName: string; ratio: number; triggerPoint: string; dueDays?: number | null }[]): TermRow[] {
  return terms.map((t) => ({
    stageName: t.stageName,
    ratioPct: Math.round(t.ratio * 1000000) / 10000,
    triggerPoint: (t.triggerPoint === '下单' ? '下单' : '入库'),
    dueDays: t.dueDays ?? 0,
  }))
}
/** 校验编辑器行:返回错误文案或 null */
export function checkTermRows(rows: TermRow[]): string | null {
  if (!rows.length) return '至少设置一段付款条件'
  const names = new Set<string>()
  for (const r of rows) {
    const n = String(r.stageName || '').trim()
    if (!n) return '阶段名称不能为空'
    if (names.has(n)) return `阶段名称重复：${n}`
    names.add(n)
    if (!r.ratioPct || r.ratioPct <= 0) return `阶段「${n}」比例须大于 0`
  }
  const total = Math.round(rows.reduce((s, r) => s + Number(r.ratioPct || 0), 0) * 100) / 100
  if (Math.abs(total - 100) >= 0.01) return `比例合计须为 100%，当前 ${total}%`
  return null
}

/** 配件 BOM 明细导入回执 */
export interface BomImportResult {
  total: number
  imported: number
  skipped: number
  bomTotal?: number
  messages: string[]
}
/** 导入配件 BOM 明细(.xls/.xlsx,整表替换) */
export function importBom(id: number, file: File): Promise<BomImportResult> {
  const fd = new FormData()
  fd.append('file', file)
  return request.post(`/rent/assets/${id}/bom/import`, fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0,
  })
}
/** 导出配件 BOM 明细;template=true 只下载表头模板 */
export async function exportBom(id: number, fileLabel: string, template = false) {
  const blob: Blob = await request.get(`/rent/assets/${id}/bom/export`, {
    params: { template }, responseType: 'blob', timeout: 0,
  })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `配件BOM明细-${fileLabel}${template ? '-模板' : ''}.xlsx`
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60000)
}

/** 单台收益手工覆盖(某项传 null = 恢复自动计算) */
export function updateSingleUnitReturn(id: number, body: Record<string, number | null>): Promise<void> {
  return request.put(`/rent/assets/${id}/single-unit-return`, body)
}
/** 设置意向承接客户(customerId=null 清除) */
export function updateIntendedCustomer(id: number, customerId: number | null): Promise<void> {
  return request.put(`/rent/assets/${id}/intended-customer`, { customerId })
}
/** 故障档案编辑 */
export function updateBomFault(bomId: number, body: { faultCount: number; repairable: boolean; warrantyUntil: string | null; supplierId: number | null }): Promise<void> {
  return request.put(`/rent/assets/bom/${bomId}/fault`, body)
}

export function uploadBomAttachment(bomId: number, file: File): Promise<BomAttachment> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('bizType', 'asset_bom')
  fd.append('bizId', String(bomId))
  return request.post('/rent/files', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0, // 附件可达 50MB,不走全局 15s 超时
  })
}

export function fetchBomAttachments(bomId: number): Promise<BomAttachment[]> {
  return request.get('/rent/files', { params: { bizType: 'asset_bom', bizId: bomId } })
}

export function fetchFileSignedUrl(fileId: number): Promise<SignedUrl> {
  return request.get('/rent/files/' + fileId + '/signed-url')
}

// 下载走统一请求实例，携带 Bearer 凭证；不直接打开缺少鉴权头的新窗口。
export async function downloadFileBlob(fileId: number): Promise<Blob> {
  const signed = await fetchFileSignedUrl(fileId)
  const token = new URL(signed.url, window.location.origin).searchParams.get('token')
  if (!token) throw new Error('下载链接缺少签名')
  return request.get('/rent/files/download', { params: { token }, responseType: 'blob', timeout: 0 })
}
export const downloadBomAttachmentBlob = downloadFileBlob

/** 下载文件并触发浏览器保存。 */
export async function saveFile(fileId: number, fileName: string) {
  const blob = await downloadFileBlob(fileId)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60000)
}
