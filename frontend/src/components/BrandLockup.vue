<script setup lang="ts">
/**
 * 曜石智能 · 标志组合（按《标志标准规范 V1.0》）
 *
 * 三种组合：
 *   stack  中英上下（标准标志）—— 首页品牌头、登录页
 *   inline 横版无英文 —— 招牌、页眉、侧栏
 *   badge  徽标「曜石」两字 —— APP 图标、小尺寸占位
 *
 * 两种配色：
 *   dark 深色版（首选）：曜石黑底 + 金字
 *   gold 金底版：金属金底 + 黑字
 */
withDefaults(defineProps<{
  variant?: 'stack' | 'inline' | 'badge'
  tone?: 'dark' | 'gold'
  /** 字标高度（px），英文与间距按比例跟随 */
  size?: number
  /** 是否显示英文（inline 组合按规范默认不带英文） */
  en?: boolean
  /**
   * 英文字距。规范里标志的英文是宽字距（0.42em），但窄容器（如 210px 侧栏）放不下，
   * 传 'tight' 收到 0.12em 保证不被截断。
   */
  enTracking?: 'wide' | 'tight'
}>(), {
  variant: 'stack',
  tone: 'dark',
  size: 28,
  en: true,
  enTracking: 'wide',
})
</script>

<template>
  <!-- 徽标：APP 图标用的「曜石」两字 -->
  <span v-if="variant === 'badge'" class="bl-badge" :class="tone"
    :style="{ width: size * 2 + 'px', height: size * 2 + 'px', fontSize: size * 0.72 + 'px' }">
    <span class="brand-cn">曜</span><span class="brand-cn">石</span>
  </span>

  <!-- 横版：招牌 / 页眉 / 侧栏 -->
  <span v-else-if="variant === 'inline'" class="bl-inline">
    <span class="brand-cn" :class="tone === 'gold' ? 'on-gold' : 'brand-gold-text'"
      :style="{ fontSize: size + 'px' }">曜石智能</span>
    <span v-if="en" class="brand-en bl-en" :class="'tk-' + enTracking"
      :style="{ fontSize: Math.max(9, size * 0.3) + 'px' }">
      Obsidian Intelligence
    </span>
  </span>

  <!-- 标准标志：中英上下 -->
  <span v-else class="bl-stack">
    <span class="brand-cn" :class="tone === 'gold' ? 'on-gold' : 'brand-gold-text'"
      :style="{ fontSize: size + 'px', lineHeight: 1.1 }">曜石智能</span>
    <span v-if="en" class="brand-en bl-en" :class="'tk-' + enTracking"
      :style="{ fontSize: Math.max(9, size * 0.26) + 'px' }">
      Obsidian Intelligence
    </span>
  </span>
</template>

<style scoped>
.bl-stack { display: inline-flex; flex-direction: column; align-items: center; gap: 6px; }
.bl-inline { display: inline-flex; align-items: baseline; gap: 10px; }
.bl-en { color: var(--brand-gold-dim); white-space: nowrap; }
/* 窄容器用紧排,避免英文被截断(侧栏 210px 放不下规范的 0.42em 宽字距) */
.bl-en.tk-tight { letter-spacing: 0.12em; text-indent: 0.12em; }
/* 金底版：底已是金，字用曜石黑 */
.on-gold { color: var(--brand-obsidian); -webkit-text-fill-color: var(--brand-obsidian); }

.bl-badge {
  display: inline-flex; flex-direction: column; align-items: center; justify-content: center;
  border-radius: 22%; line-height: 1.05;
}
.bl-badge.dark { background: var(--brand-obsidian); border: 1px solid var(--brand-gold-line); color: var(--brand-gold); }
.bl-badge.gold { background: var(--brand-gold-gradient); color: var(--brand-obsidian); }
</style>
