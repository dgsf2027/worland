import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

export interface AssetListItem {
  id: number
  serialNo: string
  category: string
  model?: string
  status: string
  marketPrice?: number
  purchasePrice?: number
  bookValue?: number
  residualValue?: number
  supplierId?: number
  supplierName?: string
  currentHolderName?: string
  sensitiveMasked?: boolean
}

export interface BomNode {
  id: number
  parentId?: number
  name: string
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
  category: string
  model?: string
  status: string
  marketPrice?: number
  purchasePrice?: number
  monthlyLaborValue?: number
  replaceHeadcount?: number
  supplierId?: number
  supplierName?: string
  currentHolderName?: string
  contractId?: number
  remark?: string
  sensitiveMasked?: boolean
  /** 集采价已与工程量清单总价联动(清单有计价行) */
  purchasePriceLinked?: boolean
  bookValue?: number
  residualValue?: number
  selfPurchasePayback?: number
  bom: BomNode[]
  costBreakdown?: { items: CostItem[]; total: number; purchasePrice?: number; gapVsPurchase?: number }
  residualBreakdown?: { items?: CostItem[]; bomResidualTotal?: number; categoryResidual?: number }
  faultArchive: FaultItem[]
  singleUnitReturn: { cumulativeRent?: number; allocRent?: number; inServiceDays?: number; idleDays?: number; returnRate?: number; idleAlert?: boolean }
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
/** 工程量清单改数量/单价(合价恢复自动计算,集采价随清单总价联动) */
export function updateBomPricing(bomId: number, body: { qty: number; unitCost: number | null }): Promise<void> {
  return request.put(`/rent/assets/bom/${bomId}/pricing`, body)
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
