import { chineseAmountToNumber, normalizeText } from './docParse'

export const LICENSE_FIELDS = {
  companyName: '公司名称', legalPerson: '法人', registeredCapitalWan: '注册资本/万元',
  establishedDate: '成立时间', companyProfile: '公司业务范围', address: '公司地址',
} as const
export type LicenseField = keyof typeof LICENSE_FIELDS
export type LicenseFields = Partial<Record<LicenseField, string>>

// 同时列出不填入表单的标签，以免把类型、登记日期等拼入相邻字段。
const LABELS = /统一社会信用代码|社会信用代码|注册号|公司名称|企业名称|名称|法定代表人|经营者|负责人|注册资本|注册资金|成立日期|成立时间|经营范围|业务范围|营业场所|经营场所|公司地址|住所|地址|公司类型|主体类型|类型|营业期限|经营期限|登记机关|发照日期|核准日期|登记日期|国家企业信用信息公示系统网址|市场主体应当|国家市场监督管理总局监制|扫描全能王/g

export function capitalInWan(raw: string): string | undefined {
  let s = raw.normalize('NFKC').replace(/[,，\s]/g, '')
  // 本表单位为万元，不在没有汇率的情况下把外币当作人民币。
  if (/美元|港[币元]|欧元|日元|英镑|USD|HKD|EUR/i.test(s)) return
  s = s.replace(/\(?人民币\)?|RMB|CNY|[¥￥]/gi, '').replace(/[()]/g, '')
  if (!/[万亿元圆]/.test(s)) return // 没有金额单位时不猜测元/万元
  const arabic = s.match(/^(\d+(?:\.\d+)?)(亿|万)?[元圆]?(?:整)?$/)
  let value: number | null = null
  if (arabic) value = Number(arabic[1]) * (arabic[2] === '亿' ? 10000 : arabic[2] === '万' ? 1 : 0.0001)
  else if (/^[零〇一二三四五六七八九十百千万亿两壹贰叁肆伍陆柒捌玖拾佰仟元圆整正]+$/.test(s)) {
    const yuan = chineseAmountToNumber(s)
    if (yuan != null) value = yuan / 10000
  }
  if (value != null && Number.isFinite(value) && value > 0) return String(Number(value.toFixed(8)))
}

function parseDate(raw: string): string | undefined {
  const match = raw.replace(/\s/g, '').match(/^(\d{4})[年./-](\d{1,2})[月./-](\d{1,2})(?!\d)/)
  if (!match) return
  const [, y, m, d] = match
  const date = new Date(Date.UTC(+y, +m - 1, +d))
  if (date.getUTCFullYear() !== +y || date.getUTCMonth() !== +m - 1 || date.getUTCDate() !== +d) return
  return `${y}-${m.padStart(2, '0')}-${d.padStart(2, '0')}`
}

/** 只解析标签后的原文，不推测联系人、电话或业务类型。空/不确定字段不覆盖表单。 */
export function parseBusinessLicense(raw: string): LicenseFields {
  const text = normalizeText(raw).replace(/[ \t]+/g, '').replace(/\r/g, '')
  const matches = [...text.matchAll(LABELS)]
  const fields: LicenseFields = {}
  for (let i = 0; i < matches.length; i++) {
    const label = matches[i][0]
    const start = matches[i].index! + label.length
    const value = text.slice(start, matches[i + 1]?.index ?? text.length)
      .replace(/^[\s:：|丨,，“”]+/, '').replace(/[\s|丨]+$/, '').trim()
    const compact = value.replace(/\n/g, '')
    if (/^(公司名称|企业名称|名称)$/.test(label)) {
      if (compact.length >= 4 && compact.length <= 128 && /[\u4e00-\u9fff]/.test(compact)) fields.companyName = compact
    } else if (/^(法定代表人|经营者|负责人)$/.test(label)) {
      if (/^[\u4e00-\u9fff·]{2,20}$/.test(compact)) fields.legalPerson = compact
    } else if (/^注册[资][本金]$/.test(label)) {
      const capital = capitalInWan(compact)
      if (capital) fields.registeredCapitalWan = capital
    } else if (/^成立/.test(label)) {
      const date = parseDate(compact)
      if (date) fields.establishedDate = date
    } else if (/^(经营范围|业务范围)$/.test(label)) {
      const scope = value.split(/\n\s*\n/)[0].replace(/\n/g, '')
      if (scope.length >= 4) fields.companyProfile = scope
    } else if (/^(住所|地址|公司地址|营业场所|经营场所)$/.test(label)) {
      if (compact.length >= 5 && compact.length <= 255) fields.address = compact
    }
  }
  return fields
}

export function licenseFieldCount(fields: LicenseFields): number { return Object.keys(fields).length }

/** 不覆盖录入内容；用户主动选择覆盖时仍只应用非空识别值。 */
export function applyLicenseFields(form: Record<string, unknown>, fields: LicenseFields, overwrite = false): LicenseField[] {
  const applied: LicenseField[] = []
  for (const key of Object.keys(LICENSE_FIELDS) as LicenseField[]) {
    if (fields[key] && (overwrite || !String(form[key] ?? '').trim())) {
      form[key] = fields[key]
      applied.push(key)
    }
  }
  return applied
}
