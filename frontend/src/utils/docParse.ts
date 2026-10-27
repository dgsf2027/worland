/**
 * 合同 / 进项发票 / 银行回单 的文字 → 字段(规则解析，不依赖大模型)。
 * 输入文字来自 PDF 文字层(准确)或本机 OCR(有噪声)。针对 OCR 常见错误做了容错：
 * 金额小数点丢失/变成逗号、「¥」被读成「于/对/#」、中文名称里夹空格、l/1 混淆等，
 * 并用「各期金额之和 = 合同总价」「不含税 + 税额 = 价税合计」「中文大写金额」互相校验。
 * 解析结果只用于预填，用户核对后才保存。
 */

export interface ParsedContract {
  contractNo: string | null
  supplierName: string | null
  mainContractNo: string | null
  signedDate: string | null
  totalAmount: number | null
  taxRate: number | null
  terms: { stageName: string; ratio: number | null; amount: number | null; conditionText: string | null }[]
}
export interface ParsedInvoice {
  invoiceNo: string | null
  invoiceType: string | null
  issueDate: string | null
  sellerName: string | null
  buyerName: string | null
  amountExTax: number | null
  taxAmount: number | null
  totalAmount: number | null
  taxRate: number | null
}
export interface ParsedReceipt {
  amount: number | null
  paidDate: string | null
  bankSerial: string | null
  payerName: string | null
  payeeName: string | null
  remark: string | null
}

const round2 = (v: number) => Math.round(v * 100) / 100

/** 全角转半角、去掉汉字之间的空格(OCR/PDF 常把词拆开)、修正「小数点后空格」 */
export function normalizeText(raw: string): string {
  let t = (raw || '').normalize('NFKC').replace(/\r/g, '')
  t = t.replace(/(?<=[一-鿿])[ \t]+(?=[一-鿿])/g, '')
  t = t.replace(/(\d)\s*\.\s+(\d)/g, '$1.$2')
  return t
}

// ---------------- 数字 ----------------

/** 一个数字串的可能取值：丢了小数点的 OCR 结果(18000000)既可能是 18000000 也可能是 180000.00 */
function amountCandidates(token: string): number[] {
  const s = token.replace(/[,，\s]/g, '')
  const dots = (s.match(/\./g) || []).length
  const out: number[] = []
  if (dots === 1) {
    const v = parseFloat(s)
    if (isFinite(v)) out.push(v)
  } else if (dots > 1) {
    // 200.396.22：最后一段是 2 位小数，前面的点是千分位
    const last = s.lastIndexOf('.')
    const dec = s.slice(last + 1)
    const head = s.slice(0, last).replace(/\./g, '')
    if (dec.length <= 2) out.push(parseFloat(head + '.' + dec))
    out.push(parseFloat(s.replace(/\./g, '')))
  } else {
    const v = parseFloat(s)
    if (isFinite(v)) {
      out.push(v)
      if (s.length >= 5) out.push(round2(v / 100))
    }
  }
  return out.filter((v) => isFinite(v))
}

const NUM = String.raw`\d[\d,.\s]{1,}\d`
const CUR = String.raw`[¥￥于对#*]?`

// ---------------- 中文大写金额 ----------------

const CN_DIGIT: Record<string, number> = {
  零: 0, 〇: 0, 壹: 1, 一: 1, 吉: 1, 喜: 1, 壶: 1, 贰: 2, 二: 2, 两: 2, 叁: 3, 参: 3, 三: 3,
  肆: 4, 四: 4, 伍: 5, 五: 5, 陆: 6, 六: 6, 柒: 7, 七: 7, 染: 7, 捌: 8, 八: 8, 玖: 9, 九: 9, 玫: 9,
}
const CN_UNIT: Record<string, number> = { 拾: 10, 十: 10, 佰: 100, 百: 100, 仟: 1000, 千: 1000, 什: 1000 }

/** 「壹佰贰拾万元捌角」→ 1200000.8；无法解析返回 null(OCR 错字多时宁可不给) */
export function chineseAmountToNumber(s: string): number | null {
  const str = s.replace(/[整正\s]/g, '')
  const m = str.match(/^(.*?)[元圆](.*)$/)
  const intPart = m ? m[1] : str.replace(/[角分].*$/, '')
  const frac = m ? m[2] : ''
  let total = 0
  let section = 0
  let num = 0
  for (const ch of intPart) {
    if (ch in CN_DIGIT) num = CN_DIGIT[ch]
    else if (ch in CN_UNIT) { section += (num || 1) * CN_UNIT[ch]; num = 0 }
    else if (ch === '万') { total += (section + num) * 10000; section = 0; num = 0 }
    else if (ch === '亿') { total += (section + num) * 100000000; section = 0; num = 0 }
    else return null
  }
  total += section + num
  let dec = 0
  const j = frac.match(/([零〇壹一贰二叁参三肆四伍五陆六柒七捌八玖九])角/)
  if (j) dec += CN_DIGIT[j[1]] / 10
  const f = frac.match(/([零〇壹一贰二叁参三肆四伍五陆六柒七捌八玖九])分/)
  if (f) dec += CN_DIGIT[f[1]] / 100
  const v = total + dec
  return v > 0 ? round2(v) : null
}

// ---------------- 日期 ----------------

function pad(n: string | number) { return String(n).padStart(2, '0') }
function toIso(y: string, m: string, d: string): string | null {
  const yy = +y, mm = +m, dd = +d
  if (yy < 2000 || yy > 2100 || mm < 1 || mm > 12 || dd < 1 || dd > 31) return null
  return `${yy}-${pad(mm)}-${pad(dd)}`
}
const DATE_RE = String.raw`(\d{4})\s*[年\-./]\s*(\d{1,2})\s*[月\-./]\s*(\d{1,2})`

function findDate(t: string, labels: string): string | null {
  const m = t.match(new RegExp(`(?:${labels})\\s*[:;,]?\\s*${DATE_RE}`))
  return m ? toIso(m[1], m[2], m[3]) : null
}
function allDates(t: string): string[] {
  const out: string[] = []
  for (const m of t.matchAll(new RegExp(DATE_RE, 'g'))) {
    const d = toIso(m[1], m[2], m[3])
    if (d) out.push(d)
  }
  return out
}

// ---------------- 名称 ----------------

const ORG = String.raw`[一-龥A-Za-z0-9()]{3,40}?(?:有限责任公司|股份有限公司|有限公司|公司|商行|工厂|厂|中心|企业|集团|事务所)`

// ---------------- 合同 ----------------

/** 合同编号第一段形如 abcl / abc1：字母 + 末位数字被读成 l/I 时还原为 1 */
function fixContractNo(no: string): string {
  const parts = no.split('-')
  if (parts.length >= 2 && /^[A-Za-z]{2,}[lI]$/.test(parts[0])) parts[0] = parts[0].slice(0, -1) + '1'
  return parts.join('-').replace(/-+$/, '')
}

const isMultipleOf5pct = (r: number) => r > 0.02 && r <= 1.001 && Math.abs(r * 20 - Math.round(r * 20)) < 0.004

/** 付款表区域 + 「预付款(¥xxx)」条款里的所有大数字(每个数字给出若干种可能取值)，作为推断总价/各期金额的证据 */
function termTokens(t: string): { cands: number[]; fromClause: boolean }[] {
  const lines = t.split('\n')
  let start = lines.findIndex((l) => /付款方式|付款条款|付款进度|付款节点/.test(l))
  if (start < 0) start = 0
  let end = lines.findIndex((l, i) => i > start && /逾期付款|违约金/.test(l))
  if (end < 0) end = Math.min(lines.length, start + 30)
  const out: { cands: number[]; fromClause: boolean }[] = []
  for (const line of lines.slice(start + 1, end)) {
    const toks = [...line.matchAll(/\d[\d,.\s]{4,}\d/g)]
    if (toks.length) out.push({ cands: amountCandidates(toks[0][0]), fromClause: false })
  }
  // 条款里的预付款放最后：表格里已读到同一笔就去重，没读到才补成第一期
  const clause = t.match(/预付款[^\d\n]{0,12}(\d[\d,.\s]{4,}\d)/)
  if (clause) out.push({ cands: amountCandidates(clause[1]), fromClause: true })
  return out
}

/**
 * 合同总价：数字(OCR 可能丢掉开头的 1)、中文大写、各期金额之和 三方印证。
 * 候选总价里选「让最多的付款节点金额恰好是其 5% 整数倍」的那个；没有证据时取数字原值。
 */
function pickTotal(t: string): number | null {
  const lineRe = /(?:合计实收|合同总价|合同总金额|合同金额|合计金额|总金额|价税合计)[^\n]*/
  const line = t.match(lineRe)
  const cands: number[] = []
  if (line) {
    const body = line[0].replace(/^(?:合计实收|合同总价|合同总金额|合同金额|合计金额|总金额|价税合计)/, '')
    const m = body.match(/^[:;,\s]*[#¥￥于对*]?\s*(,?\s*\d[\d,.\s]*\d)/)
    if (m) {
      const raw = m[1]
      const lostLead = raw.trim().startsWith(',')
      const base = amountCandidates(raw.replace(/^[,\s]+/, ''))
      cands.push(...base)
      // 开头的数字被 OCR 吞掉(「#, 335, 974.8」)：补一位 1~9 试试，由后面的证据筛选
      if (lostLead) for (const v of base) for (let d = 1; d <= 9; d++) cands.push(parseFloat(`${d}${String(v)}`))
    }
  }
  const cn = t.match(/人民币\s*([零〇壹一贰二叁参三肆四伍五陆六柒七捌八玖拾十佰百仟千什万亿圆元角分整正吉喜壶染玫查律拓]{4,})/)
  const capital = cn ? chineseAmountToNumber(cn[1]) : null
  if (capital != null) cands.push(capital)
  if (!cands.length) return null
  const evidence = termTokens(t)
  let best = cands[0]
  let bestScore = -1
  for (const c of cands) {
    const score = evidence.filter((e) => e.cands.some((v) => isMultipleOf5pct(v / c))).length
      + (capital != null && Math.abs(capital - c) < 0.011 ? 0.5 : 0)
    if (score > bestScore + 1e-9) { best = c; bestScore = score }
  }
  return best
}

/** 付款表：各期金额 = 总价的 5% 整数倍；「预付款(¥xxx)」条款里的金额作为第一期的证据 */
function pickTerms(t: string, total: number | null): ParsedContract['terms'] {
  const amounts: number[] = []
  for (const tok of termTokens(t)) {
    let hit: number | undefined
    if (total) hit = tok.cands.find((v) => isMultipleOf5pct(v / total))
    else hit = tok.cands.find((v) => /\.\d{2}$/.test(v.toFixed(2)) && v > 100)
    if (hit == null) continue
    hit = round2(hit)
    if (tok.fromClause) {
      // 条款里的预付款 = 第一期；表格里若已读到(同比例)就不重复
      if (!amounts.some((a) => Math.abs(a - hit!) < 1)) amounts.unshift(hit)
    } else amounts.push(hit)
  }
  const hasPrepay = /预付款/.test(t)
  const fixed = total ? snapTerms(amounts, total) : amounts
  return fixed.map((a, i) => ({
    stageName: i === 0 && hasPrepay ? '预付款' : `第${i + 1}期款`,
    ratio: total ? Math.round((a / total) * 10000) / 10000 : null,
    amount: a,
    conditionText: null,
  }))
}

/**
 * 付款表 OCR 常见两类错误：某一期金额读错一位、某一期整行漏读。合同里各期通常是总价的 5% 的整数倍且合计 100%，
 * 所以：先把每期金额吸附到「总价 × 5% 倍数」；若合计不足 100%，剩余部分按最后一期的比例补行(整除时)或补成一期。
 * 只有补全后合计恰为 100% 才改写，否则原样返回(宁可少改)。
 */
function snapTerms(amounts: number[], total: number): number[] {
  if (!amounts.length) return amounts
  const ratios = amounts.map((a) => Math.round((a / total) * 20) / 20)
  let sum = ratios.reduce((s, r) => s + r, 0)
  const rest = Math.round((1 - sum) * 20) / 20
  if (rest >= 0.05 - 1e-9) {
    const base = ratios[ratios.length - 1]
    const n = base > 0 && Math.abs(rest / base - Math.round(rest / base)) < 1e-6 ? Math.round(rest / base) : 1
    for (let i = 0; i < n; i++) ratios.push(n === 1 ? rest : base)
    sum = ratios.reduce((s, r) => s + r, 0)
  }
  if (Math.abs(sum - 1) > 1e-6) return amounts
  const out = ratios.map((r) => round2(total * r))
  const diff = round2(total - out.reduce((s, v) => s + v, 0))
  out[out.length - 1] = round2(out[out.length - 1] + diff)
  return out
}

export function parseContract(raw: string): ParsedContract {
  const t = normalizeText(raw)
  const no = t.match(/合同\s*(?:编号|编码|号)\s*[:;,.\s]*([A-Za-z0-9][A-Za-z0-9\-_/]{4,40})/)
  const supplier = t.match(new RegExp(String.raw`(?:供应商|卖方|销售方|供方|出卖人|乙方)\s*[:;,]\s*(${ORG})`))
  const signed = findDate(t, '签订日期|签订时间|签约日期|签署日期|合同签订日期')
  const rate = t.match(/(\d{1,2}(?:\.\d+)?)\s*%\s*(?:的)?\s*增值税/) || t.match(/税率\s*[:;,]?\s*(\d{1,2}(?:\.\d+)?)\s*%/)
  let total = pickTotal(t)
  let terms = pickTerms(t, total)
  if (total == null && terms.length) {
    total = round2(terms.reduce((s, x) => s + (x.amount || 0), 0))
    terms = pickTerms(t, total)
  }
  const main = t.match(/(?:主合同|客户合同|关联合同)(?:编号|号)?\s*[:;,]?\s*([A-Za-z0-9][A-Za-z0-9\-_/]{4,40})/)
  return {
    contractNo: no ? fixContractNo(no[1]) : null,
    supplierName: supplier ? supplier[1] : null,
    mainContractNo: main ? main[1] : null,
    signedDate: signed,
    totalAmount: total,
    taxRate: rate ? Math.round(parseFloat(rate[1]) * 100) / 10000 : null,
    terms,
  }
}

// ---------------- 发票 ----------------

const TAX_RATES = [0.13, 0.09, 0.06, 0.05, 0.03, 0.01]

export function parseInvoice(raw: string): ParsedInvoice {
  const t = normalizeText(raw)
  const no = t.match(/发票号码\s*[:;,]?\s*(\d[\d\s]{6,28}\d)/)
  let invoiceNo = no ? no[1].replace(/\s/g, '') : null
  if (!invoiceNo || ![8, 20].includes(invoiceNo.length)) {
    const any = t.match(/(?<!\d)\d{20}(?!\d)/)
    invoiceNo = any ? any[0] : invoiceNo
  }
  const issue = t.match(new RegExp(String.raw`开\S{0,2}日期\s*[:;,]?\s*${DATE_RE}`))
  const names = [...t.matchAll(new RegExp(String.raw`名称\s*[:;,]\s*(${ORG})`, 'g'))].map((m) => m[1])
  const type = /专用发票/.test(t) ? '专票' : /普通发票/.test(t) ? '普通' : null

  const tm = t.match(new RegExp(String.raw`[(（]?小写[)）]?\s*${CUR}\s*(${NUM})`))
  let total: number | null = null
  const cn = t.match(/大写[)）]?\s*([零〇壹一贰二叁参三肆四伍五陆六柒七捌八玖拾十佰百仟千什万亿圆元角分整正吉喜壶染玫]{3,})/)
  const capital = cn ? chineseAmountToNumber(cn[1]) : null
  if (tm) {
    const cands = amountCandidates(tm[1])
    total = (capital != null ? cands.find((v) => Math.abs(v - capital) < 0.011) : undefined) ?? cands[0] ?? null
  } else total = capital

  // 不含税 + 税额 = 价税合计：在文中所有「两位小数」金额里找满足税率关系的一项
  let ex: number | null = null
  let tax: number | null = null
  let rate: number | null = null
  if (total != null) {
    const nums = new Set<number>()
    for (const m of t.matchAll(/\d[\d,]*\.\d{2}(?!\d)/g)) nums.add(round2(parseFloat(m[0].replace(/,/g, ''))))
    for (const a of nums) {
      if (Math.abs(a - total) < 0.011 || a <= 0) continue
      const b = round2(total - a)
      if (b <= 0 || b >= a) continue
      const r = TAX_RATES.find((x) => Math.abs(b / a - x) < 0.0015)
      if (r != null) { ex = a; tax = b; rate = r; break }
    }
    if (ex == null) {
      const pct = t.match(/(\d{1,2})\s*%/)
      const r = pct ? parseInt(pct[1], 10) / 100 : null
      if (r != null && TAX_RATES.includes(r)) {
        ex = round2(total / (1 + r)); tax = round2(total - ex); rate = r
      }
    }
  }
  return {
    invoiceNo,
    invoiceType: type,
    issueDate: issue ? toIso(issue[1], issue[2], issue[3]) : null,
    buyerName: names[0] ?? null,
    sellerName: names[1] ?? null,
    amountExTax: ex,
    taxAmount: tax,
    totalAmount: total,
    taxRate: rate,
  }
}

// ---------------- 银行回单 ----------------

export function parseReceipt(raw: string): ParsedReceipt {
  const t = normalizeText(raw)
  const am = t.match(new RegExp(String.raw`(?:金额|小写)\s*[(（]?小写[)）]?\s*[:;,]?\s*${CUR}\s*(${NUM})`))
    || t.match(new RegExp(String.raw`小写\s*[)）]?\s*[:;,]?\s*${CUR}\s*(${NUM})`))
  // 大写金额前常夹 OCR 噪声(「。 |八民币」)：允许最多 8 个非汉字/数字字符，再跳过「x民币」
  const cn = t.match(/大写[)）]?[^一-龥\d]{0,8}(?:\S{0,2}民币)?\s*([零〇壹一贰二叁参三肆四伍五陆六柒七捌八玖拾十佰百仟千什万亿圆元角分整正吉喜壶染玫]{2,})/)
  const capital = cn ? chineseAmountToNumber(cn[1]) : null
  let amount: number | null = null
  if (am) {
    const cands = amountCandidates(am[1])
    amount = (capital != null ? cands.find((v) => Math.abs(v - capital) < 0.011) : undefined) ?? cands[0] ?? null
  } else amount = capital

  const serial = t.match(/(?:交易流水号|流水号|交易序号|业务编号|回单编号)\s*[:;,]?\s*([0-9A-Za-z][0-9A-Za-z-]{5,40})/)
    || t.match(/电子回单号码\s*[:;,]?\s*([0-9A-Za-z][0-9A-Za-z-]{5,40})/)
  const date = findDate(t, '交易日期|记账日期|入账日期|转账日期|交易时间|付款日期|日期') || allDates(t)[0] || null
  const orgs = [...t.matchAll(new RegExp(`(${ORG})`, 'g'))].map((m) => m[1])
  return {
    amount,
    paidDate: date,
    bankSerial: serial ? serial[1] : null,
    payerName: orgs[0] ?? null,
    payeeName: orgs[1] ?? null,
    remark: null, // 附言/摘要在回单里版面杂、OCR 噪声大，不自动填
  }
}

export type DocKind = 'contract' | 'receipt' | 'invoice'
export function parseByKind(kind: DocKind, text: string): any {
  if (kind === 'contract') return parseContract(text)
  if (kind === 'invoice') return parseInvoice(text)
  return parseReceipt(text)
}

/** 解析结果里一共抓到了几项关键信息(用于提示「识别到 N 项」或「没读出来」) */
export function countFilled(kind: DocKind, d: any): number {
  const keys: Record<DocKind, string[]> = {
    contract: ['contractNo', 'supplierName', 'signedDate', 'totalAmount', 'taxRate'],
    invoice: ['invoiceNo', 'issueDate', 'sellerName', 'amountExTax', 'taxAmount', 'totalAmount'],
    receipt: ['amount', 'paidDate', 'bankSerial', 'payeeName'],
  }
  let n = keys[kind].filter((k) => d?.[k] != null).length
  if (kind === 'contract' && d?.terms?.length) n++
  return n
}
