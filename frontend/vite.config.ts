import { execSync } from 'node:child_process'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import UnoCSS from 'unocss/vite'

// ---- 构建版本信息(侧栏底部「版本 提交号 · 提交时间」,点击跳 GitHub 对应提交) ----
// 构建时读一次写死进页面:优先读环境变量(服务器 Docker 构建拿不到 .git,由 compose build args
// 透传 GIT_COMMIT / GIT_COMMIT_TIME / GIT_BRANCH,见 deploy/frontend.Dockerfile 与上线手册);
// 本机开发直接问 git;两边都拿不到就显示 unknown,不报错不阻塞构建。
const REPO_URL = 'https://github.com/dgsf2027/worland'
function git(args: string): string {
  try {
    return execSync(`git ${args}`, { stdio: ['ignore', 'pipe', 'ignore'], encoding: 'utf8' }).trim()
  } catch {
    return ''
  }
}
function fromEnvOrGit(envName: string, gitArgs: string): string {
  return (process.env[envName] || '').trim() || git(gitArgs) || 'unknown'
}
const buildInfo = {
  repoUrl: REPO_URL,
  commit: fromEnvOrGit('GIT_COMMIT', 'rev-parse HEAD'),
  commitTime: fromEnvOrGit('GIT_COMMIT_TIME', 'log -1 --format=%cI'),
  branch: fromEnvOrGit('GIT_BRANCH', 'rev-parse --abbrev-ref HEAD'),
  buildTime: new Date().toISOString(),
}

// https://vitejs.dev/config/
export default defineConfig({
  define: { __BUILD_INFO__: JSON.stringify(buildInfo) },
  plugins: [vue(), UnoCSS()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5175,
    proxy: {
      // /api → 后端 8082(后端 context-path 就是 /api,前缀原样转发)
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8082',
        changeOrigin: true,
      },
    },
  },
})
