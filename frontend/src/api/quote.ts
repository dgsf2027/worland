import request from '@/utils/request'

/** 报价测算(曜石 货架/播种墙测算表简版口径)。比率均为 0-1 的小数,金额单位元。 */
export const QUOTE_CATEGORIES = ['播种墙', '货架'] as const
export const QUOTE_CUSTOMER_TYPES = ['云山快仓', '其他·标准', '其他·高'] as const

export interface QuoteRequest {
  category: string
  customerType: string
  /** 客户自购价 / 市场价(含运安) */
  marketPrice: number
  // 采购成本:直接给,或 折扣基数×(1−折扣率)+运安费
  purchasePrice?: number
  discountBase?: number
  discountRate?: number
  freightInstall?: number
  // 合同口径(不填取默认)
  termMonths?: number
  prepayRatio?: number
  transferRate?: number
  targetIrr?: number
  /** 不填则按目标 IRR 反解并向上取整到整元 */
  monthlyRent?: number
  // 成本视角
  maintenancePerYear?: number
  warrantyMonths?: number
  capitalCostRate?: number
  mgmtFeeRate?: number
  // 客户视角
  customerGeneralTaxpayer?: boolean
  replaceHeadcount?: number
  laborCostMonthly?: number
  slots?: number
}

export interface QuoteLessor {
  totalCollected: number
  collectRate: number
  vat: number
  surtax: number
  incomeTax: number
  netProfit: number
  preTaxIrr: number
  afterTaxIrr: number
  meetsTarget: boolean
  paybackMonths: number
  taxFactor: number
}

export interface QuoteCustomer {
  totalPay: number
  extraPay: number
  extraRatio: number
  buyInputTax: number
  leaseInputTax: number
  afterTaxExtra: number
  afterTaxExtraRatio: number
  impliedFinanceCost: number
  replaceHeadcount?: number
  monthlySaving?: number
  monthlyNetBenefit?: number
  buyPaybackMonths?: number
  paybackPass?: boolean
  benefitPass?: boolean
  valuePricingPass?: boolean
  unitRent?: number
  buyPricePerMonth: number
  verdict: string
}

export interface QuoteCost {
  revenueExVat: number
  taxCost: number
  maintenance: number
  capitalCost: number
  mgmtFee: number
  totalCost: number
  actualProfit: number
  profitMargin: number
  avgMonthlyCost: number
  breakEvenRent: number
  safetyMargin: number
  peakCapital: number
}

export interface QuoteResponse {
  category: string
  customerType: string
  marketPrice: number
  purchasePrice: number
  termMonths: number
  targetIrr: number
  prepayment: number
  monthlyRent: number
  rentSolved: boolean
  transferPrice: number
  transferRate: number
  lessor: QuoteLessor
  customer: QuoteCustomer
  cost: QuoteCost
  notes: string[]
}

export function calcQuote(req: QuoteRequest): Promise<QuoteResponse> {
  return request.post('/rent/quote/calc', req)
}
