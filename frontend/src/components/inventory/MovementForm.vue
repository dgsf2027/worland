<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { checkUploadFile } from '@/api/asset'
import {
  outboundRental, returnRental, uploadInvPhoto, damageAmount,
  CONDITION_LEVELS, PHOTO_EXTS, PHOTO_MAX_MB,
  type InvRental, type PriceRow, type DamageLine, type MovementResult,
} from '@/api/inventory'

/** 出库 / 归还登记:数量、配件、设备状况、现场照片;归还时检查损坏缺件并自动计算赔偿。桌面弹窗与扫码页共用。 */
const props = defineProps<{
  mode: 'out' | 'return'
  rental: InvRental
  prices: PriceRow[]
}>()
const emit = defineEmits<{ (e: 'done', r: MovementResult): void; (e: 'cancel'): void }>()

const unit = computed(() => props.rental.unit || '套')
const maxQty = computed(() => (props.mode === 'out' ? props.rental.pendingOutQty : props.rental.onSiteQty))

interface DamageRow extends DamageLine { unit: string; custom: boolean }
const form = reactive({
  qty: 0,
  goodQty: 0,
  repairQty: 0,
  scrapQty: 0,
  accessories: '',
  conditionLevel: '完好',
  conditionDesc: '',
  remark: '',
})
const damages = ref<DamageRow[]>([])

function reset() {
  form.qty = maxQty.value
  form.goodQty = maxQty.value
  form.repairQty = 0
  form.scrapQty = 0
  form.accessories = ''
  form.conditionLevel = '完好'
  form.conditionDesc = ''
  form.remark = ''
  damages.value = props.prices.map((p) => ({ partName: p.partName, unit: p.unit, damagedQty: 0, missingQty: 0, custom: false }))
  clearPhotos()
}
watch(() => [props.rental.id, props.mode], reset, { immediate: true })

// 归还数量变化时,完好数自动补齐(维修/报废手填)
watch(() => [form.qty, form.repairQty, form.scrapQty], () => {
  form.goodQty = Math.max(0, Number(form.qty || 0) - Number(form.repairQty || 0) - Number(form.scrapQty || 0))
})
const splitOk = computed(() => form.goodQty + Number(form.repairQty || 0) + Number(form.scrapQty || 0) === Number(form.qty || 0))

const priceOf = (name: string) => props.prices.find((p) => p.partName === name.trim())
const rowAmount = (r: DamageRow) => damageAmount(r, props.prices)
const damageTotal = computed(() => Math.round(damages.value.reduce((s, r) => s + rowAmount(r), 0) * 100) / 100)
const unpriced = computed(() => damages.value.filter((r) => {
  const p = priceOf(r.partName)
  return (r.damagedQty > 0 && !Number(p?.damagePrice)) || (r.missingQty > 0 && !Number(p?.missingPrice))
}).map((r) => r.partName || '(未命名)'))
function addDamageRow() {
  damages.value.push({ partName: '', unit: '个', damagedQty: 0, missingQty: 0, custom: true })
}

// ---- 现场照片(提交成功后上传到本次记录) ----
const photos = ref<{ file: File; url: string }[]>([])
const photoInput = ref<HTMLInputElement>()
function onPick(e: Event) {
  const picked = Array.from((e.target as HTMLInputElement).files || [])
  ;(e.target as HTMLInputElement).value = ''
  for (const f of picked) {
    const err = checkUploadFile(f, PHOTO_EXTS, PHOTO_MAX_MB)
    if (err) { ElMessage.warning(err); continue }
    photos.value.push({ file: f, url: URL.createObjectURL(f) })
  }
}
function removePhoto(i: number) {
  URL.revokeObjectURL(photos.value[i].url)
  photos.value.splice(i, 1)
}
function clearPhotos() {
  photos.value.forEach((p) => URL.revokeObjectURL(p.url))
  photos.value = []
}
onBeforeUnmount(clearPhotos)

const money = (v: number) => '¥' + Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

const submitting = ref(false)
async function submit() {
  const n = Number(form.qty || 0)
  if (n <= 0 || n > maxQty.value) {
    ElMessage.warning(`${props.mode === 'out' ? '出库' : '归还'}数量须在 1 ~ ${maxQty.value} 之间`)
    return
  }
  if (props.mode === 'return' && !splitOk.value) {
    ElMessage.warning('完好 + 维修 + 报废 须等于归还数量')
    return
  }
  const used = damages.value.filter((r) => r.damagedQty > 0 || r.missingQty > 0)
  if (used.some((r) => !r.partName.trim())) {
    ElMessage.warning('损坏缺件的检查项名称不能为空')
    return
  }
  const label = props.mode === 'out' ? '出库' : '归还'
  let msg = `${props.rental.customerName} · ${label} ${n} ${unit.value}`
  if (props.mode === 'return') {
    msg += `（完好 ${form.goodQty} / 维修 ${form.repairQty} / 报废 ${form.scrapQty}）`
    if (used.length) msg += `，损坏缺件 ${used.length} 项，赔偿 ${money(damageTotal.value)}`
  }
  if (photos.value.length) msg += `，现场照片 ${photos.value.length} 张`
  await ElMessageBox.confirm(msg, `确认${label}`, { type: 'info' })

  submitting.value = true
  try {
    const base = {
      qty: n,
      accessories: form.accessories,
      conditionLevel: form.conditionLevel,
      conditionDesc: form.conditionDesc,
      remark: form.remark,
    }
    const res = props.mode === 'out'
      ? await outboundRental(props.rental.id, base)
      : await returnRental(props.rental.id, {
        ...base,
        goodQty: form.goodQty,
        repairQty: Number(form.repairQty || 0),
        scrapQty: Number(form.scrapQty || 0),
        damages: used.map((r) => ({ partName: r.partName.trim(), damagedQty: r.damagedQty, missingQty: r.missingQty, remark: r.remark })),
      })
    let failed = 0
    for (const p of photos.value) {
      try { await uploadInvPhoto('inv_movement', res.movementId, p.file) } catch { failed++ }
    }
    if (failed) ElMessage.warning(`${label}已登记，但有 ${failed} 张照片上传失败，可在出入库记录里补传`)
    else ElMessage.success(`${label}已登记${props.mode === 'return' && res.compensationTotal > 0 ? `，赔偿 ${money(res.compensationTotal)}` : ''}`)
    if (res.unpricedParts?.length) ElMessage.warning(`检查项「${res.unpricedParts.join('、')}」未设置赔偿单价，金额按 0 计`)
    clearPhotos()
    emit('done', res)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-form label-position="top" class="mf" @submit.prevent>
    <div class="mf-head">
      <b>{{ rental.customerName }}</b> · {{ rental.rentalNo }}
      <div class="tip">
        {{ rental.itemName }}{{ rental.itemSpec ? `（${rental.itemSpec}）` : '' }} · 出租 {{ rental.qty }} / 已出库 {{ rental.outQty }} / 已归还 {{ rental.returnedQty }}
        <template v-if="rental.installAddress"><br />安装地址：{{ rental.installAddress }}</template>
      </div>
    </div>

    <el-form-item :label="`${mode === 'out' ? '出库' : '归还'}数量（最多 ${maxQty} ${unit}）`" required>
      <el-input-number v-model="form.qty" :min="1" :max="maxQty" :precision="0" />
    </el-form-item>

    <el-form-item v-if="mode === 'return'" label="归还去向（完好回库存 / 转维修 / 报废）">
      <div class="mf-split">
        <span>完好 <b>{{ form.goodQty }}</b></span>
        <span>维修 <el-input-number v-model="form.repairQty" size="small" :min="0" :max="form.qty" :precision="0" /></span>
        <span>报废 <el-input-number v-model="form.scrapQty" size="small" :min="0" :max="form.qty" :precision="0" /></span>
      </div>
      <div v-if="!splitOk" class="bad">维修 + 报废 超过归还数量</div>
    </el-form-item>

    <el-form-item label="配件">
      <el-input v-model="form.accessories" type="textarea" :rows="2" maxlength="500" placeholder="如：灯条×12、控制器×1、电源线×2" />
    </el-form-item>

    <el-form-item label="设备状况">
      <el-radio-group v-model="form.conditionLevel">
        <el-radio-button v-for="l in CONDITION_LEVELS" :key="l" :value="l">{{ l }}</el-radio-button>
      </el-radio-group>
      <el-input v-model="form.conditionDesc" class="mt6" maxlength="500" placeholder="状况说明（选填）" />
    </el-form-item>

    <el-form-item v-if="mode === 'return'" label="损坏与缺件检查（按赔偿价目自动计算）">
      <div class="mf-dmg">
        <div v-for="(r, i) in damages" :key="i" class="mf-dmg-row">
          <el-input v-if="r.custom" v-model="r.partName" size="small" class="mf-name" placeholder="检查项" />
          <span v-else class="mf-name">{{ r.partName }}</span>
          <span class="mf-q">损坏 <el-input-number v-model="r.damagedQty" size="small" :min="0" :precision="0" controls-position="right" /></span>
          <span class="mf-q">缺失 <el-input-number v-model="r.missingQty" size="small" :min="0" :precision="0" controls-position="right" /></span>
          <span class="mf-amt">{{ rowAmount(r) ? money(rowAmount(r)) : '' }}</span>
        </div>
        <div class="mf-foot">
          <el-button size="small" link type="primary" @click="addDamageRow">＋ 其它检查项</el-button>
          <span class="mf-total">赔偿合计 <b>{{ money(damageTotal) }}</b></span>
        </div>
        <div v-if="unpriced.length" class="bad">「{{ unpriced.join('、') }}」未设置赔偿单价，金额按 0 计（资产管理 › 设置 里维护价目）</div>
      </div>
    </el-form-item>

    <el-form-item label="现场照片">
      <div class="mf-photos">
        <div v-for="(p, i) in photos" :key="p.url" class="mf-ph">
          <img :src="p.url" alt="" />
          <el-button class="mf-ph-del" type="danger" circle size="small" @click="removePhoto(i)">×</el-button>
        </div>
        <div class="mf-ph mf-ph-add" @click="photoInput?.click()">＋<br /><small>拍照</small></div>
        <input ref="photoInput" type="file" accept="image/*" capture="environment" multiple hidden @change="onPick" />
      </div>
    </el-form-item>

    <el-form-item label="备注">
      <el-input v-model="form.remark" maxlength="255" />
    </el-form-item>

    <div class="mf-actions">
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">确认{{ mode === 'out' ? '出库' : '归还' }}</el-button>
    </div>
  </el-form>
</template>

<style scoped>
.mf-head { margin-bottom: 12px; line-height: 1.6; }
.tip { color: #909399; font-size: 12px; }
.bad { color: #e0533d; font-size: 12px; }
.mt6 { margin-top: 6px; }
.mf-split { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.mf-dmg { width: 100%; }
.mf-dmg-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 4px 0; border-bottom: 1px dashed #ebeef5; }
.mf-name { width: 88px; font-size: 13px; }
.mf-q { font-size: 12px; color: #606266; }
.mf-q :deep(.el-input-number) { width: 92px; }
.mf-amt { margin-left: auto; color: #e0533d; font-size: 13px; min-width: 70px; text-align: right; }
.mf-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 6px; }
.mf-total b { color: #e0533d; font-size: 15px; }
.mf-photos { display: flex; flex-wrap: wrap; gap: 8px; }
.mf-ph { position: relative; width: 76px; height: 76px; }
.mf-ph img { width: 76px; height: 76px; object-fit: cover; border-radius: 6px; border: 1px solid #ebeef5; }
.mf-ph-del { position: absolute; top: -6px; right: -6px; }
.mf-ph-add { border: 1px dashed #c0c4cc; border-radius: 6px; display: flex; align-items: center; justify-content: center; text-align: center; color: #909399; cursor: pointer; line-height: 1.3; }
.mf-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
</style>
