import request from '@/utils/request'

/** 资金回单(10-07 纪要):登记付款/核销/退款/还款/赔偿收取前必须先上传 */
export type ProofBizType = 'payable' | 'rent_bill' | 'inv_damage' | 'supplier_payment' | 'pc_contract' | 'pc_invoice'
export const PROOF_EXTS = ['jpg', 'jpeg', 'png', 'pdf']
export const PROOF_MAX_MB = 10

export interface ProofView {
  proofId: number
  fileId: number
  fileName: string
  contentType?: string
  size?: number
  action: string
  createByName?: string
  createTime?: string
}
export interface UploadedProof { id: number; fileName: string; size: number }

export function uploadProofFile(file: File): Promise<UploadedProof> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('bizType', 'pay_proof')
  return request.post('/rent/files', fd, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 0 })
}
export function fetchProofs(bizType: ProofBizType, bizId: number): Promise<ProofView[]> {
  return request.get('/rent/pay-proofs', { params: { bizType, bizId } })
}
export function supplementProofs(bizType: ProofBizType, bizId: number, fileIds: number[]): Promise<void> {
  return request.post('/rent/pay-proofs', { bizType, bizId, fileIds })
}
