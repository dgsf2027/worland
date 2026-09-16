import request from '@/utils/request'
import { getToken, setSession } from '@/utils/session'

export interface SessionProfile {
  accountId: number
  userId: number
  displayName: string
  role: string
}

/** 页面切换、回到窗口和授权后同步实际权限；不使用登录时缓存的旧角色。 */
export async function refreshSession(): Promise<SessionProfile | undefined> {
  const token = getToken()
  if (!token) return
  const profile: SessionProfile = await request.get('/auth/me')
  // 旧请求返回时用户可能已退出或换号，不得恢复旧会话。
  if (getToken() === token) setSession(token, profile.displayName, profile.role)
  return profile
}
