<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isLoggedIn } from '@/utils/session'
import {
  fetchPublicScan, fetchScan,
  type PublicScanView, type ScanView, type InvRental, type MovementResult,
} from '@/api/inventory'
import MovementForm from '@/components/inventory/MovementForm.vue'

/** 扫码手机页:未登录只见企业信息与名称规格;登录后可看库存与出租信息并登记出库/归还。 */
const route = useRoute()
const router = useRouter()
const token = computed(() => String(route.params.token || ''))
const loggedIn = isLoggedIn()

const pub = ref<PublicScanView | null>(null)
const view = ref<ScanView | null>(null)
const loading = ref(false)
const error = ref('')

const action = ref<{ mode: 'out' | 'return'; rental: InvRental } | null>(null)
const lastResult = ref<{ mode: 'out' | 'return'; res: MovementResult } | null>(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (loggedIn) {
      view.value = await fetchScan(token.value)
    } else {
      pub.value = await fetchPublicScan(token.value)
    }
  } catch (e: any) {
    error.value = e?.response?.data?.message || e?.message || '二维码无效或资产已删除'
  } finally {
    loading.value = false
  }
}

function toLogin() {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

function start(mode: 'out' | 'return', rental: InvRental) {
  lastResult.value = null
  action.value = { mode, rental }
  window.scrollTo(0, 0)
}
async function onDone(res: MovementResult) {
  if (action.value) lastResult.value = { mode: action.value.mode, res }
  action.value = null
  await load()
}

const company = computed(() => view.value?.company || pub.value?.company)
const money = (v?: number) => '¥' + Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

onMounted(load)
</script>

<template>
  <div class="scan">
    <header v-if="company" class="co">
      <div class="co-name">{{ company.companyName }}</div>
      <div v-if="company.phone" class="co-line">服务电话：<a :href="`tel:${company.phone}`">{{ company.phone }}</a></div>
      <div v-if="company.address" class="co-line">{{ company.address }}</div>
      <div v-if="company.website" class="co-line">{{ company.website }}</div>
      <div v-if="company.notice" class="co-notice">{{ company.notice }}</div>
    </header>

    <div v-if="loading && !pub && !view" class="card center">加载中…</div>
    <div v-else-if="error" class="card center bad">{{ error }}</div>

    <!-- 未登录 -->
    <template v-else-if="pub">
      <div class="card">
        <div class="name">{{ pub.name }}</div>
        <div v-if="pub.spec" class="line">规格：{{ pub.spec }}</div>
        <div class="line">编号：{{ pub.code }}</div>
        <div v-if="pub.category" class="line">类别：{{ pub.category }}</div>
      </div>
      <div class="card">
        <div class="tip">本公司员工登录后可查看库存与出租信息，并登记出库、归还。</div>
        <el-button type="primary" class="full mt8" @click="toLogin">员工登录</el-button>
      </div>
    </template>

    <!-- 已登录 -->
    <template v-else-if="view">
      <div class="card">
        <div class="name">{{ view.item.name }}</div>
        <div v-if="view.item.spec" class="line">规格：{{ view.item.spec }}</div>
        <div class="line">编号：{{ view.item.code }}</div>
        <div v-if="view.item.location" class="line">存放位置：{{ view.item.location }}</div>
        <div class="qty">
          <div><b class="ok">{{ view.item.stockQty }}</b><span>库存</span></div>
          <div><b class="warn">{{ view.item.reservedQty }}</b><span>已预订</span></div>
          <div><b class="blue">{{ view.item.rentedQty }}</b><span>出租中</span></div>
          <div><b class="warn">{{ view.item.repairQty }}</b><span>维修中</span></div>
          <div><b class="grey">{{ view.item.scrappedQty }}</b><span>已报废</span></div>
        </div>
      </div>

      <div v-if="lastResult" class="card done">
        ✅ {{ lastResult.mode === 'out' ? '出库' : '归还' }}已登记
        <template v-if="lastResult.mode === 'return' && lastResult.res.compensationTotal > 0">，赔偿 {{ money(lastResult.res.compensationTotal) }}</template>
        <span class="tip">（出租单状态：{{ lastResult.res.rentalStatus }}）</span>
      </div>

      <div v-if="action" class="card">
        <div class="title">{{ action.mode === 'out' ? '出库登记' : '归还登记' }}</div>
        <MovementForm :mode="action.mode" :rental="action.rental" :prices="view.prices" @done="onDone" @cancel="action = null" />
      </div>

      <template v-else>
        <div class="title pad">出租单（待出库 / 在外）</div>
        <div v-if="!view.activeRentals.length" class="card center tip">暂无预订或出租中的出租单。新建出租请在电脑端「资产管理 › 出租管理」。</div>
        <div v-for="r in view.activeRentals" :key="r.id" class="card">
          <div class="r-head">
            <b>{{ r.customerName }}</b>
            <span :class="['r-st', r.status === '出租中' ? 'blue' : 'warn']">{{ r.status }}</span>
          </div>
          <div class="line tip">{{ r.rentalNo }}</div>
          <div v-if="r.installAddress" class="line">安装地址：{{ r.installAddress }}</div>
          <div v-if="r.contact || r.phone" class="line">联系人：{{ r.contact }} <a v-if="r.phone" :href="`tel:${r.phone}`">{{ r.phone }}</a></div>
          <div class="line">出租 {{ r.qty }} · 已出库 {{ r.outQty }} · 已归还 {{ r.returnedQty }}</div>
          <div class="line">{{ r.startDate }} → 预计归还 {{ r.expectedReturnDate }}
            <span v-if="r.overdueDays" class="bad">（逾期 {{ r.overdueDays }} 天）</span>
          </div>
          <div v-if="view.canOperate" class="r-actions">
            <el-button type="primary" :disabled="!r.pendingOutQty" @click="start('out', r)">出库（{{ r.pendingOutQty }}）</el-button>
            <el-button type="success" :disabled="!r.onSiteQty" @click="start('return', r)">归还（{{ r.onSiteQty }}）</el-button>
          </div>
        </div>
        <div v-if="!view.canOperate" class="card tip">当前角色只能查看；出库/归还需 老板/供应链/业务 角色。</div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.scan { max-width: 560px; margin: 0 auto; padding: 12px 16px 32px; background: #f5f7fa; min-height: 100vh; box-sizing: border-box; }
.co { background: #1f2d3d; color: #fff; border-radius: 10px; padding: 14px 16px; margin-bottom: 12px; }
.co-name { font-size: 18px; font-weight: 700; }
.co-line { font-size: 13px; opacity: .85; margin-top: 4px; }
.co-line a { color: #fff; }
.co-notice { font-size: 12px; margin-top: 8px; padding-top: 8px; border-top: 1px solid rgba(255,255,255,.2); opacity: .8; }
.card { background: #fff; border-radius: 10px; padding: 14px 16px; margin-bottom: 12px; box-shadow: 0 1px 2px rgba(0,0,0,.04); }
.center { text-align: center; }
.name { font-size: 20px; font-weight: 700; margin-bottom: 6px; }
.title { font-size: 16px; font-weight: 700; margin-bottom: 8px; }
.pad { padding: 0 4px; }
.line { font-size: 14px; line-height: 1.8; word-break: break-all; }
.tip { color: #909399; font-size: 13px; }
.bad { color: #e0533d; }
.ok { color: #2f9e44; }
.warn { color: #e6a23c; }
.blue { color: #409eff; }
.grey { color: #909399; }
.mt8 { margin-top: 8px; }
.full { width: 100%; }
.qty { display: grid; grid-template-columns: repeat(5, 1fr); margin-top: 10px; text-align: center; border-top: 1px solid #ebeef5; padding-top: 10px; }
.qty b { display: block; font-size: 20px; }
.qty span { font-size: 12px; color: #909399; }
.done { background: #f0f9eb; color: #2f9e44; }
.r-head { display: flex; justify-content: space-between; align-items: center; font-size: 15px; }
.r-st { font-size: 13px; }
.r-actions { display: flex; gap: 8px; margin-top: 10px; }
.r-actions .el-button { flex: 1; margin: 0; }
</style>
