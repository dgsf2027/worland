import { afterEach, describe, expect, it, vi } from 'vitest'
import { convertPoint } from './assetLocation'

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.resetModules() })
describe('三家地图适配（模拟 SDK）', () => {
  it.each(['amap', 'baidu', 'tencent'] as const)('%s 将选点和搜索统一为 GCJ-02，并释放地图', async provider => {
    vi.resetModules()
    vi.stubEnv('VITE_AMAP_KEY', 'mock-only'); vi.stubEnv('VITE_BAIDU_MAP_AK', 'mock-only'); vi.stubEnv('VITE_TENCENT_MAP_KEY', 'mock-only')
    const expected = { longitude: 113.2644, latitude: 23.1291 }
    const bd = convertPoint(expected, 'GCJ02', 'BD09')
    const lnglat = { getLng: () => expected.longitude, getLat: () => expected.latitude }
    let click: (e: any) => void = () => {}
    const destroy = vi.fn(), center = vi.fn(), markerPosition = vi.fn()
    class FakeMap {
      on(_event: string, cb: any) { click = cb }
      addEventListener(_event: string, cb: any) { click = cb }
      enableScrollWheelZoom() {}
      getZoom() { return 10 }
      setZoom() {}
      setCenter = center
      panTo = center
      addOverlay() {}
      destroy = destroy
    }
    class Marker { setPosition = markerPosition; setMap() {}; setGeometries = markerPosition }
    class LatLng { constructor(public lat: number, public lng: number) {}; getLng() { return this.lng }; getLat() { return this.lat } }
    vi.stubGlobal('window', {
      AMap: { Map: FakeMap, Marker, Geocoder: class {
        getLocation(_address: string, cb: any) { cb('complete', { geocodes: [{ location: lnglat }] }) }
        getAddress(_p: any, cb: any) { cb('complete', { regeocode: { formattedAddress: '广州' } }) }
      } },
      BMap: { Map: FakeMap, Marker, Point: class { constructor(public lng: number, public lat: number) {} }, Geocoder: class {
        getPoint(_address: string, cb: any) { cb({ lng: bd.longitude, lat: bd.latitude }) }
        getLocation(_p: any, cb: any) { cb({ address: '广州' }) }
      } },
      TMap: { Map: FakeMap, MultiMarker: Marker, LatLng, service: { Geocoder: class {
        async getLocation() { return { result: { location: new LatLng(expected.latitude, expected.longitude) } } }
        async getAddress() { return { result: { address: '广州' } } }
      } } },
    })
    const { createAssetMap } = await import('./assetMapSdk')
    const onPick = vi.fn()
    const map = await createAssetMap(provider, {} as HTMLElement, expected, onPick)
    click(provider === 'amap' ? { lnglat } : provider === 'baidu' ? { latlng: { lng: bd.longitude, lat: bd.latitude } } : { latLng: lnglat })
    expect(onPick.mock.calls[0][0].longitude).toBeCloseTo(expected.longitude, 5)
    expect(onPick.mock.calls[0][0].latitude).toBeCloseTo(expected.latitude, 5)
    const result = await map.search('广州市某街道')
    expect(result.longitude).toBeCloseTo(expected.longitude, 5)
    expect(await map.address(expected)).toBe('广州')
    map.setPoint(expected)
    expect(markerPosition).toHaveBeenCalledOnce()
    if (provider === 'baidu') expect(center.mock.calls[0][0].lng).toBeCloseTo(bd.longitude, 5)
    map.destroy()
    expect(destroy).toHaveBeenCalledOnce()
  })
  it('缺少自己的 Key 时不加载外部 SDK', async () => {
    vi.stubEnv('VITE_AMAP_KEY', '')
    const { createAssetMap } = await import('./assetMapSdk')
    await expect(createAssetMap('amap', {} as HTMLElement, null, () => {})).rejects.toThrow('尚未配置')
  })
})
