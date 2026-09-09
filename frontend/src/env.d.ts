/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_PROXY_TARGET?: string
}

/** 构建版本信息:vite.config.ts 的 define 在构建时注入(拿不到时各项为 'unknown') */
declare const __BUILD_INFO__: {
  readonly repoUrl: string
  readonly commit: string
  readonly commitTime: string
  readonly branch: string
  readonly buildTime: string
}
