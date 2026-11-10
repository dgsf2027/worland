import workerUrl from 'tesseract.js/dist/worker.min.js?url'
import coreUrl from 'tesseract.js-core/tesseract-core-lstm.wasm.js?url'
import coreSimdUrl from 'tesseract.js-core/tesseract-core-simd-lstm.wasm.js?url'
import { supportsSimd } from './ocr'
import { itemsToLines, loadPdfjs } from './docExtract'
import { licenseFieldCount, parseBusinessLicense, type LicenseFields } from './businessLicenseParse'
import { LICENSE_FIELDS, type LicenseField } from './businessLicenseParse'
import { fetchRecognizeStatus, reviewBusinessLicense } from '@/api/purchaseContract'

export const LICENSE_ACCEPT = '.pdf,.jpg,.jpeg,.png,.webp'
export interface LicenseResult { fields: LicenseFields; text: string; source: 'pdf' | 'ocr' | 'review'; confidence?: number; note?: string }
const abortError = () => new DOMException('已取消识别', 'AbortError')
const checkAbort = (signal?: AbortSignal) => { if (signal?.aborted) throw abortError() }

export function validateLicenseFile(file: File): void {
  if (!/\.(pdf|jpe?g|png|webp)$/i.test(file.name)) throw new Error('请上传 PDF、JPG、PNG 或 WebP 营业执照')
  if (!file.size || file.size > 20 * 1024 * 1024) throw new Error('营业执照文件不能为空，且不能超过 20MB')
}

function rotate(source: HTMLCanvasElement, degrees: number, maxSide: number): HTMLCanvasElement {
  const scale = Math.min(1, maxSide / Math.max(source.width, source.height))
  const w = Math.round(source.width * scale), h = Math.round(source.height * scale)
  const out = document.createElement('canvas')
  out.width = degrees % 180 ? h : w
  out.height = degrees % 180 ? w : h
  const ctx = out.getContext('2d')!
  ctx.fillStyle = '#fff'
  ctx.fillRect(0, 0, out.width, out.height)
  ctx.translate(out.width / 2, out.height / 2)
  ctx.rotate(degrees * Math.PI / 180)
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(source, -w / 2, -h / 2, w, h)
  return out
}

function orientationScore(text: string): number {
  const compact = text.replace(/\s/g, '')
  return (compact.match(/名称|法定代表人|注册资本|成立日期|经营范围|住所|统一社会信用代码/g) || []).length
}

/** 去掉花边、国徽和二维码；标签不足时保留整页，避免裁掉字段。 */
async function reviewImage(canvas: HTMLCanvasElement, paragraphs: { text: string; bbox: { x0: number; y0: number; x1: number; y1: number } }[]): Promise<File> {
  const areas = paragraphs.filter((p) => /名称|法定代表人|注册资本|成立日期|经营范围|住所/.test(p.text.replace(/\s/g, '')))
  const enough = orientationScore(areas.map((p) => p.text).join('\n')) >= 4
  const x = enough ? Math.max(0, Math.min(...areas.map((p) => p.bbox.x0)) - 60) : 0
  const y = enough ? Math.max(0, Math.min(...areas.map((p) => p.bbox.y0)) - 60) : 0
  const right = enough ? Math.min(canvas.width, Math.max(...areas.map((p) => p.bbox.x1)) + 80) : canvas.width
  // 留足下方空间，经营范围的续行可能被 OCR 拆成另一个段落。
  const bottom = enough ? Math.min(canvas.height, Math.max(canvas.height * 0.85, Math.max(...areas.map((p) => p.bbox.y1)) + 100)) : canvas.height
  const crop = document.createElement('canvas')
  const scale = Math.min(1, 2200 / Math.max(right - x, bottom - y))
  crop.width = Math.round((right - x) * scale)
  crop.height = Math.round((bottom - y) * scale)
  crop.getContext('2d')!.drawImage(canvas, x, y, right - x, bottom - y, 0, 0, crop.width, crop.height)
  try {
    const blob = await new Promise<Blob>((resolve, reject) => crop.toBlob((b) => b ? resolve(b) : reject(new Error('图片处理失败')), 'image/jpeg', 0.9))
    return new File([blob], 'license-fields.jpg', { type: 'image/jpeg' })
  } finally { crop.width = crop.height = 0 }
}

/** 本地 best OCR 优先；低置信度时，配置了视觉模型才做一次紧凑复核。 */
export async function recognizeBusinessLicense(
  file: File, onProgress?: (message: string) => void, signal?: AbortSignal, allowReview = true,
): Promise<LicenseResult> {
  validateLicenseFile(file)
  checkAbort(signal)
  onProgress?.('正在读取营业执照…')
  let canvas = document.createElement('canvas')
  if (/\.pdf$/i.test(file.name)) {
    const pdfjs = await loadPdfjs()
    checkAbort(signal)
    const task = pdfjs.getDocument({ data: new Uint8Array(await file.arrayBuffer()) })
    const cancelPdf = () => { void task.destroy() }
    signal?.addEventListener('abort', cancelPdf, { once: true })
    try {
      const doc = await task.promise
      // 一份执照对应一家公司，避免把多家公司或正副本的字段混合。
      if (doc.numPages !== 1) throw new Error('请上传单页营业执照 PDF，多页文件请先拆分后选择对应页面')
      const page = await doc.getPage(1)
      const text = itemsToLines((await page.getTextContent()).items)
      const fields = parseBusinessLicense(text)
      if (licenseFieldCount(fields) === 6) return { fields, text, source: 'pdf' }
      checkAbort(signal)
      const base = page.getViewport({ scale: 1 })
      const viewport = page.getViewport({ scale: 3600 / Math.max(base.width, base.height) })
      canvas.width = Math.ceil(viewport.width)
      canvas.height = Math.ceil(viewport.height)
      await page.render({ canvasContext: canvas.getContext('2d')!, viewport }).promise
    } catch (e: any) {
      checkAbort(signal)
      if (e.name === 'PasswordException') throw new Error('PDF 有密码保护，请上传未加密的营业执照')
      throw e
    } finally {
      signal?.removeEventListener('abort', cancelPdf)
      await task.destroy()
    }
  } else {
    const bitmap = await createImageBitmap(file)
    try {
      const scale = Math.min(3, 3600 / Math.max(bitmap.width, bitmap.height))
      canvas.width = Math.round(bitmap.width * scale)
      canvas.height = Math.round(bitmap.height * scale)
      const ctx = canvas.getContext('2d')!
      ctx.fillStyle = '#fff'
      ctx.fillRect(0, 0, canvas.width, canvas.height)
      ctx.imageSmoothingQuality = 'high'
      ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    } finally { bitmap.close() }
  }
  checkAbort(signal)
  onProgress?.('正在加载高精度中文识别，首次使用需稍候…')
  const { createWorker, PSM } = await import('tesseract.js')
  const abs = (url: string) => new URL(url, location.href).href
  let worker: Awaited<ReturnType<typeof createWorker>> | undefined
  let rejectStopped: (reason: Error) => void = () => {}
  const stopped = new Promise<never>((_, reject) => { rejectStopped = reject })
  const cancel = () => rejectStopped(abortError())
  const timeout = window.setTimeout(() => rejectStopped(new Error('识别超时，请换清晰图片重试或手工填写')), 120000)
  signal?.addEventListener('abort', cancel, { once: true })
  let closed = false
  try {
    checkAbort(signal)
    const work = async (): Promise<LicenseResult> => {
      worker = await createWorker('chi_sim', 1, {
        workerPath: abs(workerUrl), corePath: abs(supportsSimd() ? coreSimdUrl : coreUrl),
        langPath: abs(`${import.meta.env.BASE_URL}ocr/license`), gzip: false,
        cachePath: 'business-license-best-v1',
        errorHandler: (error: unknown) => rejectStopped(new Error(String(error))),
      })
      if (closed || signal?.aborted) { await worker.terminate(); throw abortError() }
      await worker.setParameters({ tessedit_pageseg_mode: PSM.AUTO })
      let direction = 0, bestScore = -1
      // 正放文档只探测一次；横置/倒置在四个方向中选择标签最完整的方向。
      for (const angle of [0, 270, 90, 180]) {
        checkAbort(signal)
        onProgress?.('正在检查执照方向…')
        const probe = rotate(canvas, angle, 1600)
        const { data } = await worker.recognize(probe)
        probe.width = probe.height = 0
        const score = orientationScore(data.text)
        if (score > bestScore) { bestScore = score; direction = angle }
        if (score >= 4) break
      }
      checkAbort(signal)
      onProgress?.('正在精细识别公司信息…')
      const upright = rotate(canvas, direction, 3600)
      try {
        const { data } = await worker.recognize(upright, { rotateAuto: true })
        const fields = parseBusinessLicense(data.text)
        const result: LicenseResult = { fields, text: data.text, source: 'ocr', confidence: data.confidence }
        const fieldLines = data.lines.filter((line) => orientationScore(line.text) > 0)
        const uncertain = licenseFieldCount(fields) < 6 || fieldLines.length === 0 || fieldLines.some((line) => line.confidence < 90)
        if (!uncertain) return result
        result.note = '部分文字识别可信度不足，请重点核对姓名、地址和经营范围。'
        if (!allowReview) return result
        const configured = await fetchRecognizeStatus().then((s) => s.configured).catch(() => false)
        checkAbort(signal)
        if (!configured) { result.note += '精细复核服务未配置。'; return result }
        onProgress?.('正在精细复核疑似错字…')
        // 本地 OCR 已结束，提前释放 worker，复核最多一张图、1024 输出 Token。
        await worker.terminate()
        worker = undefined
        try {
          const reviewed = await reviewBusinessLicense(await reviewImage(upright, data.paragraphs), JSON.stringify(fields), signal)
          checkAbort(signal)
          for (const key of Object.keys(LICENSE_FIELDS) as LicenseField[]) {
            if (reviewed[key] != null && String(reviewed[key]).trim()) fields[key] = String(reviewed[key]).trim()
            else if (key in reviewed) delete fields[key]
          }
          result.source = 'review'
          result.note = '已完成精细复核，仍请对照原件确认。'
        } catch {
          checkAbort(signal)
          result.note = '精细复核暂不可用，已保留本地识别结果，请核对或手工修正。'
        }
        return result
      } finally { upright.width = upright.height = 0 }
    }
    return await Promise.race([work(), stopped])
  } finally {
    closed = true
    window.clearTimeout(timeout)
    signal?.removeEventListener('abort', cancel)
    if (worker) await worker.terminate()
    canvas.width = canvas.height = 0
  }
}
