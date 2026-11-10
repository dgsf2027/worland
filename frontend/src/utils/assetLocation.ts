import gcoord from 'gcoord'

export type MapProvider = 'amap' | 'baidu' | 'tencent'
export type CoordinateSystem = 'GCJ02' | 'BD09' | 'WGS84'
export interface MapPoint { longitude: number; latitude: number }
export const MAP_PROVIDERS: { value: MapProvider; label: string }[] = [
  { value: 'amap', label: '高德' }, { value: 'baidu', label: '百度' }, { value: 'tencent', label: '腾讯' },
]
export const MAP_KEYS: Record<MapProvider, string> = {
  amap: (import.meta.env.VITE_AMAP_KEY || '').trim(),
  baidu: (import.meta.env.VITE_BAIDU_MAP_AK || '').trim(),
  tencent: (import.meta.env.VITE_TENCENT_MAP_KEY || '').trim(),
}
export const PICKER_URLS: Record<MapProvider, string> = {
  amap: 'https://lbs.amap.com/tools/picker',
  baidu: 'https://api.map.baidu.com/lbsapi/getpoint/index.html',
  tencent: 'https://lbs.qq.com/getPoint/',
}
export function validPoint(longitude: unknown, latitude: unknown): boolean {
  return typeof longitude === 'number' && typeof latitude === 'number'
    && Number.isFinite(longitude) && Number.isFinite(latitude)
    && Math.abs(longitude) <= 180 && Math.abs(latitude) <= 90
}
export function convertPoint(point: MapPoint, from: CoordinateSystem, to: CoordinateSystem = 'GCJ02'): MapPoint {
  if (!validPoint(point.longitude, point.latitude)) throw new Error('经纬度无效')
  const [longitude, latitude] = gcoord.transform([point.longitude, point.latitude], gcoord[from], gcoord[to])
  return { longitude, latitude }
}
/** 粘贴官方拾取器输出，顺序始终为经度、纬度；不猜测坐标系。 */
export function parseCoordinates(text: string, system: CoordinateSystem): MapPoint {
  const parts = text.trim().split(/[,，\s]+/)
  if (parts.length !== 2 || parts.some(p => !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(p))) {
    throw new Error('请输入“经度,纬度”，例如 113.2644,23.1291')
  }
  return convertPoint({ longitude: Number(parts[0]), latitude: Number(parts[1]) }, system)
}
export function mapLink(provider: MapProvider, point: MapPoint, name: string, navigation = false, tencentKey = MAP_KEYS.tencent): string | null {
  if (!validPoint(point.longitude, point.latitude)) return null
  // 名称中的分隔符不能成为 URI 协议中的额外坐标/参数。
  const title = name.replace(/[,;|:]/g, ' ').slice(0, 80)
  const lng = point.longitude.toFixed(7), lat = point.latitude.toFixed(7)
  let base: string, params: Record<string, string>
  if (provider === 'amap') {
    base = `https://uri.amap.com/${navigation ? 'navigation' : 'marker'}`
    params = navigation ? { to: `${lng},${lat},${title}`, mode: 'car' } : { position: `${lng},${lat}`, name: title }
    Object.assign(params, { src: 'worland', coordinate: 'gaode', callnative: '1' })
  } else if (provider === 'baidu') {
    base = `https://api.map.baidu.com/${navigation ? 'direction' : 'marker'}`
    params = navigation ? { origin: '我的位置', destination: `latlng:${lat},${lng}|name:${title}`, mode: 'driving' }
      : { location: `${lat},${lng}`, title, content: title }
    Object.assign(params, { coord_type: 'gcj02', output: 'html', src: 'webapp.worland.asset' })
  } else {
    if (!tencentKey) return null
    base = `https://apis.map.qq.com/uri/v1/${navigation ? 'routeplan' : 'marker'}`
    params = navigation ? { type: 'drive', to: title, tocoord: `${lat},${lng}` }
      : { marker: `coord:${lat},${lng};title:${title}` }
    params.referer = tencentKey
  }
  return `${base}?${new URLSearchParams(params)}`
}
