import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import UnoCSS from 'unocss/vite'

// https://vitejs.dev/config/
export default defineConfig({
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
