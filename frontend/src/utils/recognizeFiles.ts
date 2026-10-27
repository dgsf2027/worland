import { ElMessage } from 'element-plus'
import { checkUploadFile } from '@/api/asset'
import { fetchRecognizeStatus, recognizePcDoc, type RecognizeKind } from '@/api/purchaseContract'
import { ExtractError, extractForRecognize, RECOGNIZE_EXTS } from '@/utils/docExtract'
import { countFilled, parseByKind } from '@/utils/docParse'
import { ocrImage } from '@/utils/ocr'

/** 能识别的文件类型提示，给上传控件的 accept / 说明文字用 */
export const RECOGNIZE_ACCEPT = 'image/*,application/pdf,.pdf,.txt,text/plain'

/** 识别时「没读到」的字段中文名，提示用户手工补全 */
const LABELS: Record<RecognizeKind, Record<string, string>> = {
  contract: { contractNo: '合同号', supplierName: '供应商', signedDate: '签订日期', totalAmount: '含税总价', taxRate: '税率', terms: '付款节点' },
  invoice: { invoiceNo: '发票号码', issueDate: '开票日期', sellerName: '销方', amountExTax: '不含税金额', taxAmount: '税额', totalAmount: '价税合计' },
  receipt: { amount: '金额', paidDate: '日期', bankSerial: '流水号', payeeName: '收款方' },
}

let llmConfigured: Promise<boolean> | null = null
/** 服务器是否配置了大模型(配了就用它增强本地识别结果；没配也完全可用)。结果缓存，查询失败按未配置处理。 */
function isLlmConfigured(): Promise<boolean> {
  if (!llmConfigured) llmConfigured = fetchRecognizeStatus().then((s) => !!s.configured).catch(() => false)
  return llmConfigured
}

/** 大模型结果优先(非空才覆盖)，本地规则结果兜底 */
function merge(local: any, llm: any): any {
  if (!llm) return local
  const out = { ...local }
  for (const [k, v] of Object.entries(llm)) {
    if (v == null) continue
    if (Array.isArray(v) && !v.length) continue
    out[k] = v
  }
  return out
}

/**
 * 识别流程(默认完全在本机完成，不需要大模型)：
 *  1. PDF 文字层直接取文字；图片、扫描版 PDF 的页面用浏览器端 OCR 转成文字
 *  2. 规则解析文字 → 合同/发票/回单字段(金额等做交叉校验)
 *  3. 服务器配了大模型时，再让它复核一遍，非空字段优先采用；大模型失败不影响本地结果
 * 返回识别出的字段；读不出任何关键信息返回 null。识别只用于预填，用户核对后才保存。
 */
export async function recognizeFromFiles(
  kind: RecognizeKind, files: File[], onProgress?: (msg: string) => void,
): Promise<any | null> {
  for (const f of files) {
    const err = checkUploadFile(f, RECOGNIZE_EXTS, 10)
    if (err) { ElMessage.warning(err + '（可识别：图片 / PDF / txt）'); return null }
  }
  try {
    onProgress?.('正在读取文件…')
    const ex = await extractForRecognize(files)
    ex.notes.forEach((n) => ElMessage.info(n))
    const parts: string[] = [ex.text]
    for (let i = 0; i < ex.images.length; i++) {
      onProgress?.(ex.images.length > 1 ? `本机文字识别中 ${i + 1}/${ex.images.length}…` : '本机文字识别中…')
      try {
        parts.push(await ocrImage(ex.images[i]))
      } catch (e: any) {
        console.warn('[ocr] 识别失败', e)
        ElMessage.warning('本机文字识别引擎加载失败，请刷新页面重试或手工录入')
        break
      }
    }
    const text = parts.join('\n')
    const local = parseByKind(kind, text)

    let llm: any = null
    if (await isLlmConfigured()) {
      onProgress?.('大模型复核中…')
      try { llm = await recognizePcDoc(kind, ex.images, text.trim() || undefined, true) } catch { /* 回退本地结果 */ }
    }
    const result = merge(local, llm)
    if (countFilled(kind, result) === 0) {
      ElMessage.warning('没有从文件里读出关键信息（图片太模糊或不是对应的单据），请换一份更清晰的，或手工录入')
      return null
    }
    const missing = Object.entries(LABELS[kind])
      .filter(([k]) => (k === 'terms' ? !result.terms?.length : result[k] == null))
      .map(([, label]) => label)
    if (missing.length) ElMessage.info(`以下内容没读到，请手工补全：${missing.join('、')}`)
    return result
  } catch (err: any) {
    if (err instanceof ExtractError) ElMessage.warning(err.message)
    else { console.warn('[recognize]', err); ElMessage.warning('识别失败，请手工录入') }
    return null
  } finally {
    onProgress?.('')
  }
}
