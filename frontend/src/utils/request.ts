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

request.interceptors.request.use((config) => {
  // 2026-08-19 邀请码注册上线:身份来源 = /api/auth 签发的 Bearer token(后端按 token 派生 X-User-*,不再信任客户端头)
  const token = getToken()
  if (token) config.headers['Authorization'] = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  (response) => {
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
      clearSession()
      if (!location.pathname.startsWith('/login')) location.href = `/login?redirect=${encodeURIComponent(location.pathname)}`
      return Promise.reject(error)
    }
    ElMessage.error(error?.response?.data?.message || error?.message || '网络异常')
    return Promise.reject(error)
  },
)

export default request
