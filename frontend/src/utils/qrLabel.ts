import QRCode from 'qrcode'
import { scanUrl, type CompanyInfo, type InvItem } from '@/api/inventory'

/** 资产二维码标签:企业信息 + 名称规格编号 + 二维码(扫码打开 /scan/:token 手机页) */

export function qrDataUrl(token: string, width = 360): Promise<string> {
  return QRCode.toDataURL(scanUrl(token), { margin: 1, width, errorCorrectionLevel: 'M' })
}

function esc(s?: string | null): string {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] as string))
}

/**
 * 打印标签:每个资产打印 copies 张(每套播种墙、每组货架各贴一张)。
 * 须在点击事件里同步调用(先开窗口再异步生成二维码),否则会被浏览器拦截弹窗。
 */
export async function printQrLabels(items: InvItem[], company: CompanyInfo, copies: number): Promise<boolean> {
  const win = window.open('', '_blank')
  if (!win) return false
  win.document.write('<p style="font-family:sans-serif">正在生成标签…</p>')
  const labels: string[] = []
  for (const it of items) {
    const img = await qrDataUrl(it.qrToken)
    const one = `
      <div class="label">
        <div class="co">${esc(company.companyName)}</div>
        <div class="body">
          <img src="${img}" alt="" />
          <div class="info">
            <div class="name">${esc(it.name)}</div>
            ${it.spec ? `<div>规格：${esc(it.spec)}</div>` : ''}
            <div>编号：${esc(it.code)}</div>
            ${company.phone ? `<div>电话：${esc(company.phone)}</div>` : ''}
          </div>
        </div>
        ${company.notice ? `<div class="notice">${esc(company.notice)}</div>` : ''}
        <div class="scan">扫码查询 · 出库 · 归还</div>
      </div>`
    for (let i = 0; i < Math.max(1, copies); i++) labels.push(one)
  }
  win.document.open()
  win.document.write(`<!doctype html><html><head><meta charset="utf-8"><title>资产二维码标签</title>
    <style>
      @page { margin: 8mm; }
      body { margin: 0; font-family: "PingFang SC","Microsoft YaHei",sans-serif; color: #111; }
      .grid { display: flex; flex-wrap: wrap; gap: 4mm; }
      .label { width: 90mm; border: 1px solid #333; border-radius: 2mm; padding: 3mm; box-sizing: border-box; page-break-inside: avoid; }
      .co { font-weight: 700; font-size: 14px; border-bottom: 1px solid #333; padding-bottom: 1.5mm; margin-bottom: 2mm; }
      .body { display: flex; gap: 3mm; align-items: center; }
      .body img { width: 30mm; height: 30mm; }
      .info { font-size: 11px; line-height: 1.6; word-break: break-all; }
      .name { font-size: 14px; font-weight: 700; }
      .notice { font-size: 10px; margin-top: 1.5mm; }
      .scan { font-size: 10px; color: #555; margin-top: 1mm; }
    </style></head><body><div class="grid">${labels.join('')}</div>
    <script>window.onload = function () { setTimeout(function () { window.print() }, 200) }<\/script>
    </body></html>`)
  win.document.close()
  return true
}
