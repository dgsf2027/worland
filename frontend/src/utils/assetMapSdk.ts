import { MAP_KEYS, convertPoint, type MapPoint, type MapProvider } from './assetLocation'

export interface AssetMap {
  setPoint(point: MapPoint): void
  search(address: string): Promise<MapPoint>
  address(point: MapPoint): Promise<string>
  destroy(): void
}
// 三家浏览器 SDK 的接口封装在此边界内，业务层只接触 GCJ-02。
type Sdk = any
const pending = new Map<MapProvider, Promise<Sdk>>()
let sequence = 0
function loadSdk(provider: MapProvider): Promise<Sdk> {
  const key = MAP_KEYS[provider]
  if (!key) return Promise.reject(new Error('尚未配置此地图，可使用现场定位或粘贴坐标'))
  const existing = pending.get(provider)
  if (existing) return existing
  const win = window as unknown as Record<string, Sdk>
  const name = { amap: 'AMap', baidu: 'BMap', tencent: 'TMap' }[provider]
  if (win[name]) return Promise.resolve(win[name])
  const promise = new Promise<Sdk>((resolve, reject) => {
    const callback = `__assetMapReady${++sequence}`
    const script = document.createElement('script')
    let done = false
    const finish = (error?: Error) => {
      if (done) return
      done = true
      clearTimeout(timer)
      // 保留空回调，避免超时后迟到的 SDK 抛出全局异常。
      win[callback] = () => {}
      script.onerror = null
      if (error) { script.remove(); reject(error) } else resolve(win[name])
    }
    const timer = setTimeout(() => finish(new Error('地图加载超时，请稍后重试')), 20000)
    win[callback] = () => finish(win[name] ? undefined : new Error('地图初始化失败'))
    script.onerror = () => finish(new Error('地图加载失败，请检查网络及地图配置'))
    if (provider === 'amap') {
      win._AMapSecurityConfig = { serviceHost: import.meta.env.VITE_AMAP_SERVICE_HOST || `${location.origin}/_AMapService` }
      script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&plugin=AMap.Geocoder&callback=${callback}`
    } else if (provider === 'baidu') {
      script.src = `https://api.map.baidu.com/api?v=4.0&ak=${encodeURIComponent(key)}&callback=${callback}`
    } else {
      script.src = `https://map.qq.com/api/gljs?v=1.exp&key=${encodeURIComponent(key)}&libraries=service&callback=${callback}`
    }
    document.head.appendChild(script)
  })
  pending.set(provider, promise)
  promise.catch(() => pending.delete(provider))
  return promise
}
function timed<T>(executor: (resolve: (value: T) => void, reject: (reason: Error) => void) => void): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('地址查询超时，请重试')), 12000)
    try { executor(v => { clearTimeout(timer); resolve(v) }, e => { clearTimeout(timer); reject(e) }) }
    catch (e) { clearTimeout(timer); reject(e) }
  })
}
export async function createAssetMap(provider: MapProvider, el: HTMLElement, initial: MapPoint | null,
  onPick: (point: MapPoint) => void): Promise<AssetMap> {
  const sdk = await loadSdk(provider)
  const center = initial || { longitude: 113.2644, latitude: 23.1291 }
  const zoom = initial ? 16 : 10
  if (provider === 'amap') {
    const map = new sdk.Map(el, { center: [center.longitude, center.latitude], zoom })
    const marker = new sdk.Marker()
    const geocoder = new sdk.Geocoder()
    map.on('click', (e: Sdk) => onPick({ longitude: e.lnglat.getLng(), latitude: e.lnglat.getLat() }))
    const point = (p: MapPoint) => [p.longitude, p.latitude]
    return {
      setPoint(p) { marker.setPosition(point(p)); marker.setMap(map); map.setZoom(Math.max(map.getZoom(), 16)); map.setCenter(point(p)) },
      search: address => timed((resolve, reject) => geocoder.getLocation(address, (status: string, result: Sdk) => {
        const p = result?.geocodes?.[0]?.location
        status === 'complete' && p ? resolve({ longitude: p.getLng(), latitude: p.getLat() }) : reject(new Error('未找到地址，请补充省市和街道'))
      })),
      address: p => timed(resolve => geocoder.getAddress(point(p), (status: string, result: Sdk) => resolve(status === 'complete' ? result?.regeocode?.formattedAddress || '' : ''))),
      destroy() { map.destroy() },
    }
  }
  if (provider === 'baidu') {
    const native = (p: MapPoint) => { const v = convertPoint(p, 'GCJ02', 'BD09'); return new sdk.Point(v.longitude, v.latitude) }
    const canonical = (p: Sdk) => convertPoint({ longitude: p.lng, latitude: p.lat }, 'BD09')
    const map = new sdk.Map(el, { center: native(center), zoom })
    map.enableScrollWheelZoom(true)
    const marker = new sdk.Marker(native(center))
    const geocoder = new sdk.Geocoder()
    map.addEventListener('click', (e: Sdk) => onPick(canonical(e.latlng || e.point)))
    return {
      setPoint(p) { marker.setPosition(native(p)); map.addOverlay(marker); map.setZoom(Math.max(map.getZoom(), 16)); map.panTo(native(p)) },
      search: address => timed((resolve, reject) => geocoder.getPoint(address, (p: Sdk) => p ? resolve(canonical(p)) : reject(new Error('未找到地址，请补充省市和街道')))),
      address: p => timed(resolve => geocoder.getLocation(native(p), (result: Sdk) => resolve(result?.address || ''))),
      destroy() { map.destroy() },
    }
  }
  const native = (p: MapPoint) => new sdk.LatLng(p.latitude, p.longitude)
  const canonical = (p: Sdk): MapPoint => ({ longitude: p.getLng(), latitude: p.getLat() })
  const map = new sdk.Map(el, { center: native(center), zoom })
  const marker = new sdk.MultiMarker({ map, geometries: [] })
  const geocoder = new sdk.service.Geocoder()
  map.on('click', (e: Sdk) => onPick(canonical(e.latLng)))
  return {
    setPoint(p) { marker.setGeometries([{ id: 'asset', position: native(p) }]); map.setZoom(Math.max(map.getZoom(), 16)); map.setCenter(native(p)) },
    search: address => timed((resolve, reject) => {
      geocoder.getLocation({ address }).then((r: Sdk) => r.result?.location ? resolve(canonical(r.result.location)) : reject(new Error('未找到地址'))).catch(() => reject(new Error('地址查询失败，请检查网络及地图配置')))
    }),
    address: p => timed((resolve, reject) => {
      geocoder.getAddress({ location: native(p) }).then((r: Sdk) => resolve(r.result?.address || '')).catch(() => reject(new Error('地址查询失败')))
    }),
    destroy() { marker.setMap(null); map.destroy() },
  }
}
