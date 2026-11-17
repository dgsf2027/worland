<script setup lang="ts">
import { nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearInvLocation, updateInvLocation, type InvItem, type InvLocationSave } from '@/api/inventory'
import { MAP_KEYS, MAP_PROVIDERS, convertPoint, mapLink, validPoint,
  type MapPoint, type MapProvider } from '@/utils/assetLocation'
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
const point = ref<MapPoint | null>(validPoint(props.item.mapLongitude, props.item.mapLatitude)
  ? { longitude: props.item.mapLongitude!, latitude: props.item.mapLatitude! } : null)
const draft = reactive({ address: props.item.mapAddress || '',
  provider: (props.item.mapProvider || 'manual') as InvLocationSave['provider'],
  source: (props.item.mapSource || 'manual') as InvLocationSave['source'], accuracy: props.item.mapAccuracy ?? null })
const hasSaved = validPoint(props.item.mapLongitude, props.item.mapLatitude)
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
watch(provider, () => { ++gpsVersion; locating.value = false; startMap() })
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
    <p class="location-note">搜索地址或点击地图选择资产位置，确认标记后保存。每条台账保存一个位置。</p>
    <p v-if="item.mapUpdatedAt" class="location-note">最近更新：{{ item.mapUpdatedAt.replace('T', ' ') }} · {{ item.mapUpdatedBy || '—' }}</p>
    <div class="location-bar">
      <el-radio-group v-model="provider" :disabled="saving"><el-radio-button v-for="p in MAP_PROVIDERS" :key="p.value" :label="p.value" :disabled="p.value !== 'amap' && !MAP_KEYS[p.value]">{{ p.label }}地图</el-radio-button></el-radio-group>
      <el-button v-if="editable" :loading="locating" :disabled="saving" @click="locate">使用现场位置</el-button>
    </div>
    <div v-if="editable" class="location-bar">
      <el-input v-model="searchText" placeholder="搜索资产地址，如广州市白云区某仓库" :disabled="!mapReady || saving" clearable @keyup.enter="search" />
      <el-button type="primary" :loading="searching" :disabled="!mapReady || saving || !searchText.trim()" @click="search">搜索地址</el-button>
    </div>
    <template v-if="MAP_KEYS[provider]">
      <div :key="provider" ref="canvas" class="asset-map" :class="{ 'can-pick': editable }" v-loading="mapLoading" element-loading-text="正在加载地图…" />
      <el-alert v-if="mapError" :title="mapError" type="warning" :closable="false" />
      <el-button v-if="mapError" link @click="startMap">重新加载地图</el-button>
      <p v-if="editable && mapReady" class="location-note">点击地图移动位置标记；地址搜索后也可点选微调。</p>
    </template>
    <el-empty v-else class="map-unavailable" :image-size="60" description="地图选点暂未启用，请联系管理员配置地图服务">
      <span class="location-note">在资产现场时，也可使用上方“使用现场位置”。</span>
    </el-empty>
    <el-form label-width="86px" class="location-form">
      <el-form-item label="选点状态"><el-tag v-if="point" type="success">已选择位置</el-tag><span v-else>请在地图上选择资产位置</span></el-form-item>
      <el-form-item v-if="draft.source === 'gps' && draft.accuracy != null && point" label="定位精度">约 {{ Math.ceil(draft.accuracy) }} 米（请结合现场核对）</el-form-item>
      <el-form-item label="位置说明"><el-input v-model="draft.address" maxlength="255" show-word-limit :disabled="!editable || saving" placeholder="选点后自动显示地址，可补充楼层、仓位等说明" /></el-form-item>
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
.asset-map { height: 400px; background: #f1f5f9; border-radius: 6px; }
.can-pick { cursor: crosshair; }
.map-unavailable { min-height: 260px; background: #f7f9fc; border-radius: 6px; }
@media (max-width: 600px) { .asset-map { height: 300px; } }
.location-form { margin-top: 16px; }
.navigation-links { display: flex; flex-wrap: wrap; gap: 22px; }
a { color: #1677d2; text-decoration: none; }
.navigation-links a + a { margin-left: 12px; }
</style>
<style>
.asset-location-dialog { max-width: calc(100vw - 24px); }
</style>
