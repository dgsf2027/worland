/**
 * 识别前的文件预处理(在浏览器里完成，服务器不需要 PDF 库，中文字体也用浏览器自带的)：
 * - 图片(jpg/png/webp)：原样交给识别
 * - 有文字层的 PDF(电子发票、导出的合同)：直接提取文字，比 OCR 更准
 * - 扫描版 PDF(没有文字层)：把页面渲染成图片，再走看图识别
 * - txt 文本：读出文字
 */
export interface Extracted {
  /** 要识别的图片(拍照图、扫描版 PDF 的页面图) */
  images: File[]
  /** 从 PDF / 文本文件里提取的文字 */
  text: string
  /** 给用户看的说明，如「PDF 只取前 8 页」 */
  notes: string[]
}

export const RECOGNIZE_EXTS = ['jpg', 'jpeg', 'png', 'webp', 'pdf', 'txt']
const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'webp']
const MAX_PDF_PAGES = 8
const MAX_IMAGES = 6
const MAX_TEXT = 30000
/** 一页文字少于这个数就当作扫描页(没有文字层) */
const SCANNED_PAGE_MIN_CHARS = 20

/** 本地读取/解析文件的错误(message 可直接给用户看)；请求错误由 axios 拦截器自己弹，不走这里 */
export class ExtractError extends Error {}

const extOf = (f: File) => (f.name.split('.').pop() || '').toLowerCase()

let pdfjsPromise: Promise<any> | null = null
async function loadPdfjs() {
  if (!pdfjsPromise) {
    pdfjsPromise = (async () => {
      const pdfjs: any = await import('pdfjs-dist')
      const worker: any = await import('pdfjs-dist/build/pdf.worker.min.js?url')
      pdfjs.GlobalWorkerOptions.workerSrc = worker.default
      return pdfjs
    })()
  }
  return pdfjsPromise
}

/**
 * pdf.js 的文字块顺序是 PDF 内容流顺序，电子发票常把「标签」和「值」分开存放(先写所有标签再写所有值)，
 * 直接拼接会让「发票号码」和号码隔得很远。这里按页面坐标重排：同一行(纵坐标相近)的块按横坐标从左到右拼成一行，
 * 行按从上到下排列，还原肉眼看到的版面。
 */
export function itemsToLines(items: { str?: string; transform?: number[] }[]): string {
  const rows: { y: number; parts: { x: number; s: string }[] }[] = []
  for (const it of items) {
    if (!it.str || !it.str.trim() || !it.transform) continue
    const x = it.transform[4]
    const y = it.transform[5]
    let row = rows.find((r) => Math.abs(r.y - y) < 3)
    if (!row) { row = { y, parts: [] }; rows.push(row) }
    row.parts.push({ x, s: it.str })
  }
  rows.sort((a, b) => b.y - a.y)
  return rows.map((r) => r.parts.sort((a, b) => a.x - b.x).map((p) => p.s.trim()).join(' ')).join('\n')
}

async function renderPageToFile(page: any, name: string): Promise<File> {
  const base = page.getViewport({ scale: 1 })
  // 目标长边约 1800px：够看清小字，又不会让图片过大
  const scale = Math.min(3, Math.max(1, 1800 / Math.max(base.width, base.height)))
  const viewport = page.getViewport({ scale })
  const canvas = document.createElement('canvas')
  canvas.width = Math.floor(viewport.width)
  canvas.height = Math.floor(viewport.height)
  await page.render({ canvasContext: canvas.getContext('2d')!, viewport }).promise
  const blob: Blob = await new Promise((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('页面转图片失败'))), 'image/jpeg', 0.85))
  return new File([blob], name, { type: 'image/jpeg' })
}

async function extractPdf(file: File, out: Extracted) {
  const pdfjs = await loadPdfjs()
  let doc: any
  try {
    doc = await pdfjs.getDocument({ data: new Uint8Array(await file.arrayBuffer()) }).promise
  } catch {
    throw new ExtractError(`「${file.name}」无法读取（文件损坏或有密码保护），请换一份或手工录入`)
  }
  const pages = Math.min(doc.numPages, MAX_PDF_PAGES)
  if (doc.numPages > MAX_PDF_PAGES) out.notes.push(`「${file.name}」共 ${doc.numPages} 页，只识别前 ${MAX_PDF_PAGES} 页`)
  let scannedPages = 0
  for (let p = 1; p <= pages; p++) {
    const page = await doc.getPage(p)
    const content = await page.getTextContent()
    const pageText = itemsToLines(content.items).trim()
    if (pageText.replace(/\s+/g, '').length >= SCANNED_PAGE_MIN_CHARS) {
      out.text += `\n【${file.name} 第${p}页】\n${pageText}`
    } else if (out.images.length < MAX_IMAGES) {
      out.images.push(await renderPageToFile(page, `${file.name.replace(/\.pdf$/i, '')}-p${p}.jpg`))
      scannedPages++
    }
  }
  if (scannedPages) out.notes.push(`「${file.name}」有 ${scannedPages} 页是扫描图，已转成图片识别（需要看图模型）`)
}

/** 把用户选的文件整理成「图片 + 文字」两部分；不支持的类型抛 Error(message 可直接给用户看) */
export async function extractForRecognize(files: File[]): Promise<Extracted> {
  const out: Extracted = { images: [], text: '', notes: [] }
  for (const f of files) {
    const ext = extOf(f)
    if (IMAGE_EXTS.includes(ext)) {
      out.images.push(f)
    } else if (ext === 'pdf') {
      await extractPdf(f, out)
    } else if (ext === 'txt') {
      out.text += `\n【${f.name}】\n${await f.text()}`
    } else {
      throw new ExtractError(`「${f.name}」格式不支持，可识别：jpg/png/webp 图片、PDF、txt 文本`)
    }
  }
  if (out.images.length > MAX_IMAGES) {
    out.images = out.images.slice(0, MAX_IMAGES)
    out.notes.push(`图片超过 ${MAX_IMAGES} 张，只识别前 ${MAX_IMAGES} 张`)
  }
  out.text = out.text.trim().slice(0, MAX_TEXT)
  if (!out.images.length && !out.text) throw new ExtractError('没有读到可识别的内容，请换一份清晰的文件或手工录入')
  return out
}
