import request from '@/utils/request'

export interface QuoteRequest {
  marketPrice: number
  category: string
  customerType: string
  monthlyLaborValue: number
  firstPayRatio?: number
  termMonths?: number
  purchasePrice?: number
  targetIrr?: number
}

export interface QuoteResponse {
  marketPrice: number
  purchasePrice: number
  category: string
  customerType: string
  termMonths: number
  targetIrr: number
  monthlyRent: number
  monthlyRentQuick?: number
  speedCoeff?: number
  transferPrice: number
  transferRate: number
  customerTotalPay: number
  afterTaxNetProfit: number
  afterTaxIrr: number
  layer1Return: number
  layer2Return: number
  layer3Return: number
  paybackMonths: number
  paybackPass: boolean
  monthlyNetBenefit: number
  benefitPass: boolean
  valuePricingPass: boolean
  taxVat: number
  taxSurtax: number
  taxIncome: number
  firstPayRatio?: number
  firstPay?: number
  deposit?: number
  occupiedCapital?: number
  leverageNote?: string
  note?: string
}

export function calcQuote(req: QuoteRequest): Promise<QuoteResponse> {
  return request.post('/rent/quote/calc', req)
}
