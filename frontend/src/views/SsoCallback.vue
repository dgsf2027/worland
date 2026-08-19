<template>
  <div class="sso-page">
    <div class="sso-card">
      <div class="brand-row"><span class="brand-mark">曜</span><div><h1>曜石科技 · 租赁板块</h1><p>平台门户单点登录</p></div></div>
      <template v-if="!error">
        <div class="spinner" aria-label="loading" />
        <p class="msg">正在从平台门户免登进入…</p>
      </template>
      <template v-else>
        <el-alert type="error" :closable="false" show-icon :title="error" />
        <p class="msg sub">授权码 60 秒内一次性有效；请回平台重新点击「曜石租赁」卡片，或直接用账号密码登录。</p>
        <div class="actions">
          <el-button type="primary" @click="goPortal">回平台门户</el-button>
          <el-button @click="goLogin">账号密码登录</el-button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { setSession, clearSession } from '@/utils/session'

/**
 * 门户 SSO 落地页（ole-portal-sso 硬约束 9/10/11）：
 * 后端 GET /api/v1/sso/callback 换好本系统会话后 302 到本页，token 放在 URL fragment（不进服务端日志）：
 *   /sso/callback#token=..&name=..&role=..[&created=1]   或   /sso/callback#error=..
 * 本页只做：读 hash → 写 localStorage（与账号密码登录同一份 session）→ 整页强刷 '/'（不用 router.push，让路由守卫重新初始化）。
 */
const PORTAL_URL = 'https://eco.vvaix.com'
const error = ref('')

function parseHash(): Record<string, string> {
  const raw = window.location.hash.replace(/^#/, '')
  const out: Record<string, string> = {}
  for (const kv of raw.split('&')) {
    if (!kv) continue
    const i = kv.indexOf('=')
    const k = decodeURIComponent(i < 0 ? kv : kv.slice(0, i))
    const v = i < 0 ? '' : decodeURIComponent(kv.slice(i + 1).replace(/\+/g, ' '))
    out[k] = v
  }
  return out
}

function goPortal() { window.location.href = PORTAL_URL }
function goLogin() { window.location.href = '/login' }

onMounted(() => {
  const h = parseHash()
  if (h.error) {
    clearSession()
    error.value = h.error
    return
  }
  if (!h.token) {
    error.value = '未收到登录凭证'
    return
  }
  setSession(h.token, h.name || '', h.role || '')
  // 清掉地址栏里的 token 再整页强刷首页（硬约束 11：不用 SPA navigate）
  history.replaceState(null, '', '/sso/callback')
  window.location.href = '/'
})
</script>

<style scoped>
.sso-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #1f2d3d; }
.sso-card { width: 400px; background: #fff; border-radius: 14px; padding: 36px; box-shadow: 0 20px 60px rgba(0,0,0,.35); text-align: center; }
.brand-row { display: flex; gap: 14px; align-items: center; margin-bottom: 26px; text-align: left; }
.brand-mark { width: 48px; height: 48px; border-radius: 12px; background: #409eff; color: #fff; font-size: 24px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.brand-row h1 { margin: 0; font-size: 18px; color: #1f2d3d; }
.brand-row p { margin: 2px 0 0; font-size: 12px; color: #909399; }
.msg { margin: 12px 0 0; color: #606266; font-size: 14px; }
.sub { font-size: 12px; color: #909399; }
.actions { margin-top: 18px; display: flex; gap: 10px; justify-content: center; }
.spinner { width: 32px; height: 32px; margin: 4px auto 0; border: 3px solid #e4e7ed; border-top-color: #409eff; border-radius: 50%; animation: rotate 0.9s linear infinite; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
