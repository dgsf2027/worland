/**
 * 浏览器端本地 OCR(tesseract.js + 中文语言包)：图片/扫描页 → 文字。
 * 全部在用户浏览器里完成，图片不外传；引擎与语言包随站点静态资源发布(不访问外网)，首次使用时才加载。
 * 识别前把小图放大、转灰度并拉伸对比度——回单截图这类低分辨率图放大后准确率明显提高。
 */
import workerUrl from 'tesseract.js/dist/worker.min.js?url'
import coreSimdUrl from 'tesseract.js-core/tesseract-core-simd-lstm.wasm.js?url'
import coreUrl from 'tesseract.js-core/tesseract-core-lstm.wasm.js?url'

const abs = (u: string) => new URL(u, location.href).href

/** 浏览器是否支持 WASM SIMD(决定用哪个核心文件) */
function supportsSimd(): boolean {
  try {
    return WebAssembly.validate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11]))
  } catch {
    return false
  }
}

let workerPromise: Promise<any> | null = null
function getWorker(): Promise<any> {
  if (!workerPromise) {
    workerPromise = (async () => {
      const { createWorker } = await import('tesseract.js')
      const langPath = abs(`${import.meta.env.BASE_URL}ocr`)
      return createWorker('chi_sim', 1, {
        workerPath: abs(workerUrl),
        corePath: abs(supportsSimd() ? coreSimdUrl : coreUrl),
        langPath,
        gzip: false,
      })
    })().catch((e) => { workerPromise = null; throw e })
  }
  return workerPromise
}

/** 放大到长边约 2400px(最多 3 倍)、转灰度、拉伸对比度，返回可直接喂给 OCR 的 canvas */
async function preprocess(file: File): Promise<HTMLCanvasElement> {
  const bmp = await createImageBitmap(file)
  const longSide = Math.max(bmp.width, bmp.height)
  const scale = Math.min(3, Math.max(1, 2400 / longSide))
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(bmp.width * scale)
  canvas.height = Math.round(bmp.height * scale)
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(bmp, 0, 0, canvas.width, canvas.height)
  const img = ctx.getImageData(0, 0, canvas.width, canvas.height)
  const d = img.data
  let min = 255
  let max = 0
  for (let i = 0; i < d.length; i += 4) {
    const g = (d[i] * 299 + d[i + 1] * 587 + d[i + 2] * 114) / 1000
    d[i] = d[i + 1] = d[i + 2] = g
    if (g < min) min = g
    if (g > max) max = g
  }
  const range = Math.max(1, max - min)
  for (let i = 0; i < d.length; i += 4) {
    const v = ((d[i] - min) * 255) / range
    d[i] = d[i + 1] = d[i + 2] = v
  }
  ctx.putImageData(img, 0, 0)
  return canvas
}

/** 识别一张图片，返回文字；失败抛错(由调用方转成用户提示) */
export async function ocrImage(file: File): Promise<string> {
  const worker = await getWorker()
  const canvas = await preprocess(file)
  const { data } = await worker.recognize(canvas)
  return data.text || ''
}

/** 释放 OCR 引擎(用完一批后可调用以回收内存；下次使用会重新加载) */
export async function disposeOcr() {
  if (!workerPromise) return
  const p = workerPromise
  workerPromise = null
  try { (await p).terminate() } catch { /* ignore */ }
}
