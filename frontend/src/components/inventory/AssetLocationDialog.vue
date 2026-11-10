<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearInvLocation, updateInvLocation, type InvItem, type InvLocationSave } from '@/api/inventory'
import { MAP_KEYS, MAP_PROVIDERS, PICKER_URLS, convertPoint, mapLink, parseCoordinates, validPoint,
  type CoordinateSystem, type MapPoint, type MapProvider } from '@/utils/assetLocation'
import { createAssetMap, type AssetMap } from '@/utils/assetMapSdk'

const props = defineProps<{ item: InvItem; editable: boolean }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const visible = ref(true)
const provider = ref<MapProvider>('amap')
const canvas = ref<HTMLElement>()
const mapReady = ref(false)
const mapLoading = ref(false)
const mapError = ref('')
const saving = ref(false)
const locating = ref(false)
const searching = ref(false)
const searchText = ref('')
const coordinates = ref('')
const system = ref<CoordinateSystem>('GCJ02')
const point = ref<MapPoint | null>(validPoint(props.item.mapLongitude, props.item.mapLatitude)
  ? { longitude: props.item.mapLongitude!, latitude: props.item.mapLatitude! } : null)
const draft = reactive({ address: props.item.mapAddress || '',
  provider: (props.item.mapProvider || 'manual') as InvLocationSave['provider'],
  source: (props.item.mapSource || 'manual') as InvLocationSave['source'], accuracy: props.item.mapAccuracy ?? null })
const hasSaved = validPoint(props.item.mapLongitude, props.item.mapLatitude)
const providerName = computed(() => MAP_PROVIDERS.find(p => p.value === provider.value)!.label)
let map: AssetMap | null = null
let alive = true, mapVersion = 0, pointVersion = 0, gpsVersion = 0
function stop() {
  alive = false; ++mapVersion; ++pointVersion; ++gpsVersion
  map?.destroy(); map = null
}
onBeforeUnmount(stop)
watch(visible, v => { if (!v) { stop(); emit('close') } })

async function startMap() {
  const version = ++mapVersion
  map?.destroy(); map = null; mapReady.value = false; mapLoading.value = false; mapError.value = ''
  if (!MAP_KEYS[provider.value] || !alive) return
  const currentProvider = provider.value
  mapLoading.value = true
  await nextTick()
  if (!canvas.value || !alive || version !== mapVersion) return
  try {
    const instance = await createAssetMap(currentProvider, canvas.value, point.value, p => {
      if (alive && version === mapVersion && props.editable && !saving.value) choose(p, currentProvider, 'map')
    })
    if (!alive || version !== mapVersion) { instance.destroy(); return }
    map = instance; mapReady.value = true
    if (point.value) map.setPoint(point.value)
  } catch (e) { if (alive && version === mapVersion) mapError.value = e instanceof Error ? e.message : '地图加载失败' }
  finally { if (version === mapVersion) mapLoading.value = false }
}
watch(provider, p => { system.value = p === 'baidu' ? 'BD09' : 'GCJ02'; ++gpsVersion; locating.value = false; startMap() })
async function choose(p: MapPoint, from: InvLocationSave['provider'], source: InvLocationSave['source'], accuracy: number | null = null) {
  if (!validPoint(p.longitude, p.latitude)) { ElMessage.warning('定位结果无效，请重新选点'); return }
  ++gpsVersion; locating.value = false
  const version = ++pointVersion, currentMap = map, currentMapVersion = mapVersion
  point.value = p
  Object.assign(draft, { provider: from, source, accuracy, address: '' })
  map?.setPoint(p)
  if (currentMap) {
    try {
      const address = await currentMap.address(p)
      if (alive && version === pointVersion && currentMapVersion === mapVersion && !draft.address && !saving.value) draft.address = address.slice(0, 255)
    } catch { /* 地址可手动补充；坐标不因逆地理编码失败而丢失。 */ }
  }
}
function locate() {
  if (!props.editable || saving.value) return
  if (!window.isSecureContext || !navigator.geolocation) { ElMessage.warning('现场定位需要 HTTPS 和支持定位的浏览器'); return }
  const version = ++gpsVersion, selection = pointVersion
  locating.value = true
  navigator.geolocation.getCurrentPosition(position => {
    if (!alive || version !== gpsVersion || selection !== pointVersion) return
    locating.value = false
    choose(convertPoint({ longitude: position.coords.longitude, latitude: position.coords.latitude }, 'WGS84'), 'browser', 'gps', position.coords.accuracy)
    ElMessage.success('已取得本机位置，请确认资产在此处后保存')
  }, error => {
    if (!alive || version !== gpsVersion) return
    locating.value = false
    ElMessage.warning(error.code === 1 ? '未获定位权限，请在浏览器中允许定位，或手动选点' : '未能取得现场位置，请重试或手动选点')
  }, { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 })
}
async function search() {
  if (!map || !props.editable || !searchText.value.trim() || saving.value) return
  const version = ++pointVersion, current = mapVersion, from = provider.value, instance = map
  searching.value = true
  try {
    const p = await instance.search(searchText.value.trim())
    if (alive && current === mapVersion && version === pointVersion) choose(p, from, 'map')
  } catch (e) { if (alive && current === mapVersion && version === pointVersion) ElMessage.warning(e instanceof Error ? e.message : '未找到地址') }
  finally { searching.value = false }
}
function applyCoordinates() {
  if (!props.editable || saving.value) return
  try { choose(parseCoordinates(coordinates.value, system.value), 'manual', 'manual') }
  catch (e) { ElMessage.warning((e as Error).message) }
}
function link(p: MapProvider, navigation = false) { return point.value ? mapLink(p, point.value, `${props.item.name} ${props.item.code}`, navigation) : null }
async function save() {
  if (!props.editable || !point.value || saving.value) return
  saving.value = true; ++pointVersion; ++gpsVersion; locating.value = false
  try {
    await updateInvLocation(props.item.id, { ...point.value, ...draft })
    ElMessage.success('资产位置已保存'); emit('saved'); visible.value = false
  } finally { saving.value = false }
}
async function clear() {
  if (!props.editable || saving.value) return
  try { await ElMessageBox.confirm('清除该资产已保存的地图位置？存放位置文字将保留。', '清除资产定位', { type: 'warning' }) }
  catch { return }
  saving.value = true; ++pointVersion; ++gpsVersion; locating.value = false
  try { await clearInvLocation(props.item.id); ElMessage.success('资产定位已清除'); emit('saved'); visible.value = false }
  finally { saving.value = false }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`资产定位 · ${item.name} · ${item.code}`" width="860px" class="asset-location-dialog"
    append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @opened="startMap">
    <p class="location-note">每条台账保存一个位置。现场定位取得的是当前设备位置，请在资产现场操作并确认后保存。</p>
    <p v-if="item.mapUpdatedAt" class="location-note">最近更新：{{ item.mapUpdatedAt.replace('T', ' ') }} · {{ item.mapUpdatedBy || '—' }}</p>
    <div class="location-bar">
      <el-radio-group v-model="provider" :disabled="saving"><el-radio-button v-for="p in MAP_PROVIDERS" :key="p.value" :label="p.value">{{ p.label }}地图</el-radio-button></el-radio-group>
      <el-button v-if="editable" :loading="locating" :disabled="saving" @click="locate">使用现场位置</el-button>
    </div>
    <div v-if="MAP_KEYS[provider]">
      <div v-if="editable" class="location-bar">
        <el-input v-model="searchText" placeholder="输入完整地址（省市、街道、门牌）" :disabled="saving" @keyup.enter="search" />
        <el-button :loading="searching" :disabled="!mapReady || saving" @click="search">查找地址</el-button>
      </div>
      <div :key="provider" ref="canvas" class="asset-map" v-loading="mapLoading" />
      <el-alert v-if="mapError" :title="mapError" type="warning" :closable="false" />
      <el-button v-if="mapError" link @click="startMap">重新加载地图</el-button>
      <p v-if="editable && mapReady" class="location-note">点击地图选定位置，再点击“保存位置”。</p>
    </div>
    <el-alert v-else :title="`${providerName}内嵌地图尚未配置。可先使用现场定位、粘贴坐标，再打开地图核对。`" type="info" :closable="false" />
    <div v-if="editable" class="coordinate-entry">
      <div class="location-bar">
        <a :href="PICKER_URLS[provider]" target="_blank" rel="noopener noreferrer">打开{{ providerName }}官方选点工具 ↗</a>
        <span class="location-note">选点后复制经纬度，返回此处粘贴。</span>
      </div>
      <p v-if="provider === 'baidu'" class="location-note">百度游客坐标仅保留两位小数，不能准确定位资产；请认证后拾取，或使用现场定位。</p>
      <div class="location-bar">
        <el-input v-model="coordinates" aria-label="粘贴坐标" placeholder="经度,纬度" :disabled="saving" />
        <el-select v-model="system" aria-label="坐标系" style="min-width:200px" :disabled="saving">
          <el-option label="高德 / 腾讯（GCJ-02）" value="GCJ02" />
          <el-option label="百度（BD-09）" value="BD09" />
          <el-option label="GPS（WGS-84）" value="WGS84" />
        </el-select>
        <el-button :disabled="saving" @click="applyCoordinates">应用坐标</el-button>
      </div>
    </div>
    <el-form label-width="86px" class="location-form">
      <el-form-item label="当前选点"><span v-if="point">{{ point.longitude.toFixed(7) }}, {{ point.latitude.toFixed(7) }}</span><span v-else>尚未定位</span></el-form-item>
      <el-form-item v-if="draft.source === 'gps' && draft.accuracy != null && point" label="定位精度">约 {{ Math.ceil(draft.accuracy) }} 米（请结合现场核对）</el-form-item>
      <el-form-item label="位置说明"><el-input v-model="draft.address" maxlength="255" show-word-limit :disabled="!editable || saving" placeholder="如广州市某仓库东门；可手动补充" /></el-form-item>
    </el-form>
    <div v-if="point" class="navigation-links">
      <div v-for="p in MAP_PROVIDERS" :key="p.value">
        <template v-if="link(p.value)"><a :href="link(p.value)!" target="_blank" rel="noopener noreferrer">{{ p.label }}查看</a><a :href="link(p.value, true)!" target="_blank" rel="noopener noreferrer">{{ p.label }}导航 ↗</a></template>
        <span v-else class="location-note">腾讯地图待配置</span>
      </div>
    </div>
    <p class="location-note">查看与导航使用当前选点；修改后需保存才会更新台账。电脑导航可在地图中填写出发地。</p>
    <template #footer>
      <el-button v-if="editable && hasSaved" type="danger" plain :disabled="saving" @click="clear">清除定位</el-button>
      <el-button :disabled="saving" @click="visible = false">关闭</el-button>
      <el-button v-if="editable" type="primary" :loading="saving" :disabled="!point || locating || searching" @click="save">保存位置</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.location-note { color: #687386; font-size: 13px; line-height: 1.6; margin: 8px 0; }
.location-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 12px 0; }
.location-bar > .el-input { flex: 1; min-width: 180px; }
.location-bar > .el-select { width: 210px; }
.asset-map { height: 330px; background: #f1f5f9; border-radius: 6px; }
.coordinate-entry { padding: 4px 12px; background: #f7f9fc; margin-top: 14px; border-radius: 6px; }
.location-form { margin-top: 16px; }
.navigation-links { display: flex; flex-wrap: wrap; gap: 22px; }
a { color: #1677d2; text-decoration: none; }
.navigation-links a + a { margin-left: 12px; }
</style>
<style>
.asset-location-dialog { max-width: calc(100vw - 24px); }
</style>
