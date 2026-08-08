import axios from 'axios'
import { ElMessage } from 'element-plus'

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
  // 占位期身份头:就绪后换真 SSO token
  const name = localStorage.getItem('rent_user_name') || '老板'
  const role = localStorage.getItem('rent_user_role') || '老板'
  config.headers['X-User-Name'] = encodeURIComponent(name)
  config.headers['X-User-Role'] = encodeURIComponent(role)
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
    ElMessage.error(error?.response?.data?.message || error?.message || '网络异常')
    return Promise.reject(error)
  },
)

export default request
