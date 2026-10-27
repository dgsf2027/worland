import { describe, expect, it } from 'vitest'
import { chineseAmountToNumber, countFilled, parseContract, parseInvoice, parseReceipt } from './docParse'
import { itemsToLines } from './docExtract'

/** 全部使用虚构公司/金额。样例文字模拟真实 OCR 的典型错误，而不是干净文本。 */

describe('中文大写金额', () => {
  it('常规大写', () => {
    expect(chineseAmountToNumber('壹拾叁万元整')).toBe(130000)
    expect(chineseAmountToNumber('壹佰贰拾万圆整')).toBe(1200000)
    expect(chineseAmountToNumber('贰拾柒万圆整')).toBe(270000)
    expect(chineseAmountToNumber('壹仟贰佰叁拾肆元伍角陆分')).toBe(1234.56)
  })
  it('OCR 常见错字(喜→壹 参→叁 什→仟)', () => {
    expect(chineseAmountToNumber('喜拾参万元整')).toBe(130000)
    expect(chineseAmountToNumber('伍什元整')).toBe(5000)
  })
  it('读不懂就返回 null', () => {
    expect(chineseAmountToNumber('abc')).toBeNull()
  })
})

describe('合同识别(扫描件 OCR 噪声)', () => {
  const clean = [
    '某某智能112格口播种墙销售合同',
    '合同编号;， abcl-123XY-260101-01',
    '采购商; 某某市甲方科技有限公司〈以下简称甲方)',
    '供应商: 某某市乙方动力技术有限公司    (以下简称乙方)',
    '合计实收: ¥1, 200, 000.0 (人民币壹佰贰拾万圆整) ”本合同约定价格均已含税费等费用，',
    '1， 以上单价乙方提供_13% 增值税专用发票。',
    '3.，交货期: 签订合同并收到预付款 〈 ¥480, 000.00 ) 之日起45个自然日',
    '4，付款方式;参照协议第十条进行。',
    '| 1 | 40% | 签订合同并收到预付款 | 480000.00 | 肆拾捌万元整',
    '| 2 | 15% | 到货30天内 | 180000.00 | 壹拾捌万元整',
    '| 3 | 15% | 安装60天内 | 180000.00 | 壹拾捌万元整',
    '| 4 | 15% | 验收90天内 | 180000.00 | 壹拾捌万元整',
    '| 5 | 15% | 质保120天内 | 180000.00 | 壹拾捌万元整',
    '若甲方逾期付款，每迟延一天按货物总价款的万分之五向乙方支付违约金',
  ].join('\n')

  it('干净文本：合同号、供应商、总价、税率、5 期付款', () => {
    const r = parseContract(clean)
    expect(r.contractNo).toBe('abc1-123XY-260101-01') // 第一段末位 l → 1
    expect(r.supplierName).toBe('某某市乙方动力技术有限公司')
    expect(r.totalAmount).toBe(1200000)
    expect(r.taxRate).toBe(0.13)
    expect(r.terms.map((t) => t.amount)).toEqual([480000, 180000, 180000, 180000, 180000])
    expect(r.terms.map((t) => t.ratio)).toEqual([0.4, 0.15, 0.15, 0.15, 0.15])
    expect(r.terms[0].stageName).toBe('预付款')
  })

  it('总价开头的数字被 OCR 吞掉、小数点丢失、某一期金额错一位、整行漏读 → 仍还原出完整付款表', () => {
    const noisy = [
      '合同编号;， abcl-123XY-260101-01',
      '供应商: 某某市乙方动力技术有限公司    (以下简称乙方)',
      '合计实收: #, 200, 000. 0 人民币壹佰贰拾万圆整) ”本合同约定价格均已含税费等费用，',
      '1， 以上单价乙方提供_13% 增值税专用发票。',
      '3，交货期: 签订合同并收到预付款 〈( 圣480, 000.00 ) 之日起45个自然日',
      '4，付款方式:',
      '| | ty | s有区JBaoK内| 18000000 | 其失万军人人下了元下用开人',
      '| | | BR | 17900000 | 式失军人合欢失了元用人', // 第 3 期金额读错一位
      '| | sy | RDORR | 180.000.00 | 区的万过人全失央元开外分',
      // 第 2 期、第 5 期整行没读到
      '若甲方逾期付款，每迟延一天按货物总价款的万分之五向乙方支付违约金',
    ].join('\n')
    const r = parseContract(noisy)
    expect(r.totalAmount).toBe(1200000)
    expect(r.terms.map((t) => t.amount)).toEqual([480000, 180000, 180000, 180000, 180000])
    expect(r.terms.reduce((s, t) => s + (t.amount || 0), 0)).toBe(1200000)
  })

  it('只读到合同号和供应商时，其余字段为空而不是乱填', () => {
    const r = parseContract('合同编号: abc-12345-001\n供应商: 某某乙方科技有限公司 (以下简称乙方)')
    expect(r.contractNo).toBe('abc-12345-001')
    expect(r.supplierName).toBe('某某乙方科技有限公司')
    expect(r.totalAmount).toBeNull()
    expect(r.terms).toEqual([])
  })

  it('不是合同的文字 → 一项都读不到', () => {
    expect(countFilled('contract', parseContract('今天天气不错，我们去吃饭'))).toBe(0)
  })
})

describe('发票识别', () => {
  it('电子发票 PDF 文字层(版面还原后)：号码/日期/购销方/金额/税率/类型', () => {
    const text = [
      '电子发票（增值税专用发票） 发票号码： 12345678901234567890',
      '开票日期： 2026年01月05日',
      '购 名称： 某某市甲方科技有限公司 销 名称： 某某乙方货架有限公司',
      '项目名称 规格型号 单 位 数 量 单 价 金 额 税率/征收率 税 额',
      '*金属制品*立柱 片 100 80.00 8000.00 13% 1040.00',
      '*金属制品*横梁 根 50 17.7 849.56 13% 110.44',
      '¥8849.56 ¥1150.44',
      '价税合计（大写） 壹万圆整 （小写） ¥10000.00',
    ].join('\n')
    const r = parseInvoice(text)
    expect(r.invoiceNo).toBe('12345678901234567890')
    expect(r.invoiceType).toBe('专票')
    expect(r.issueDate).toBe('2026-01-05')
    expect(r.buyerName).toBe('某某市甲方科技有限公司')
    expect(r.sellerName).toBe('某某乙方货架有限公司')
    expect(r.totalAmount).toBe(10000)
    expect(r.amountExTax).toBe(8849.56)
    expect(r.taxAmount).toBe(1150.44)
    expect(r.taxRate).toBe(0.13)
  })

  it('发票图片 OCR：「¥」读成「于/对」、税额末位读成「]」、开票日期的「日」读成别的字', () => {
    const text = [
      '发票号码: 98765432109876543210',
      '开景日期: 2026年10月14晶',
      '四 名称: 某某市甲方科技有限公司 的 名称:某某市乙方动力技术有限公司',
      '搬运设备*立体播种墙 台 0. 20 110619. 45 22123. 89 13% 2876. 11|',
      '合 计 于22123. 89 于2876. 1]',
      '价税合计(大写) @@佐万参什佐佰玫拾玫圆参角类分 (小写) 对25000. 00',
    ].join('\n')
    const r = parseInvoice(text)
    expect(r.invoiceNo).toBe('98765432109876543210')
    expect(r.issueDate).toBe('2026-10-14')
    expect(r.buyerName).toBe('某某市甲方科技有限公司')
    expect(r.sellerName).toBe('某某市乙方动力技术有限公司')
    expect(r.totalAmount).toBe(25000)
    expect(r.amountExTax).toBe(22123.89)
    expect(r.taxAmount).toBe(2876.11) // 用 合计 - 不含税 校正被读坏的税额
    expect(r.taxRate).toBe(0.13)
  })

  it('只读到价税合计时，按税率推算不含税与税额', () => {
    const r = parseInvoice('发票号码: 12345678901234567890\n税率 13%\n价税合计（大写） 壹万壹仟叁佰圆整 （小写） ¥11300.00')
    expect(r.totalAmount).toBe(11300)
    expect(r.amountExTax).toBe(10000)
    expect(r.taxAmount).toBe(1300)
  })
})

describe('银行回单识别', () => {
  it('数字金额读不出来时用中文大写金额；取打印日期与回单号；付款方/收款方', () => {
    const text = [
      '中国工商银行 网上银行电子回单',
      '电子回单号码: 0922-1111-2222-3333',
      ' 州市某某甲方科技有限公司 州某某乙方货架有限公司',
      ' 金额(大写) 。 |八民币 壹拾叁万元整',
      '打印日期: 2026年10月16日',
    ].join('\n')
    const r = parseReceipt(text)
    expect(r.amount).toBe(130000)
    expect(r.paidDate).toBe('2026-10-16')
    expect(r.bankSerial).toBe('0922-1111-2222-3333')
    expect(r.payeeName).toContain('乙方货架有限公司')
  })

  it('有数字金额且与大写一致时取数字', () => {
    const r = parseReceipt('交易流水号: 20260101123456\n金额(小写): ¥133,567.89\n金额(大写) 壹拾叁万叁仟伍佰陆拾柒元捌角玖分\n交易日期: 2026-01-05')
    expect(r.amount).toBe(133567.89)
    expect(r.bankSerial).toBe('20260101123456')
    expect(r.paidDate).toBe('2026-01-05')
  })
})

describe('PDF 文字层按版面重排', () => {
  it('标签和值分开存放时，按坐标把同一行拼回去', () => {
    const items = [
      { str: '发票号码：', transform: [1, 0, 0, 1, 300, 700] },
      { str: '开票日期：', transform: [1, 0, 0, 1, 300, 680] },
      { str: '12345678901234567890', transform: [1, 0, 0, 1, 380, 700.5] },
      { str: '2026年01月05日', transform: [1, 0, 0, 1, 380, 680.2] },
    ]
    expect(itemsToLines(items)).toBe('发票号码： 12345678901234567890\n开票日期： 2026年01月05日')
  })
})
