import request from '@/utils/request'
import type { PageResult } from '@/api/supplier'

/** 资产管理(仓库实物,一批同规格按数量管理) */

export const INV_CATEGORIES = ['播种墙', '货架', '阁楼', '其他']
export const INV_UNITS = ['套', '组', '件', '个']
export const CONDITION_LEVELS = ['完好', '轻微损坏', '损坏']
export const RENTAL_STATUSES = ['已预订', '出租中', '已归还', '已取消']
export const SETTLE_STATUSES = ['待收取', '已收取', '已减免']
/** 照片:手机拍照直传,单张 20MB(后端同口径) */
export const PHOTO_EXTS = ['jpg', 'jpeg', 'png', 'webp', 'gif', 'heic', 'heif']
export const PHOTO_MAX_MB = 20
export const OPERATE_ROLES = ['老板', '供应链', '业务']
export const SETTLE_ROLES = ['老板', '财务', '供应链']

export interface InvItem {
  id: number
  code: string
  name: string
  spec?: string
  category?: string
  unit: string
  location?: string
  remark?: string
  totalQty: number
  stockQty: number
  reservedQty: number
  rentedQty: number
  repairQty: number
  scrappedQty: number
  inStoreQty: number
  qrToken: string
  photoCount: number
  createByName?: string
  createTime?: string
}

export interface InvRental {
  id: number
  rentalNo: string
  itemId: number
  itemCode?: string
  itemName?: string
  itemSpec?: string
  unit?: string
  customerId?: number
  customerName: string
  contractId?: number
  contractNo?: string
  contractEndDate?: string
  installAddress?: string
  contact?: string
  phone?: string
  qty: number
  outQty: number
  returnedQty: number
  pendingOutQty: number
  onSiteQty: number
  startDate: string
  expectedReturnDate: string
  actualReturnDate?: string
  status: string
  overdueDays?: number
  compensationTotal?: number
  remark?: string
  createByName?: string
  createTime?: string
}

export interface InvDamage {
  id: number
  movementId: number
  rentalId?: number
  rentalNo?: string
  customerName?: string
  itemId: number
  itemCode?: string
  itemName?: string
  partName: string
  damagedQty: number
  missingQty: number
  damagePrice: number
  missingPrice: number
  amount: number
  settleStatus: string
  remark?: string
  createTime?: string
}

export interface InvMovement {
  id: number
  itemId: number
  itemCode?: string
  itemName?: string
  rentalId?: number
  rentalNo?: string
  customerName?: string
  type: string
  qty: number
  goodQty: number
  repairQty: number
  scrapQty: number
  accessories?: string
  conditionLevel?: string
  conditionDesc?: string
  compensationTotal?: number
  operatorName?: string
  opTime: string
  remark?: string
  photoCount: number
  damages: InvDamage[]
}

export interface ItemDetail {
  item: InvItem
  rentals: InvRental[]
  movements: InvMovement[]
}

export interface PriceRow {
  id?: number
  partName: string
  unit: string
  damagePrice: number
  missingPrice: number
  sortNo?: number
}

export interface CompanyInfo {
  companyName: string
  phone?: string
  address?: string
  website?: string
  notice?: string
}

export interface Reminder {
  type: string
  level: 'danger' | 'warning' | 'info'
  title: string
  detail?: string
  itemId?: number
  rentalId?: number
  date?: string
}

export interface Overview {
  itemCount: number
  totalQty: number
  inStoreQty: number
  stockQty: number
  reservedQty: number
  rentedQty: number
  repairQty: number
  scrappedQty: number
  idleQty: number
  activeRentalCount: number
  overdueRentalCount: number
  pendingCompensation: number
  reminders: Reminder[]
}

export interface MovementResult {
  movementId: number
  compensationTotal: number
  unpricedParts: string[]
  rentalStatus: string
}

export interface DamageLine {
  partName: string
  damagedQty: number
  missingQty: number
  remark?: string
}

export interface PublicScanView {
  company: CompanyInfo
  code: string
  name: string
  spec?: string
  category?: string
  unit?: string
}

export interface ScanView {
  company: CompanyInfo
  item: InvItem
  activeRentals: InvRental[]
  prices: PriceRow[]
  canOperate: boolean
}

export interface PhotoFile {
  id: number
  fileName: string
  contentType?: string
  size: number
  uploaderName?: string
  createTime?: string
}

// ---------- 资产 ----------
export function fetchInvItems(params: Record<string, any>): Promise<PageResult<InvItem>> {
  return request.get('/rent/inventory/items', { params })
}
export function fetchInvItem(id: number): Promise<ItemDetail> {
  return request.get(`/rent/inventory/items/${id}`)
}
export function createInvItem(body: Record<string, any>): Promise<number> {
  return request.post('/rent/inventory/items', body)
}
export function updateInvItem(id: number, body: Record<string, any>): Promise<void> {
  return request.put(`/rent/inventory/items/${id}`, body)
}
export function deleteInvItem(id: number): Promise<void> {
  return request.delete(`/rent/inventory/items/${id}`)
}
export function adjustInvItem(id: number, body: { type: string; qty: number; fromStatus?: string; conditionDesc?: string; remark?: string }): Promise<number> {
  return request.post(`/rent/inventory/items/${id}/adjust`, body)
}

// ---------- 出租 ----------
export function fetchInvRentals(params: Record<string, any>): Promise<PageResult<InvRental>> {
  return request.get('/rent/inventory/rentals', { params })
}
export function createInvRental(body: Record<string, any>): Promise<number> {
  return request.post('/rent/inventory/rentals', body)
}
export function updateInvRental(id: number, body: Record<string, any>): Promise<void> {
  return request.put(`/rent/inventory/rentals/${id}`, body)
}
export function cancelInvRental(id: number): Promise<void> {
  return request.post(`/rent/inventory/rentals/${id}/cancel`)
}
export function outboundRental(id: number, body: Record<string, any>): Promise<MovementResult> {
  return request.post(`/rent/inventory/rentals/${id}/out`, body)
}
export function returnRental(id: number, body: Record<string, any>): Promise<MovementResult> {
  return request.post(`/rent/inventory/rentals/${id}/return`, body)
}

// ---------- 出入库 / 损坏缺件 / 设置 ----------
export function fetchInvMovements(params: Record<string, any>): Promise<PageResult<InvMovement>> {
  return request.get('/rent/inventory/movements', { params })
}
export function fetchInvDamages(params: Record<string, any>): Promise<PageResult<InvDamage>> {
  return request.get('/rent/inventory/damages', { params })
}
export function settleInvDamage(id: number, body: { settleStatus: string; remark?: string }): Promise<void> {
  return request.put(`/rent/inventory/damages/${id}/settle`, body)
}
export function fetchCompPrices(): Promise<PriceRow[]> {
  return request.get('/rent/inventory/comp-prices')
}
export function saveCompPrices(rows: PriceRow[]): Promise<void> {
  return request.put('/rent/inventory/comp-prices', rows)
}
export function fetchInvCompany(): Promise<CompanyInfo> {
  return request.get('/rent/inventory/company')
}
export function saveInvCompany(body: CompanyInfo): Promise<void> {
  return request.put('/rent/inventory/company', body)
}
export function fetchInvOverview(): Promise<Overview> {
  return request.get('/rent/inventory/overview')
}

// ---------- 扫码 ----------
export function fetchPublicScan(token: string): Promise<PublicScanView> {
  return request.get(`/rent/inv-public/${encodeURIComponent(token)}`)
}
export function fetchScan(token: string): Promise<ScanView> {
  return request.get(`/rent/inventory/scan/${encodeURIComponent(token)}`)
}
/** 二维码内容:扫码打开的手机网页地址 */
export function scanUrl(token: string): string {
  return `${window.location.origin}/scan/${token}`
}

// ---------- 照片 ----------
export function uploadInvPhoto(bizType: 'inv_item' | 'inv_movement', bizId: number, file: File): Promise<PhotoFile> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('bizType', bizType)
  fd.append('bizId', String(bizId))
  return request.post('/rent/files', fd, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 0 })
}
export function fetchInvPhotos(bizType: 'inv_item' | 'inv_movement', bizId: number): Promise<PhotoFile[]> {
  return request.get('/rent/files', { params: { bizType, bizId } })
}
export function deleteInvPhoto(fileId: number): Promise<void> {
  return request.delete(`/rent/files/${fileId}`)
}

/** 赔偿金额(与后端同口径):损坏数×损坏单价 + 缺失数×缺失单价 */
export function damageAmount(line: DamageLine, prices: PriceRow[]): number {
  const p = prices.find((x) => x.partName === line.partName.trim())
  const v = Number(line.damagedQty || 0) * Number(p?.damagePrice || 0) + Number(line.missingQty || 0) * Number(p?.missingPrice || 0)
  return Math.round(v * 100) / 100
}

export function currentRole(): string {
  return localStorage.getItem('rent_user_role') || ''
}
