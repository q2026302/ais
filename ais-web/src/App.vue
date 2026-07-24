<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ArrowLeft, MagicStick, Setting, SwitchButton, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import {
  getMobileWorkspaceSource,
  mobileWorkspaceLocation,
  mobileWorkspacePath,
  withMobileSource,
} from '@/utils/mobileWorkspace'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const isEmbedded = computed(() => route.meta.embedded === true)
const showChrome = computed(() => !isEmbedded.value && route.name !== 'login')
const mobileSource = computed(() => getMobileWorkspaceSource(route))
const isMobileWorkspaceContext = computed(() => mobileSource.value != null)
const workspaceLocation = computed(() =>
  mobileSource.value ? mobileWorkspaceLocation(mobileSource.value) : { name: 'home' as const },
)
const isWorkspaceRoute = computed(
  () => route.name === 'home' || route.name === 'feishu-h5' || route.name === 'mobile-workbench',
)
const showBackToWorkspace = computed(() => showChrome.value && !isWorkspaceRoute.value)

function appLocation(name: 'admin' | 'profile') {
  return withMobileSource({ name }, mobileSource.value)
}

function goWorkspace() {
  void router.push(workspaceLocation.value)
}

async function handleLogout() {
  await auth.logout()
  const redirect = mobileSource.value ? mobileWorkspacePath(mobileSource.value) : undefined
  await router.replace({ name: 'login', query: redirect ? { redirect } : undefined })
}
</script>

<template>
  <div id="app-container">
    <el-container class="app-shell">
      <el-header v-if="showChrome" class="app-header" height="64px">
        <div class="header-left">
          <button
            v-if="showBackToWorkspace"
            class="back-to-workspace"
            type="button"
            aria-label="返回创作"
            title="返回创作"
            @click="goWorkspace"
          >
            <ArrowLeft />
            <span>返回创作</span>
          </button>
          <button class="brand" type="button" aria-label="返回创作工作台" @click="goWorkspace">
            <span class="brand-mark"><MagicStick /></span>
            <span class="brand-copy">
              <strong>AIS</strong>
              <small>{{ isMobileWorkspaceContext ? '移动创作' : 'AI 创作工作台' }}</small>
            </span>
          </button>
        </div>
        <nav class="header-nav" aria-label="主导航">
          <el-button
            class="nav-button"
            :class="{ active: isWorkspaceRoute }"
            text
            title="创作工作台"
            @click="goWorkspace"
          >
            <MagicStick />
            创作
          </el-button>
          <el-button
            v-if="auth.isAdmin"
            class="nav-button"
            :class="{ active: route.path.startsWith('/admin') }"
            text
            title="系统管理"
            @click="router.push(appLocation('admin'))"
          >
            <Setting />
            管理
          </el-button>
          <el-button
            v-if="auth.isAuthenticated"
            class="nav-button"
            :class="{ active: route.path === '/profile' }"
            text
            title="个人中心"
            @click="router.push(appLocation('profile'))"
          >
            <User />
            个人中心
          </el-button>
          <el-button
            v-if="auth.securityEnabled"
            class="nav-button"
            text
            title="退出登录"
            @click="handleLogout"
          >
            <SwitchButton />
            退出
          </el-button>
        </nav>
      </el-header>
      <el-main class="app-main" :class="{ 'app-main--embedded': isEmbedded || route.name === 'login' }">
        <div
          v-if="showBackToWorkspace"
          class="mobile-return-bar"
          role="navigation"
          aria-label="返回创作"
        >
          <button type="button" class="mobile-return-button" @click="goWorkspace">
            <ArrowLeft />
            <span>返回创作对话</span>
          </button>
          <span class="mobile-return-hint">当前在{{ route.path.startsWith('/admin') ? '管理页' : route.path === '/profile' ? '个人中心' : '应用页' }}</span>
        </div>
        <router-view v-slot="{ Component }">
          <keep-alive include="HomeView">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </div>
</template>

<style>
:root {
  --app-font-family: Inter, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif;
  --app-primary: #5267f6;
  --app-primary-deep: #3f4ec9;
  --app-accent: #9b5cf6;
  --app-surface: #ffffff;
  --app-canvas: #f5f7ff;
  --app-border: #e7eaf6;
  --app-text: #202a44;
  --app-muted: #77819d;
  --app-shadow: 0 14px 40px rgba(44, 58, 119, 0.1);
  --app-radius: 16px;
  --el-font-family: var(--app-font-family);
  --el-color-primary: var(--app-primary);
  --el-color-primary-light-3: #8794ff;
  --el-color-primary-light-5: #a9b1ff;
  --el-color-primary-light-7: #d5d9ff;
  --el-color-primary-light-8: #e5e7ff;
  --el-color-primary-light-9: #f0f1ff;
  --el-border-radius-base: 10px;
  --el-border-color: var(--app-border);
  --el-text-color-primary: var(--app-text);
  --el-text-color-regular: #59647e;
}

* { box-sizing: border-box; }

html, body, #app {
  margin: 0;
  padding: 0;
  height: 100%;
  min-width: 320px;
  font-family: var(--app-font-family);
  color: var(--app-text);
  font-size: 14px;
  line-height: 1.5;
  background: var(--app-canvas);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-rendering: optimizeLegibility;
  /* Lock document scroll; shells size to visual viewport (see --app-vvh). */
  overflow: hidden;
}

/*
 * Pin the app root to the visual viewport so soft keyboards shrink the shell
 * instead of overlaying bottom inputs (iOS Safari / installed PWA).
 * --vv-height / --vv-offset-top are written by subscribeVisualViewport().
 */
#app-container {
  position: fixed;
  top: var(--vv-offset-top, 0px);
  left: 0;
  width: 100%;
  height: 100%;
  height: var(--vv-height, 100dvh);
  overflow: hidden;
}

button, input, textarea, select { font-family: inherit; }
button { -webkit-tap-highlight-color: transparent; }

/* Focus rings must follow border-radius; avoid outline + offset (square / detached). */
button:focus-visible,
a:focus-visible,
[tabindex]:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px rgba(82, 103, 246, .28);
}

::selection { color: #fff; background: var(--app-primary); }

::-webkit-scrollbar { width: 8px; height: 8px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { border: 2px solid transparent; border-radius: 999px; background: #c9cfe2; background-clip: padding-box; }
::-webkit-scrollbar-thumb:hover { background: #aeb8d5; background-clip: padding-box; }

.el-button { font-weight: 600; min-height: 32px; transition: transform .18s ease, box-shadow .18s ease, background-color .18s ease, border-color .18s ease; }
.el-button:not(.is-disabled):active { transform: translateY(1px); }
.el-button--primary { border: 0; box-shadow: 0 5px 12px rgba(82, 103, 246, .2); }
.el-button:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px rgba(82, 103, 246, .28);
}
.el-button--primary:focus-visible {
  box-shadow: 0 5px 12px rgba(82, 103, 246, .22), 0 0 0 3px rgba(82, 103, 246, .28);
}
.el-card { border-color: var(--app-border); }

/*
 * Form controls: real border follows border-radius cleanly.
 * Kill Element Plus inset box-shadow borders (often read as square corners).
 */
.el-input .el-input__wrapper,
.el-textarea .el-textarea__inner,
.el-select .el-select__wrapper,
.el-input-number .el-input .el-input__wrapper {
  border: 1px solid var(--app-border);
  border-radius: var(--el-border-radius-base);
  box-shadow: none;
  transition: border-color .18s ease, box-shadow .18s ease;
}
.el-input .el-input__wrapper:hover,
.el-textarea .el-textarea__inner:hover,
.el-select .el-select__wrapper.is-hovering:not(.is-focused),
.el-input-number .el-input:not(.is-disabled) .el-input__wrapper:hover,
.el-input-number__increase:hover ~ .el-input:not(.is-disabled) .el-input__wrapper,
.el-input-number__decrease:hover ~ .el-input:not(.is-disabled) .el-input__wrapper {
  border-color: #c5cbe3;
  box-shadow: none;
}
.el-input .el-input__wrapper.is-focus,
.el-textarea .el-textarea__inner:focus,
.el-select .el-select__wrapper.is-focused,
.el-input-number .el-input .el-input__wrapper.is-focus {
  border-color: rgba(82, 103, 246, .65);
  box-shadow: 0 0 0 3px rgba(82, 103, 246, .14);
}
.el-input.is-disabled .el-input__wrapper,
.el-textarea.is-disabled .el-textarea__inner,
.el-select .el-select__wrapper.is-disabled,
.el-input-number.is-disabled .el-input__wrapper {
  border-color: var(--el-disabled-border-color, #e4e7ed);
  box-shadow: none;
}
.el-input .el-input__inner,
.el-input .el-input__inner:focus,
.el-select .el-select__input,
.el-select .el-select__input:focus {
  outline: none;
  border: 0;
  box-shadow: none;
  background: transparent;
}
.el-dialog { border-radius: 20px; overflow: hidden; box-shadow: 0 24px 80px rgba(29, 43, 98, .22); }
.el-dialog__header { margin-right: 0; padding: 20px 24px 16px; border-bottom: 1px solid var(--app-border); }
.el-dialog__body { padding: 22px 24px; }
.el-dialog__footer { padding: 14px 24px 20px; border-top: 1px solid var(--app-border); }
.el-empty { padding: 48px 20px; }
.el-empty__description p { color: var(--app-muted); }
.el-tag { border-radius: 999px; }

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { scroll-behavior: auto !important; animation-duration: .01ms !important; animation-iteration-count: 1 !important; transition-duration: .01ms !important; }
}

@media (max-width: 720px) {
  .el-dialog { width: calc(100% - 24px) !important; }
}
</style>

<style scoped>
.app-shell { height: 100%; }

.app-header {
  position: relative;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 28px;
  border-bottom: 1px solid rgba(222, 226, 244, .92);
  background: rgba(255, 255, 255, .9);
  box-shadow: 0 3px 18px rgba(40, 54, 113, .05);
  backdrop-filter: blur(16px);
}

.header-left {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

.back-to-workspace {
  display: none;
  align-items: center;
  gap: 4px;
  min-height: 34px;
  padding: 0 10px 0 8px;
  color: #4f62d6;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  border: 1px solid #dbe1ff;
  border-radius: 999px;
  background: #eef1ff;
}

.back-to-workspace :deep(svg) {
  width: 15px;
  height: 15px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  padding: 0;
  color: inherit;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.mobile-return-bar {
  display: none;
  position: sticky;
  top: 0;
  z-index: 15;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid #e6eaf6;
  background: rgba(246, 248, 255, .97);
  backdrop-filter: blur(12px);
}

.mobile-return-button {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  gap: 6px;
  padding: 0 12px 0 10px;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #5267f6, #7b5cf0);
  box-shadow: 0 6px 14px rgba(82, 103, 246, .22);
}

.mobile-return-button :deep(svg) {
  width: 16px;
  height: 16px;
}

.mobile-return-hint {
  min-width: 0;
  overflow: hidden;
  color: #8a93aa;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brand-mark {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  color: #fff;
  font-size: 19px;
  border-radius: 12px;
  background: linear-gradient(135deg, #586bf7 0%, #9b5cf6 100%);
  box-shadow: 0 7px 15px rgba(94, 94, 239, .28);
}

.brand-copy { display: flex; flex-direction: column; align-items: flex-start; line-height: 1.1; }
.brand-copy strong { color: #1f2a4a; font-size: 18px; letter-spacing: -.35px; }
.brand-copy small { margin-top: 4px; color: #8a93aa; font-size: 10px; font-weight: 600; letter-spacing: .3px; }

.header-nav { display: flex; align-items: center; gap: 4px; padding: 4px; border: 1px solid #eaedf7; border-radius: 13px; background: #f5f6fc; }
.nav-button { height: 32px; padding: 0 12px; color: #68738e; border-radius: 8px; }
.nav-button:not(.active):hover { color: #4456c5; background: rgba(255,255,255,.75); }
.nav-button :deep(.el-icon) { margin-right: 5px; }
.nav-button.active { color: var(--app-primary); background: #fff; box-shadow: 0 2px 8px rgba(56, 72, 139, .11); }

.app-main { height: calc(100% - 64px); padding: 0; overflow: auto; overscroll-behavior: contain; }
.app-main--embedded { height: 100%; }

@media (max-width: 600px) {
  .app-header { height: calc(56px + env(safe-area-inset-top)) !important; padding: env(safe-area-inset-top) 12px 0; }
  .app-main { height: calc(100% - 56px - env(safe-area-inset-top)); }
  .header-nav { gap: 1px; padding: 3px; }
  .header-left { gap: 6px; }
  .back-to-workspace { display: inline-flex; padding: 0 8px; font-size: 12px; }
  .back-to-workspace span { max-width: 4.5em; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .brand-copy small { display: none; }
  .brand-copy strong { font-size: 16px; }
  .brand-mark { width: 34px; height: 34px; }
  .nav-button { width: 34px; padding: 0; font-size: 0; }
  .nav-button :deep(.el-icon) { margin-right: 0; font-size: 16px; }
  /* Explicit return strip — icon-only top nav is easy to miss on phones. */
  .mobile-return-bar { display: flex; }
}
</style>
