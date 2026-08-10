import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/quote' },
    { path: '/dashboard', name: 'dashboard', meta: { title: '工作台' }, component: () => import('@/views/Dashboard.vue') },
    { path: '/quote', name: 'quote', meta: { title: '报价测算器' }, component: () => import('@/views/Quote.vue') },
    { path: '/supplier', name: 'supplier', meta: { title: '供应商 · 上游' }, component: () => import('@/views/Supplier.vue') },
    { path: '/customer', name: 'customer', meta: { title: '客户 · CRM' }, component: () => import('@/views/Customer.vue') },
    { path: '/asset', name: 'asset', meta: { title: '设备 · 逐件台账' }, component: () => import('@/views/Asset.vue') },
    { path: '/contract', name: 'contract', meta: { title: '合同 · 签约与租金计划' }, component: () => import('@/views/Contract.vue') },
    { path: '/rent', name: 'rent', meta: { title: '收租 · 收租单与逾期' }, component: () => import('@/views/Rent.vue') },
    { path: '/transfer', name: 'transfer', meta: { title: '转让·处置 · 到期转让/复投飞轮' }, component: () => import('@/views/Transfer.vue') },
    { path: '/maintenance', name: 'maintenance', meta: { title: '维保工单 · 报修/派工/回写' }, component: () => import('@/views/Maintenance.vue') },
    { path: '/stocktake', name: 'stocktake', meta: { title: '盘点 · 扫码差异/盘盈亏调整' }, component: () => import('@/views/Stocktake.vue') },
    { path: '/voucher', name: 'voucher', meta: { title: '凭证中心 · 双账/折旧/500万红线' }, component: () => import('@/views/Voucher.vue') },
    { path: '/cashflow', name: 'cashflow', meta: { title: '现金流/分配驾驶舱 · 兑付缺口/回报四源' }, component: () => import('@/views/Cashflow.vue') },
    { path: '/monthly', name: 'monthly', meta: { title: '月度报表 · 六件套/七节报告/财务日历' }, component: () => import('@/views/Monthly.vue') },
  ],
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 沃朗租赁` : '沃朗科技租赁板块'
})

export default router
