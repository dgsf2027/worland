import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearSession } from '@/utils/session'

/**
 * 统一 axios 实例:
 * - baseURL = /api(dev 由 vite proxy 转发到 8082,后端 context-path 也是 /api)
 * - 后端统一返回 R{code,message,data},仅 code===200 时 resolve 出 data
 * - 占位期注入 X-User-* 头(生产由可信网关剥离/重注入,ADR-001 · S0-04)
 */
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
})

/**
 * 免登白名单(ole-portal-sso 硬约束 9):门户 SSO 回调与登录/注册接口不带旧 token、401 也不弹回登录页。
 * 路径按 baseURL 之后的相对路径匹配(/v1/sso/*、/auth/login|register)。
 */
const AUTH_WHITELIST = ['/v1/sso/', '/auth/login', '/auth/register']
function isWhitelisted(url?: string): boolean {
  if (!url) return false
  const path = url.replace(/^https?:\/\/[^/]+/, '').replace(/^\/api(?=\/)/, '')
  return AUTH_WHITELIST.some((p) => path.startsWith(p))
}

request.interceptors.request.use((config) => {
  // 2026-08-19 邀请码注册上线:身份来源 = /api/auth 签发的 Bearer token(后端按 token 派生 X-User-*,不再信任客户端头)
  const token = getToken()
  if (token && !isWhitelisted(config.url)) config.headers['Authorization'] = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  async (response) => {
    if (response.config.responseType === 'blob' && response.data instanceof Blob) {
      // 普通附件(含 JSON 文件)原样返回；兼容以 HTTP 200 返回的业务错误。
      if (response.data.type.includes('application/json')) {
        let payload: any
        try { payload = JSON.parse(await response.data.text()) } catch { /* 普通附件 */ }
        if (payload && typeof payload.code === 'number' && payload.code !== 200 && payload.message) {
          ElMessage.error(payload.message)
          throw new Error(payload.message)
        }
      }
      return response.data
    }
    const res = response.data
    if (res && res.code === 200) {
      return res.data
    }
    const message = res?.message || '请求失败'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  },
  (error) => {
    if (error?.response?.status === 401) {
      // 白名单接口 / SSO 落地页上的 401 由页面自己处理,不清会话、不强跳登录页(硬约束 9)
      if (isWhitelisted(error?.config?.url) || location.pathname.startsWith('/sso/')) return Promise.reject(error)
      clearSession()
      if (!location.pathname.startsWith('/login')) location.href = `/login?redirect=${encodeURIComponent(location.pathname)}`
      return Promise.reject(error)
    }
    ElMessage.error(error?.response?.data?.message || error?.message || '网络异常')
    return Promise.reject(error)
  },
)

export default request
