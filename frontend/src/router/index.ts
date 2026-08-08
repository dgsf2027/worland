import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/quote' },
    { path: '/dashboard', name: 'dashboard', meta: { title: '工作台' }, component: () => import('@/views/Dashboard.vue') },
    { path: '/quote', name: 'quote', meta: { title: '报价测算器' }, component: () => import('@/views/Quote.vue') },
    { path: '/supplier', name: 'supplier', meta: { title: '供应商 · 上游' }, component: () => import('@/views/Supplier.vue') },
    { path: '/customer', name: 'customer', meta: { title: '客户 · CRM' }, component: () => import('@/views/Customer.vue') },
  ],
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 沃朗租赁` : '沃朗科技租赁板块'
})

export default router
