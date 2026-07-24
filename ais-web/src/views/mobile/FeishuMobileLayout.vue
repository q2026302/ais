<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { MagicStick, MoreFilled, Picture, Plus, ChatDotRound, User } from '@element-plus/icons-vue'
import { useSessionStore } from '@/stores/session'
import { useMobileKeyboard } from '@/composables/useMobileKeyboard'

defineOptions({
  name: 'FeishuMobileLayout',
})

const store = useSessionStore()
const route = useRoute()
const pageRef = ref<HTMLElement | null>(null)
const { keyboardOpen, inputChromeCollapsed } = useMobileKeyboard()

const entryPrefix = computed(() => route.meta.mobileEntry === 'mobile' ? 'mobile' : 'feishu')

const activeSessionTitle = computed(() => {
  const s = store.sessions.find((s) => s.id === store.activeSessionId)
  return s?.title || 'AI 创作'
})

const hideBottomNav = computed(() => route.meta?.hideBottomNav === true || inputChromeCollapsed.value)
</script>

<template>
  <main
    ref="pageRef"
    class="feishu-layout"
    :class="{ 'keyboard-open': keyboardOpen }"
  >
    <header class="mobile-header">
      <div class="brand-block">
        <span class="brand-icon"><MagicStick /></span>
        <div class="brand-copy">
          <strong>{{ activeSessionTitle }}</strong>
          <span>AI 创作助手</span>
        </div>
      </div>
      <div class="header-actions">
        <button
          class="header-icon-button"
          type="button"
          aria-label="新建会话"
          title="新建会话"
          @click="store.createSession?.()"
        >
          <Plus />
        </button>
        <button
          class="more-button"
          type="button"
          aria-label="更多操作"
        >
          <MoreFilled />
        </button>
      </div>
    </header>

    <div class="layout-content">
      <router-view />
    </div>

    <footer v-if="!hideBottomNav" class="bottom-nav">
      <router-link
        :to="{ name: entryPrefix + '-sessions' }"
        class="nav-tab"
        :class="{ active: route.name === entryPrefix + '-sessions' }"
      >
        <ChatDotRound />
        <span>会话</span>
      </router-link>
      <router-link
        :to="{ name: entryPrefix + '-gallery' }"
        class="nav-tab"
        :class="{ active: route.name === entryPrefix + '-gallery' }"
      >
        <Picture />
        <span>作品</span>
      </router-link>
      <router-link
        :to="{ name: entryPrefix + '-profile' }"
        class="nav-tab"
        :class="{ active: route.name === entryPrefix + '-profile' }"
      >
        <User />
        <span>我的</span>
      </router-link>
    </footer>
  </main>
</template>

<style scoped>
.feishu-layout {
  --mobile-primary: #4f67e8;
  --mobile-primary-deep: #3d51c7;
  --mobile-text: #24314d;
  --mobile-muted: #7d899f;
  --mobile-border: #e5e9f2;

  position: fixed;
  top: 0;
  left: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 100%;
  height: 100%;
  height: 100dvh;
  overflow: hidden;
  color: var(--mobile-text);
  background:
    radial-gradient(circle at 95% -5%, rgba(106, 90, 238, .12), transparent 24rem),
    linear-gradient(180deg, #f7f9fd 0%, #f2f5fa 100%);
}

.mobile-header {
  position: relative;
  z-index: 10;
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  min-height: calc(64px + env(safe-area-inset-top));
  padding: calc(10px + env(safe-area-inset-top)) 14px 10px;
  border-bottom: 1px solid rgba(225, 230, 240, .9);
  background: rgba(255, 255, 255, .92);
  box-shadow: 0 3px 16px rgba(41, 55, 94, .04);
  backdrop-filter: blur(18px);
}

.brand-block { display: flex; min-width: 0; align-items: center; gap: 11px; }
.brand-icon {
  display: grid;
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  place-items: center;
  color: #fff;
  font-size: 20px;
  border-radius: 13px;
  background: linear-gradient(145deg, #506cf1, #8b5be0);
  box-shadow: 0 8px 18px rgba(75, 91, 211, .24);
}
.brand-copy { display: flex; min-width: 0; flex-direction: column; line-height: 1.2; }
.brand-copy strong { max-width: 52vw; overflow: hidden; color: #26334e; font-size: 16px; letter-spacing: -.2px; text-overflow: ellipsis; white-space: nowrap; }
.brand-copy span { margin-top: 4px; color: #8c97aa; font-size: 11px; font-weight: 600; }
.header-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 8px; }
.header-icon-button {
  display: grid;
  width: 40px;
  height: 40px;
  padding: 0;
  place-items: center;
  cursor: pointer;
  border: 0;
  border-radius: 13px;
  color: #5365cc;
  background: #eef1ff;
}
.more-button {
  display: grid;
  width: 40px;
  height: 40px;
  padding: 0;
  place-items: center;
  cursor: pointer;
  border: 0;
  border-radius: 13px;
  color: #5365cc;
  background: #eef1ff;
}
.header-icon-button:disabled { cursor: not-allowed; opacity: .5; }

.layout-content {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
}

.bottom-nav {
  display: flex;
  flex: 0 0 auto;
  align-items: stretch;
  justify-content: space-around;
  padding: 6px 0 calc(6px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--mobile-border);
  background: rgba(255, 255, 255, .96);
  backdrop-filter: blur(18px);
}

.nav-tab {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 6px 0;
  cursor: pointer;
  text-decoration: none;
  color: var(--mobile-muted);
  font-size: 11px;
  border: 0;
  background: transparent;
  transition: color .15s;
}
.nav-tab svg { width: 22px; height: 22px; }
.nav-tab.active { color: var(--mobile-primary); font-weight: 600; }
</style>
