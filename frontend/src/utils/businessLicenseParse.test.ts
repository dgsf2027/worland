import { describe, expect, it } from 'vitest'
import { applyLicenseFields, capitalInWan, parseBusinessLicense } from './businessLicenseParse'

describe('营业执照字段', () => {
  it('按标签切分双栏执照，金额转万元且不混入登记日期', () => {
    expect(parseBusinessLicense(`名 称 示例仓储有限公司 注册资本 壹佰万元（人民币）
类 型 有限责任公司 成立日期 2024年08月20日
法定代表人 张三 住 所 广州市白云区示例街7号216室
经营范围 仓储服务；技术咨询。
登记机关 示例市场监管局
2024年09月11日`)).toEqual({ companyName: '示例仓储有限公司', legalPerson: '张三', registeredCapitalWan: '100', establishedDate: '2024-08-20', address: '广州市白云区示例街7号216室', companyProfile: '仓储服务;技术咨询。' })
  })
  it.each([['200万元', '200'], ['1,000,000元', '100'], ['人民币壹仟伍佰万元整', '1500'], ['1.2亿元', '12000'], ['60.5万', '60.5']])('换算资本 %s', (input, expected) => {
    expect(capitalInWan(input)).toBe(expected)
  })
  it.each(['100万美元', '壹佰万元(港币)', '大约200万', 'OCR噪声', '100欧元', '100'])('不猜测外币或破损金额 %s', (input) => expect(capitalInWan(input)).toBeUndefined())
  it('拒绝非法日期、无标签姓名，不把发照日期当作成立日期', () => {
    expect(parseBusinessLicense('成立日期2024年02月30日\n登记日期2024年09月11日\n张三')).toEqual({})
  })
  it('只填空白，覆盖必须主动选择，空识别值不会删除已有数据', () => {
    const form = { companyName: '手工公司', address: '', phone: '123', legalPerson: '李四' }
    expect(applyLicenseFields(form, { companyName: '识别公司', address: '测试地址一号' })).toEqual(['address'])
    expect(form.companyName).toBe('手工公司')
    applyLicenseFields(form, { companyName: '识别公司' }, true)
    expect(form).toEqual({ companyName: '识别公司', address: '测试地址一号', phone: '123', legalPerson: '李四' })
  })
  it('合并换行的经营范围并截断 OCR 的空白分块噪声', () => {
    expect(parseBusinessLicense('经营范围仓储服务；\n技术咨询。\n\n零碎花边文字').companyProfile).toBe('仓储服务;技术咨询。')
  })
})
