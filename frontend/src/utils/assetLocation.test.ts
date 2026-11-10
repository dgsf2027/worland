import { describe, expect, it } from 'vitest'
import { convertPoint, mapLink, parseCoordinates, validPoint } from './assetLocation'

describe('资产地图坐标', () => {
  it('GPS 转 GCJ-02 后保存，北京参考点不发生经纬度颠倒', () => {
    const p = convertPoint({ longitude: 116.404, latitude: 39.915 }, 'WGS84')
    expect(p.longitude).toBeCloseTo(116.4102445, 5)
    expect(p.latitude).toBeCloseTo(39.9164043, 5)
  })
  it('百度往返转换保持同一地点，切换高德腾讯不再偏移', () => {
    const p = { longitude: 113.2644, latitude: 23.1291 }
    const bd = convertPoint(p, 'GCJ02', 'BD09')
    const back = convertPoint(bd, 'BD09')
    expect(back.longitude).toBeCloseTo(p.longitude, 5)
    expect(back.latitude).toBeCloseTo(p.latitude, 5)
    expect(convertPoint(p, 'GCJ02')).toEqual(p)
    expect(p).toEqual({ longitude: 113.2644, latitude: 23.1291 })
  })
  it('允许零坐标，但拒绝缺失、NaN、无穷和越界坐标', () => {
    expect(validPoint(0, 0)).toBe(true)
    for (const [lng, lat] of [[null, 0], [undefined, 0], [NaN, 20], [Infinity, 20], [181, 20], [113, 91], ['113', 23]]) {
      expect(validPoint(lng, lat)).toBe(false)
    }
  })
  it('粘贴拾取器坐标必须明确来源，不会把空文本或分享链接当成零坐标', () => {
    expect(parseCoordinates('113.2644，23.1291', 'GCJ02')).toEqual({ longitude: 113.2644, latitude: 23.1291 })
    for (const s of ['', ',23', '113,23,4', 'map://113,23', 'Infinity,23', '113,200']) expect(() => parseCoordinates(s, 'GCJ02')).toThrow()
    const bd = convertPoint({ longitude: 113.2644, latitude: 23.1291 }, 'GCJ02', 'BD09')
    expect(parseCoordinates(`${bd.longitude},${bd.latitude}`, 'BD09').longitude).toBeCloseTo(113.2644, 5)
  })
})
describe('地图跳转', () => {
  const point = { longitude: 113.2644, latitude: 23.1291 }
  it('高德经度在前、百度纬度在前，两家查看和导航均不带密钥', () => {
    expect(new URL(mapLink('amap', point, '资产 & A')!).searchParams.get('position')).toBe('113.2644000,23.1291000')
    const route = new URL(mapLink('amap', point, '资产 & A', true)!)
    expect(route.searchParams.get('to')).toBe('113.2644000,23.1291000,资产 & A')
    expect(route.searchParams.has('key')).toBe(false)
    const baidu = new URL(mapLink('baidu', point, '资产', true)!)
    expect(baidu.searchParams.get('destination')).toBe('latlng:23.1291000,113.2644000|name:资产')
    expect(baidu.searchParams.get('coord_type')).toBe('gcj02')
    expect(baidu.searchParams.has('ak')).toBe(false)
  })
  it('腾讯没有自己的 Key 时不产生伪可用链接，配置后使用纬度在前', () => {
    expect(mapLink('tencent', point, '资产', true, '')).toBeNull()
    const url = new URL(mapLink('tencent', point, '资产', true, 'test-key')!)
    expect(url.searchParams.get('tocoord')).toBe('23.1291000,113.2644000')
    expect(url.searchParams.get('referer')).toBe('test-key')
  })
  it('转义名称，不让协议分隔符变成额外参数；无效坐标不能导航', () => {
    const url = new URL(mapLink('baidu', point, '资产|name:伪造&ak=x')!)
    expect(url.searchParams.get('title')).toBe('资产 name 伪造&ak=x')
    expect(url.searchParams.has('ak')).toBe(false)
    expect(mapLink('amap', { longitude: NaN, latitude: 0 }, '资产')).toBeNull()
  })
})
