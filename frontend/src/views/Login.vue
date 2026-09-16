<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand-row"><span class="brand-mark">曜</span><div><h1>{{ mode === 'recovery' ? '激活恢复管理员' : '曜石科技 · 租赁板块' }}</h1><p>{{ mode === 'recovery' ? '输入一次性恢复码，设置正式登录密码' : '内部使用 · 凭邀请码注册后登录' }}</p></div></div>
      <el-alert v-if="mode === 'recovery'" class="recovery-tip" type="warning" :closable="false" title="临时密码没有业务访问权限。恢复码由建号命令显示，30 分钟内有效；激活后临时密码和恢复码同时失效。" />
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="submit">
        <el-form-item prop="username"><el-input v-model="form.username" :disabled="mode === 'recovery'" placeholder="账号（3-32 位字母数字）" autocomplete="username" /></el-form-item>
        <el-form-item prop="password"><el-input v-model="form.password" type="password" show-password :placeholder="mode === 'register' ? '设置密码（至少 8 位）' : mode === 'recovery' ? '临时密码' : '密码'" :autocomplete="mode === 'register' ? 'new-password' : 'current-password'" /></el-form-item>
        <template v-if="mode === 'recovery'">
          <el-form-item prop="recoveryCode"><el-input v-model="form.recoveryCode" placeholder="一次性恢复码" autocomplete="off" maxlength="128" /></el-form-item>
          <el-form-item prop="newPassword"><el-input v-model="form.newPassword" type="password" show-password placeholder="新密码（至少 12 位，含字母和数字）" autocomplete="new-password" maxlength="128" /></el-form-item>
          <el-form-item prop="confirmPassword"><el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" autocomplete="new-password" maxlength="128" /></el-form-item>
        </template>
        <template v-if="mode === 'register'">
          <el-form-item prop="displayName"><el-input v-model="form.displayName" placeholder="姓名（显示名，如：小洪）" maxlength="30" /></el-form-item>
          <el-form-item prop="inviteCode"><el-input v-model="form.inviteCode" placeholder="邀请码（内部使用，请向管理员索取）" autocomplete="off" maxlength="64" /></el-form-item>
        </template>
        <el-button type="primary" class="login-btn" :loading="loading" @click="submit">{{ mode === 'login' ? '登 录' : mode === 'recovery' ? '设置密码并激活' : '注 册' }}</el-button>
      </el-form>
      <div class="hint">
        <a v-if="mode === 'login'" href="#" @click.prevent="switchMode('register')">没有账号？凭邀请码注册</a>
        <a v-else href="#" @click.prevent="switchMode('login')">已有账号？去登录</a>
      </div>
      <template v-if="mode !== 'recovery'">
        <el-divider class="sso-divider"><span class="sso-or">或</span></el-divider>
        <el-button class="sso-btn" plain @click="goPortal">用平台账号登录（生态管理平台）</el-button>
        <p class="sso-tip">在平台工作台点「曜石租赁」卡片即可免登直达</p>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { setSession } from '@/utils/session'

// 登录 / 注册双模式（2026-08-19 邀请码注册 · 全系统统一契约，服务端 /api/auth/register 校验邀请码）
const router = useRouter()
const route = useRoute()
const formRef = ref()
const loading = ref(false)
const mode = ref<'login' | 'register' | 'recovery'>('login')
const form = reactive({ username: '', password: '', displayName: '', inviteCode: '', recoveryCode: '', newPassword: '', confirmPassword: '' })
const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  inviteCode: [{ required: true, message: '请输入邀请码', trigger: 'blur' }],
  recoveryCode: [{ required: true, message: '请输入建号命令显示的恢复码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请设置新密码', trigger: 'blur' }, { min: 12, max: 128, message: '新密码需 12–128 位', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请再次输入新密码', trigger: 'blur' }],
}
function switchMode(m: 'login' | 'register') {
  mode.value = m
  form.recoveryCode = ''; form.newPassword = ''; form.confirmPassword = ''
  formRef.value?.clearValidate()
}
// 门户 SSO 入口(本系统属生态管理平台):去平台登录后从工作台卡片免登进入(后端 /api/v1/sso/callback 接码)
const PORTAL_URL = 'https://eco.vvaix.com'
function goPortal() { window.location.href = PORTAL_URL }
async function submit() {
  if (loading.value) return
  if (!await formRef.value.validate().catch(() => false)) return
  if (mode.value === 'recovery' && form.newPassword !== form.confirmPassword) {
    ElMessage.warning('两次新密码不一致，请重新输入')
    return
  }
  loading.value = true
  try {
    const data: any = mode.value === 'recovery'
      ? await request.post('/auth/complete-recovery', { username: form.username.trim(), password: form.password, recoveryCode: form.recoveryCode.trim(), newPassword: form.newPassword })
      : mode.value === 'register'
      ? await request.post('/auth/register', { username: form.username.trim(), password: form.password, displayName: form.displayName.trim(), inviteCode: form.inviteCode.trim() })
      : await request.post('/auth/login', { username: form.username.trim(), password: form.password })
    if (data.requiresPasswordChange) {
      mode.value = 'recovery'
      return
    }
    setSession(data.token, data.displayName, data.role)
    form.password = ''; form.recoveryCode = ''; form.newPassword = ''; form.confirmPassword = ''
    ElMessage.success((mode.value === 'register' ? '注册成功，欢迎 ' : '欢迎回来，') + data.displayName)
    router.replace(mode.value === 'recovery' ? '/roster' : (route.query.redirect as string) || '/workbench')
  } catch { /* 接口错误由拦截器显示，保留输入供重试 */
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #1f2d3d; }
.login-card { width: 400px; background: #fff; border-radius: 14px; padding: 36px 36px 28px; box-shadow: 0 20px 60px rgba(0,0,0,.35); }
.recovery-tip { margin-bottom: 16px; }
.brand-row { display: flex; gap: 14px; align-items: center; margin-bottom: 26px; }
.brand-mark { width: 48px; height: 48px; border-radius: 12px; background: #409eff; color: #fff; font-size: 24px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.brand-row h1 { margin: 0; font-size: 18px; color: #1f2d3d; }
.brand-row p { margin: 2px 0 0; font-size: 12px; color: #909399; }
.login-btn { width: 100%; height: 42px; margin-top: 4px; }
.hint { margin-top: 16px; text-align: center; font-size: 12px; }
.hint a { color: #409eff; text-decoration: none; }
.sso-divider { margin: 18px 0 12px; }
.sso-or { font-size: 12px; color: #c0c4cc; }
.sso-btn { width: 100%; height: 40px; }
.sso-tip { margin: 8px 0 0; text-align: center; font-size: 12px; color: #909399; }
</style>
