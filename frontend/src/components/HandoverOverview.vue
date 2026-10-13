<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Workbench } from '@/api/workbench'

const props = defineProps<{ data: Workbench | null; loading: boolean; failed: boolean; updatedAt: string }>()
const expanded = ref(true)
const selected = ref(0)
const stages = [
  { title: '客户与需求', short: '找到需求', owner: '业务', output: '客户档案 · 商机 · 跟进记录', description: '从客户需求出发，明确使用场景、设备需求与下一次跟进。', checks: ['客户联系人、需求和商机进展', '最近一次沟通结论与下一次跟进时间'], links: [{ label: '客户 · CRM', to: '/customer' }] },
  { title: '选品与测算', short: '算清方案', owner: '业务 / 供应链', output: '供应商考察 · 报价方案', description: '核对供应商和设备方案，测算月租、客户节省与税后回报。', checks: ['供应商考察结果与供应条件', '报价参数、租期和测算依据'], links: [{ label: '报价测算器', to: '/quote' }, { label: '供应商考察', to: '/supplier-inspection' }, { label: '供应商档案', to: '/supplier' }] },
  { title: '签约与计划', short: '先签合同', owner: '业务', output: '合同 · 设备清单 · 租金计划', description: '确定客户、租期、月租与押金，形成合同和分期租金计划。', checks: ['已签合同、关联设备与关键约定', '起止日期、租金计划和押金记录'], links: [{ label: '合同 · 租金计划', to: '/contract' }] },
  { title: '采购与投放', short: '再采再交付', owner: '供应链', output: '采购入库 · 应付 · 交付记录', description: '按照已签合同采购，逐件建档，再确认投放与交付。', checks: ['采购单、付款阶段与供应商应付', '设备编号、当前状态及交付凭据'], links: [{ label: '采购入库 · 应付', to: '/purchase' }, { label: '设备 · 租赁台账', to: '/asset' }] },
  { title: '收租与服务', short: '持续履约', owner: '财务 / 供应链', output: '收租单 · 核销 · 服务工单', description: '按期收租和核销，跟进逾期，同时处理报修与损坏赔偿。', checks: ['待收款、逾期处置和收款凭据', '未完维保、损坏赔偿与处理进展'], links: [{ label: '收租 · 逾期', to: '/rent' }, { label: '服务 · 维保与赔偿', to: '/service' }] },
  { title: '到期与处置', short: '完成闭环', owner: '业务 / 供应链', output: '续约跟进 · 转让 · 收回处置', description: '提前跟进合同到期，确认续约、转让或收回，并完成后续处置。', checks: ['到期合同、客户意向和跟进人', '转让或收回记录、处置金额与凭据'], links: [{ label: '转让 · 处置', to: '/transfer' }, { label: '到期合同', to: '/contract' }] },
]
const groups = [
  { title: '实物与仓库', note: '租赁设备逐台追踪；仓库物品按数量管理。', links: [{ label: '设备台账', to: '/asset' }, { label: '资产管理 · 出租 / 出入库', to: '/inventory' }, { label: '服务 · 维保 / 赔偿', to: '/service' }] },
  { title: '财务与经营', note: '凭证、现金流和月报贯穿整个履约过程。', links: [{ label: '凭证 · 双账 / 折旧', to: '/voucher' }, { label: '现金流 / 分配', to: '/cashflow' }, { label: '月度报表', to: '/monthly' }] },
  { title: '协作与改进', note: '落实待办和审批，核对账号角色与交接归属。', links: [{ label: '任务 · 审批', to: '/task' }, { label: '花名册 · 权限 / 提成', to: '/roster' }, { label: 'BI · PDCA', to: '/bi-pdca' }, { label: '导入中心', to: '/import' }] },
]
const stage = computed(() => stages[selected.value])
const kpi = computed(() => props.data?.kpi)
const available = (v?: number) => v != null && Number.isFinite(v)
const percent = (v?: number) => available(v) ? `${(v! * 100).toFixed(1)}%` : '—'
const money = (v?: number) => available(v) ? `¥${v!.toLocaleString('zh-CN', { maximumFractionDigits: 0 })}` : '—'
const rentedWidth = computed(() => available(kpi.value?.rentedRate) ? Math.min(100, Math.max(0, kpi.value!.rentedRate! * 100)) : 0)
const taskCount = computed(() => props.data ? props.data.myTaskCount : '—')
const alertCount = computed(() => props.data ? props.data.redPoints.length : '—')
const metrics = computed(() => [
  { label: '应收合计', value: money(kpi.value?.receivableTotal), note: '收款情况见现金流', to: '/cashflow' },
  { label: '本月分配', value: money(kpi.value?.monthDistribution), note: kpi.value?.period || '当前会计月份', to: '/cashflow' },
  { label: '加权回报', value: percent(kpi.value?.weightedReturn), note: '税后 IRR', to: '/cashflow' },
])
</script>

<template>
  <section class="handover" aria-labelledby="handover-title" :aria-busy="loading">
    <header class="overview-header">
      <div>
        <h2 id="handover-title">业务全景与交接</h2>
        <p>从一份客户需求，到一台设备的完整履约。沿着业务主线，找到资料、责任环节和下一步。</p>
      </div>
      <button class="collapse-control" type="button" :aria-expanded="expanded" aria-controls="handover-content" @click="expanded = !expanded">{{ expanded ? '收起面板' : '展开面板' }} <span aria-hidden="true">{{ expanded ? '−' : '+' }}</span></button>
    </header>

    <div v-show="expanded" id="handover-content">
      <div class="overview-layout">
        <div class="business-map">
          <div class="section-heading"><h3>一条业务主线</h3><span>点击环节查看交接重点</span></div>
          <ol class="flow">
            <li v-for="(item, index) in stages" :key="item.title">
              <button type="button" :class="['flow-step', { selected: selected === index }]" :aria-pressed="selected === index" aria-controls="handover-stage" @click="selected = index">
                <span class="step-number">{{ String(index + 1).padStart(2, '0') }}</span>
                <strong>{{ item.title }}</strong><span class="step-short">{{ item.short }}</span>
              </button>
              <span v-if="index < stages.length - 1" class="connector" aria-hidden="true">→</span>
            </li>
          </ol>
          <div id="handover-stage" class="stage-detail" aria-live="polite" aria-atomic="true">
            <div class="stage-description"><div class="stage-heading"><h4>{{ stage.title }}</h4><span>{{ stage.owner }}</span></div><p>{{ stage.description }}</p><div class="stage-output"><span>关键资料</span><strong>{{ stage.output }}</strong></div></div>
            <div class="stage-handoff"><h5>交接时核对</h5><ul><li v-for="check in stage.checks" :key="check">{{ check }}</li></ul><div class="stage-links"><RouterLink v-for="link in stage.links" :key="link.to" :to="link.to">{{ link.label }} <span aria-hidden="true">↗</span></RouterLink></div></div>
          </div>
          <div class="support-map">
            <div v-for="group in groups" :key="group.title" class="support-row"><div><h4>{{ group.title }}</h4><p>{{ group.note }}</p></div><nav :aria-label="group.title"><RouterLink v-for="link in group.links" :key="link.to" :to="link.to">{{ link.label }} <span aria-hidden="true">↗</span></RouterLink></nav></div>
          </div>
        </div>

        <aside class="live-overview" aria-label="当前经营快照">
          <div class="section-heading"><h3>当前经营快照</h3><span class="data-indicator" :class="{ stale: failed }">{{ loading ? '更新中' : failed ? '更新失败' : data ? '已更新' : '未加载' }}</span></div>
          <p class="data-scope">按当前账号权限展示 · 未提供的数据以 — 表示</p>
          <RouterLink to="/asset" class="occupancy metric-link"><div class="occupancy-heading"><span>设备在租率</span><strong>{{ percent(kpi?.rentedRate) }}</strong></div><div class="occupancy-track" aria-hidden="true"><span :style="{ width: `${rentedWidth}%` }"></span></div><p v-if="available(kpi?.rentedCount) && available(kpi?.activeAssetCount)">在租 <b>{{ kpi?.rentedCount }}</b> / 有效设备 <b>{{ kpi?.activeAssetCount }}</b> 台</p><p v-else>在租设备 / 有效设备 · 暂无可见数据</p></RouterLink>
          <dl class="metric-list"><div v-for="metric in metrics" :key="metric.label"><dt><RouterLink :to="metric.to">{{ metric.label }} <span aria-hidden="true">↗</span></RouterLink><small>{{ metric.note }}</small></dt><dd>{{ metric.value }}</dd></div></dl>
          <div class="handoff-priority"><h4>接手先看这里</h4><a href="#workbench-alerts"><span>提醒类别<small>逐类核对，可能涉及同一事项</small></span><strong>{{ alertCount }} <small>类</small></strong></a><a href="#workbench-tasks"><span>我的待办<small>核对处理人、期限与进展</small></span><strong>{{ taskCount }} <small>项</small></strong></a></div>
          <p class="snapshot-time">{{ failed ? (data ? '保留上次成功数据，请刷新重试。' : '经营数据加载失败，请刷新重试。') : updatedAt ? `更新于 ${updatedAt}` : '业务地图可直接浏览，经营数据加载后显示。' }}</p>
        </aside>
      </div>
      <footer class="handoff-footer"><strong>交接顺序</strong><span>先核对提醒与待办</span><span aria-hidden="true">→</span><span>再沿主线核对单据与设备</span><span aria-hidden="true">→</span><span>最后在任务中心落实后续跟进</span><RouterLink to="/task">进入任务中心 <span aria-hidden="true">↗</span></RouterLink></footer>
    </div>
  </section>
</template>

<style scoped>
.handover { --overview-ink: var(--brand-obsidian); --overview-muted: #66625b; --overview-rule: #e6e0d5; background: #fff; border: 1px solid var(--overview-rule); border-radius: 12px; margin: 0 0 24px; color: var(--overview-ink); overflow: hidden; }
.handover * { box-sizing: border-box; }
.handover ::selection { background: var(--brand-gold-bright); color: var(--brand-obsidian); }
.handover a { text-decoration: none; color: inherit; text-underline-offset: 4px; }
.handover a:hover { text-decoration: underline; }
.handover :is(a, button):focus-visible { outline: 2px solid #85602e; outline-offset: 4px; }
.overview-header { padding: 26px 28px 22px; display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; border-bottom: 1px solid var(--overview-rule); }
h2, h3, h4, h5, p { margin: 0; }
.overview-header h2 { font-size: 24px; font-weight: 650; line-height: 1.4; }
.overview-header p { margin-top: 8px; color: var(--overview-muted); font-size: 13px; line-height: 1.7; max-width: 65ch; }
.collapse-control { background: #fff; border: 1px solid #cfc6b7; padding: 8px 12px; border-radius: 6px; font: inherit; font-size: 12px; color: #514b41; cursor: pointer; white-space: nowrap; }
.collapse-control:hover { background: #f7f4ef; border-color: var(--brand-gold); }
.collapse-control span { margin-left: 12px; font-size: 16px; }
.overview-layout { display: grid; grid-template-columns: minmax(0, 1fr) 280px; }
.business-map { padding: 24px 28px 14px; min-width: 0; }
.section-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.section-heading h3 { font-size: 15px; font-weight: 650; }
.section-heading > span { font-size: 11px; color: var(--overview-muted); }
.flow { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); list-style: none; padding: 0; margin: 22px 0 18px; }
.flow li { position: relative; min-width: 0; padding-right: 16px; }
.flow li:last-child { padding-right: 0; }
.flow-step { display: flex; flex-direction: column; align-items: flex-start; gap: 7px; width: 100%; min-height: 110px; background: transparent; border: 0; border-top: 2px solid #dfd8cb; padding: 10px 0 8px; cursor: pointer; color: #5a554d; font: inherit; text-align: left; }
.flow-step:hover { border-color: var(--brand-gold); color: var(--overview-ink); }
.flow-step.selected { border-color: var(--brand-obsidian); color: var(--brand-obsidian); }
.step-number { font-size: 12px; font-weight: 700; color: #756c5e; font-variant-numeric: tabular-nums; }
.selected .step-number { color: #795622; }
.flow-step strong { font-size: 13px; line-height: 1.4; }
.step-short { font-size: 11px; color: var(--overview-muted); }
.connector { position: absolute; right: 3px; top: 44px; color: #958674; font-size: 13px; }
.stage-detail { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; padding: 22px; background: #f8f5ee; min-height: 176px; border-radius: 8px; }
.stage-heading { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; }
.stage-heading h4 { font-size: 18px; }
.stage-heading > span { font-size: 11px; color: #685a45; }
.stage-description > p { margin: 10px 0 18px; font-size: 12px; line-height: 1.8; color: #615a50; }
.stage-output { display: flex; flex-direction: column; gap: 6px; font-size: 12px; line-height: 1.7; }
.stage-output span { color: #71634f; font-size: 11px; }
.stage-output strong { font-weight: 500; }
.stage-handoff h5 { font-size: 12px; margin: 0 0 8px; }
.stage-handoff ul { padding: 0 0 0 16px; margin: 0; color: #615a50; font-size: 12px; line-height: 1.85; }
.stage-links { display: flex; flex-wrap: wrap; gap: 10px 16px; margin-top: 16px; }
.stage-links a { font-size: 12px; font-weight: 600; color: #785422; }
.support-map { margin-top: 24px; }
.support-row { display: grid; grid-template-columns: minmax(170px, .9fr) 1.2fr; gap: 20px; padding: 16px 0; border-top: 1px solid var(--overview-rule); }
.support-row h4 { font-size: 13px; }
.support-row p { margin-top: 5px; color: var(--overview-muted); font-size: 11px; line-height: 1.8; }
.support-row nav { display: flex; gap: 10px 18px; flex-wrap: wrap; align-content: center; }
.support-row a { font-size: 12px; line-height: 1.6; }
.support-row a span, .stage-links a span { color: #856333; }
.live-overview { padding: 24px; background: #fcfbf8; border-left: 1px solid var(--overview-rule); min-width: 0; }
.data-indicator { display: inline-flex; align-items: center; gap: 5px; }
.data-indicator::before { content: ''; width: 5px; height: 5px; border-radius: 50%; background: #847252; }
.data-indicator.stale { color: #9d412b; }
.data-scope { font-size: 11px; color: var(--overview-muted); line-height: 1.7; margin: 10px 0 24px; }
.occupancy { display: block; }
.occupancy-heading { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; }
.occupancy-heading > span { font-size: 12px; }
.occupancy-heading strong { font-size: 24px; font-weight: 650; font-variant-numeric: tabular-nums; }
.occupancy-track { height: 7px; background: #e7e0d4; border-radius: 2px; overflow: hidden; margin-top: 12px; }
.occupancy-track span { height: 100%; display: block; background: var(--brand-gold); }
.occupancy p { color: var(--overview-muted); font-size: 11px; margin-top: 9px; line-height: 1.6; }
.metric-list { margin: 20px 0 24px; }
.metric-list > div { display: flex; justify-content: space-between; gap: 10px; border-bottom: 1px solid var(--overview-rule); padding: 15px 0; }
.metric-list dt { font-size: 12px; }
.metric-list dt > small { display: block; font-size: 11px; margin-top: 4px; color: var(--overview-muted); }
.metric-list dd { margin: 0; font-size: 19px; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; text-align: right; }
.handoff-priority h4 { font-size: 13px; margin-bottom: 10px; }
.handoff-priority a { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 10px 0; font-size: 12px; }
.handoff-priority a > span > small { display: block; margin-top: 5px; font-size: 11px; color: var(--overview-muted); line-height: 1.6; }
.handoff-priority strong { font-size: 22px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.handoff-priority strong small { font-size: 11px; font-weight: 400; }
.snapshot-time { font-size: 11px; color: var(--overview-muted); line-height: 1.7; margin-top: 18px; }
.handoff-footer { border-top: 1px solid var(--overview-rule); background: #f8f5ee; padding: 15px 28px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; font-size: 11px; color: #605744; line-height: 1.7; }
.handoff-footer strong { color: var(--brand-obsidian); margin-right: 8px; }
.handoff-footer a { margin-left: auto; color: #785422; font-weight: 600; }
@media (max-width: 1280px) { .overview-layout { grid-template-columns: minmax(0, 1fr) 250px; } .business-map { padding: 22px 20px 14px; } .live-overview { padding: 22px 18px; } .flow { grid-template-columns: repeat(3, minmax(0, 1fr)); row-gap: 8px; } .flow-step { min-height: 94px; } .flow li:nth-child(3) .connector { display: none; } .stage-detail { gap: 18px; padding: 18px; } .support-row { grid-template-columns: 1fr; gap: 10px; } }
@media (max-width: 960px) { .overview-layout { grid-template-columns: minmax(0, 1fr); } .live-overview { border-left: 0; border-top: 1px solid var(--overview-rule); } .metric-list { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; } .metric-list > div { display: block; } .metric-list dd { margin-top: 10px; text-align: left; } .data-scope { margin-bottom: 16px; } .handoff-priority { display: flex; gap: 24px; flex-wrap: wrap; align-items: center; } .handoff-priority h4 { margin: 0; } .handoff-priority a { gap: 20px; } }
@media (max-width: 580px) { .overview-header { padding: 20px 16px; gap: 12px; flex-wrap: wrap; } .overview-header h2 { font-size: 22px; } .overview-header p { font-size: 12px; } .business-map { padding: 20px 16px 10px; } .section-heading > span { font-size: 11px; } .stage-detail { grid-template-columns: 1fr; min-height: 0; } .stage-handoff { border-top: 1px solid var(--overview-rule); padding-top: 16px; } .flow { margin-top: 16px; } .flow-step strong { font-size: 12px; } .metric-list { gap: 12px; } .metric-list dd { font-size: 17px; } .handoff-footer { padding: 16px; gap: 8px; } .handoff-footer strong { flex-basis: 100%; } .handoff-footer a { flex-basis: 100%; margin: 6px 0 0; } }
</style>
