<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchStocktakes, fetchStocktakeDetail, createStocktake, scanStocktake, closeStocktake,
  type StocktakeItem, type StocktakeDetail, type CloseResult,
} from '@/api/stocktake'

const activeTab = ref('list')
const list = ref<StocktakeItem[]>([])
const total = ref(0)
const loading = ref(false)

const statusTag: Record<string, string> = { 进行中: 'primary', 已闭合: 'success' }
const diffTag: Record<string, string> = { 盘盈: 'success', 盘亏: 'danger', 状态不符: 'warning' }

async function loadList() {
  loading.value = true
  try {
    const res = await fetchStocktakes({ page: 1, size: 50 })
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

// ---- 建单 ----
const createForm = reactive<{ scope: string; remark: string }>({ scope: '全量', remark: '' })
async function submitCreate() {
  const id = await createStocktake({ scope: createForm.scope, remark: createForm.remark })
  ElMessage.success('盘点单已创建(账面带出)')
  openDetail(id)
}

// ---- 详情 + 扫码 ----
const detail = ref<StocktakeDetail | null>(null)
async function openDetail(id: number) {
  detail.value = await fetchStocktakeDetail(id)
  activeTab.value = 'detail'
}

const scanRows = ref<{ assetId?: number; actualStatus: string }[]>([{ actualStatus: '' }])
const statusOptions = ['投放', '在租', '收回待处置', '待转让', '丢失', '报废']
function addScanRow() { scanRows.value.push({ actualStatus: '' }) }
function removeScanRow(i: number) { scanRows.value.splice(i, 1) }
async function submitScan() {
  const diffs = scanRows.value.filter((r) => r.assetId && r.actualStatus)
  if (!diffs.length) { ElMessage.warning('至少录一条差异(设备ID+实盘状态)'); return }
  detail.value = await scanStocktake(detail.value!.stocktake.id, { diffs })
  ElMessage.success('差异已录入')
  scanRows.value = [{ actualStatus: '' }]
}

// ---- 闭合 ----
const closeResult = ref<CloseResult | null>(null)
async function onClose() {
  try {
    await ElMessageBox.confirm('闭合将逐差异生成盘盈亏调整单(走单据对齐台账·留痕),不可撤销。', '确认闭合', {
      confirmButtonText: '闭合并生成调整单', cancelButtonText: '取消', type: 'warning',
    })
    closeResult.value = await closeStocktake(detail.value!.stocktake.id)
    ElMessage.success('已闭合，调整 ' + closeResult.value.adjusted + ' 台')
    openDetail(detail.value!.stocktake.id)
    loadList()
  } catch { /* 取消 */ }
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <div class="sub">扫码盘点账面带出只录差异 → 账实差异生成盘盈亏调整单(走单据不直改台账·§4.24)闭合。盘盈/盘亏/状态不符经调整单对齐台账并留痕。</div>

    <el-tabs v-model="activeTab">
      <!-- ============ 盘点单列表 ============ -->
      <el-tab-pane label="盘点单" name="list">
        <div class="filterbar">
          <el-button type="primary" @click="activeTab = 'create'">+ 发起盘点</el-button>
          <el-button @click="loadList">刷新</el-button>
          <span class="cnt">共 {{ total }} 单</span>
        </div>
        <el-table :data="list" v-loading="loading" stripe border size="small">
          <el-table-column label="单号" min-width="150"><template #default="{ row }"><a class="lnk" @click="openDetail(row.id)">{{ row.no }}</a></template></el-table-column>
          <el-table-column prop="scope" label="范围" width="100" />
          <el-table-column prop="bookCount" label="账面" width="70" align="right" />
          <el-table-column prop="scannedCount" label="已扫" width="70" align="right" />
          <el-table-column prop="diffCount" label="差异" width="70" align="right"><template #default="{ row }"><b :class="row.diffCount ? 'warn' : ''">{{ row.diffCount }}</b></template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="(statusTag[row.status] as any) || 'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
          <el-table-column prop="bizTime" label="盘点时间" width="160" />
        </el-table>
      </el-tab-pane>

      <!-- ============ 发起盘点 ============ -->
      <el-tab-pane label="发起盘点" name="create">
        <div class="panel">
          <h4>发起盘点(账面带出应盘台数)</h4>
          <div class="frm">
            <el-select v-model="createForm.scope" style="width: 140px">
              <el-option v-for="s in ['全量','播种墙','货架','阁楼','配件']" :key="s" :label="s" :value="s" />
            </el-select>
            <el-input v-model="createForm.remark" placeholder="备注" style="width: 240px" />
            <el-button type="primary" @click="submitCreate">发起盘点</el-button>
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ 详情/扫码/闭合 ============ -->
      <el-tab-pane label="扫码盘点/闭合" name="detail">
        <el-empty v-if="!detail" description="从盘点单点单号进入,或先发起盘点" />
        <template v-else>
          <h3 class="dt-title">
            {{ detail.stocktake.no }}
            <el-tag :type="(statusTag[detail.stocktake.status] as any) || 'info'" size="small">{{ detail.stocktake.status }}</el-tag>
            <span class="muted">范围 {{ detail.stocktake.scope }} · 账面 {{ detail.stocktake.bookCount }} · 差异 {{ detail.stocktake.diffCount }}</span>
          </h3>

          <div v-if="detail.stocktake.status === '进行中'" class="panel">
            <h4>扫码录差异(账面一致的不录)</h4>
            <div v-for="(r, i) in scanRows" :key="i" class="scanrow">
              <el-input-number v-model="r.assetId" :min="1" placeholder="设备ID" controls-position="right" style="width: 140px" />
              <el-select v-model="r.actualStatus" placeholder="实盘状态" style="width: 150px">
                <el-option v-for="s in statusOptions" :key="s" :label="s" :value="s" />
              </el-select>
              <el-button link type="danger" size="small" @click="removeScanRow(i)" :disabled="scanRows.length <= 1">删</el-button>
            </div>
            <div style="margin-top:8px">
              <el-button size="small" @click="addScanRow">+ 加一行</el-button>
              <el-button type="primary" size="small" @click="submitScan">提交差异</el-button>
            </div>
            <div class="mini">实盘=丢失 → 盘亏；账面终态(已转让/报废)而实物在 → 盘盈；其余不一致 → 状态不符。</div>
          </div>

          <div class="panel">
            <div class="row-head">
              <h4>差异清单</h4>
              <el-button v-if="detail.stocktake.status === '进行中'" type="warning" size="small" @click="onClose">闭合(生成盘盈亏调整单)</el-button>
            </div>
            <el-table :data="detail.diffs" border size="small">
              <el-table-column prop="serialNo" label="设备" width="130" />
              <el-table-column prop="bookStatus" label="账面状态" width="110" />
              <el-table-column prop="actualStatus" label="实盘状态" width="110" />
              <el-table-column label="差异类型" width="100"><template #default="{ row }"><el-tag :type="(diffTag[row.diffType] as any) || 'info'" size="small">{{ row.diffType }}</el-tag></template></el-table-column>
              <el-table-column label="已调整" width="90"><template #default="{ row }"><el-tag :type="row.adjusted ? 'success' : 'info'" size="small">{{ row.adjusted ? '已闭合' : '待调整' }}</el-tag></template></el-table-column>
              <el-table-column label="调整留痕" width="110"><template #default="{ row }">{{ row.adjustEventId ? 'event#' + row.adjustEventId : '—' }}</template></el-table-column>
              <el-table-column prop="remark" label="说明" min-width="140" show-overflow-tooltip />
            </el-table>
            <el-empty v-if="!detail.diffs.length" description="暂无差异" :image-size="60" />
          </div>

          <div v-if="closeResult && closeResult.stocktakeId === detail.stocktake.id" class="panel result">
            <h4>闭合结果 · 调整 {{ closeResult.adjusted }} 台(盘盈 {{ closeResult.profitCount }} / 盘亏 {{ closeResult.lossCount }} / 状态不符 {{ closeResult.mismatchCount }})</h4>
            <ul class="impact"><li v-for="(x, i) in closeResult.impact" :key="i">{{ x }}</li></ul>
          </div>
        </template>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.page { padding: 4px; }
.sub { color: #666; font-size: 13px; margin-bottom: 10px; }
.filterbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.cnt { color: #999; font-size: 12px; margin-left: auto; }
.lnk { color: #2e6da4; cursor: pointer; font-weight: 600; }
.lnk:hover { text-decoration: underline; }
.mini { color: #999; font-size: 12px; margin-top: 8px; }
.panel { border: 1px solid #eee; border-radius: 8px; padding: 14px; background: #fff; margin-bottom: 12px; }
.panel h4 { margin: 0 0 12px; font-size: 14px; }
.frm { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.scanrow { display: flex; gap: 10px; align-items: center; margin-bottom: 8px; }
.dt-title { font-size: 16px; margin: 4px 0 12px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.muted { color: #999; font-size: 13px; }
.row-head { display: flex; justify-content: space-between; align-items: center; }
.warn { color: #d9534f; }
.result .impact { margin: 6px 0; padding-left: 18px; font-size: 13px; color: #555; }
</style>
