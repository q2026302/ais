<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowRight,
  Check,
  Close,
  CopyDocument,
  Download,
  Monitor,
  Promotion,
  Setting,
  SwitchButton,
  User,
  UserFilled,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useSessionStore } from '@/stores/session'
import { ElMessage } from 'element-plus'
import { getMobileWorkspaceSource, mobileWorkspacePath } from '@/utils/mobileWorkspace'
import { isRestrictedWebView } from '@/utils/downloadImage'

defineOptions({
  name: 'FeishuProfilePage',
})

interface DeferredInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>
}

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const store = useSessionStore()

const mobileSource = computed(() => getMobileWorkspaceSource(route) ?? 'mobile')
const entryPrefix = computed(() => route.meta.mobileEntry === 'mobile' ? 'mobile' : 'feishu')

const iosInstallGuideVisible = ref(false)
const deferredInstallPrompt = ref<DeferredInstallPromptEvent | null>(null)
const isPwaStandalone = ref(false)
const isRestrictedBrowser = ref(false)
const isIosSafari = ref(false)
let standaloneMediaQuery: MediaQueryList | null = null
const originalTitle = document.title

const accountRoleLabel = computed(() => auth.isAdmin ? '管理员' : '普通用户')

const showPwaInstall = computed(() => !isPwaStandalone.value && !isRestrictedBrowser.value && deferredInstallPrompt.value != null)
const showIosInstallGuide = computed(() => !isPwaStandalone.value && !isRestrictedBrowser.value && isIosSafari.value)
const showBrowserInstallHint = computed(() => !isPwaStandalone.value && isRestrictedBrowser.value)

const activeSessionTitle = computed(() => {
  const s = store.sessions.find((s) => s.id === store.activeSessionId)
  return s?.title || 'AI 创作'
})

function getMobileInstallUrl() {
  if (typeof window === 'undefined') return mobileWorkspacePath('mobile')
  const url = new URL(window.location.href)
  const currentPath = url.pathname.replace(/\/$/, '')
  url.pathname = /\/(?:mobile|feishu)$/.test(currentPath)
    ? `${currentPath.replace(/\/(?:mobile|feishu)$/, '')}/mobile`
    : mobileWorkspacePath('mobile')
  url.search = ''
  url.hash = ''
  return url.href
}

async function copyText(text: string, successMessage = '内容已复制') {
  if (!text.trim()) return
  try {
    await navigator.clipboard.writeText(text)
    window.getSelection()?.removeAllRanges()
    ElMessage.success(successMessage)
  } catch {
    window.getSelection()?.removeAllRanges()
    ElMessage.error('复制失败，请手动选择复制')
  }
}

async function copyMobileInstallUrl() {
  await copyText(getMobileInstallUrl(), '安装链接已复制')
}

function detectPwaStandalone() {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') return false
  const navigatorStandalone = Boolean((navigator as Navigator & { standalone?: boolean }).standalone)
  return navigatorStandalone || window.matchMedia?.('(display-mode: standalone)').matches === true
}

function detectIosSafari() {
  if (typeof navigator === 'undefined') return false
  const ua = navigator.userAgent || ''
  const isIos = /iPad|iPhone|iPod/i.test(ua)
    || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
  return isIos && /Safari/i.test(ua)
    && !/(CriOS|FxiOS|EdgiOS|OPiOS|YaBrowser|GSA|DuckDuckGo|FBAN|FBAV|Instagram|Line|MicroMessenger|Lark|Feishu)/i.test(ua)
}

function updatePwaDisplayMode() {
  isPwaStandalone.value = detectPwaStandalone()
  if (isPwaStandalone.value) deferredInstallPrompt.value = null
}

function handleBeforeInstallPrompt(event: Event) {
  event.preventDefault()
  if (isPwaStandalone.value || isRestrictedBrowser.value) return
  const installEvent = event as DeferredInstallPromptEvent
  if (typeof installEvent.prompt === 'function') deferredInstallPrompt.value = installEvent
}

function handleAppInstalled() {
  deferredInstallPrompt.value = null
  isPwaStandalone.value = true
  ElMessage.success('AIS 已安装')
}

async function promptPwaInstall() {
  const installEvent = deferredInstallPrompt.value
  if (!installEvent) return
  try {
    await installEvent.prompt()
    const { outcome } = await installEvent.userChoice
    deferredInstallPrompt.value = null
    if (outcome === 'accepted') ElMessage.success('正在安装 AIS')
    else ElMessage.info('已取消安装')
  } catch {
    ElMessage.warning('暂时无法打开安装提示')
  }
}

async function handleLogout() {
  await auth.logout()
  await router.replace({ name: 'login', query: { redirect: mobileWorkspacePath(mobileSource.value) } })
}

function goBack() {
  router.push({ name: entryPrefix.value + '-sessions' })
}

onMounted(() => {
  document.title = '我的'
  if (typeof window !== 'undefined' && typeof navigator !== 'undefined') {
    isRestrictedBrowser.value = isRestrictedWebView()
    isIosSafari.value = !isRestrictedBrowser.value && detectIosSafari()
    updatePwaDisplayMode()
    window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
    window.addEventListener('appinstalled', handleAppInstalled)
    standaloneMediaQuery = window.matchMedia?.('(display-mode: standalone)') ?? null
    standaloneMediaQuery?.addEventListener('change', updatePwaDisplayMode)
  }
})

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
    window.removeEventListener('appinstalled', handleAppInstalled)
  }
  standaloneMediaQuery?.removeEventListener('change', updatePwaDisplayMode)
  standaloneMediaQuery = null
  document.title = originalTitle
})
</script>

<template>
  <main class="profile-page">
    <header class="profile-header">
      <button type="button" class="back-button" aria-label="返回" @click="goBack">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <strong class="profile-title">我的</strong>
      <span class="profile-spacer"></span>
    </header>

    <div class="profile-body">
      <div class="account-summary">
        <span class="account-avatar"><UserFilled /></span>
        <div>
          <strong>应用与账户</strong>
          <span>{{ accountRoleLabel }} · 移动工作台</span>
        </div>
      </div>

      <p class="account-tip">进入个人中心或管理页后，可用顶部「返回创作」回到对话。</p>

      <div class="app-menu">
        <button
          v-if="showPwaInstall"
          type="button"
          aria-label="安装 AIS"
          @click="promptPwaInstall"
        >
          <span class="menu-icon"><Download /></span>
          <span><strong>安装 AIS</strong><small>添加到设备主屏幕</small></span>
          <ArrowRight />
        </button>
        <button
          v-else-if="showIosInstallGuide"
          type="button"
          aria-label="查看添加到主屏幕说明"
          @click="iosInstallGuideVisible = true"
        >
          <span class="menu-icon"><Promotion /></span>
          <span><strong>添加到主屏幕</strong><small>使用 Safari 分享菜单</small></span>
          <ArrowRight />
        </button>
        <button
          v-else-if="showBrowserInstallHint"
          type="button"
          aria-label="复制浏览器安装链接"
          @click="copyMobileInstallUrl"
        >
          <span class="menu-icon"><CopyDocument /></span>
          <span><strong>在浏览器中打开以安装</strong><small>复制安装链接后在浏览器打开</small></span>
          <ArrowRight />
        </button>
        <button type="button" @click="router.push('/profile')">
          <span class="menu-icon"><User /></span>
          <span><strong>个人中心</strong><small>资料、模型偏好与消费记录</small></span>
          <ArrowRight />
        </button>
        <button
          v-if="auth.isAdmin"
          type="button"
          @click="router.push('/admin')"
        >
          <span class="menu-icon"><Setting /></span>
          <span><strong>管理</strong><small>用户、会话和操作日志</small></span>
          <ArrowRight />
        </button>
        <button type="button" @click="router.push('/')">
          <span class="menu-icon"><Monitor /></span>
          <span><strong>完整工作台</strong><small>进入桌面版界面（可返回）</small></span>
          <ArrowRight />
        </button>
        <button
          v-if="auth.securityEnabled"
          class="danger-menu"
          type="button"
          @click="handleLogout"
        >
          <span class="menu-icon"><SwitchButton /></span>
          <span><strong>退出登录</strong><small>安全退出当前账户</small></span>
          <ArrowRight />
        </button>
      </div>
    </div>

    <!-- iOS 安装引导提示抽屉 -->
    <Transition name="ios-guide-fade">
      <div
        v-if="iosInstallGuideVisible"
        class="ios-guide-overlay"
        role="dialog"
        aria-modal="true"
        aria-label="添加到主屏幕说明"
      >
        <div class="ios-guide-backdrop" @click="iosInstallGuideVisible = false"></div>
        <div class="ios-guide-panel">
          <div class="drawer-title compact">
            <div>
              <strong>添加到主屏幕</strong>
              <span>请在 Safari 中完成安装</span>
            </div>
            <button type="button" aria-label="关闭" @click="iosInstallGuideVisible = false">
              <Close />
            </button>
          </div>
          <div class="install-guide-steps">
            <p><span>1</span>点击 Safari 的分享按钮</p>
            <p><span>2</span>选择「添加到主屏幕」</p>
          </div>
          <button
            type="button"
            class="install-guide-confirm"
            @click="iosInstallGuideVisible = false"
          >
            我知道了
          </button>
        </div>
      </div>
    </Transition>
  </main>
</template>

<style scoped>
.profile-page {
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
  height: 100%;
  height: 100dvh;
  overflow: hidden;
  color: var(--mobile-text);
  background:
    radial-gradient(circle at 95% -5%, rgba(106, 90, 238, .12), transparent 24rem),
    linear-gradient(180deg, #f7f9fd 0%, #f2f5fa 100%);
}

.profile-header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  min-height: calc(56px + env(safe-area-inset-top));
  padding: env(safe-area-inset-top) 12px 10px;
  border-bottom: 1px solid rgba(225, 230, 240, .9);
  background: rgba(255, 255, 255, .92);
  backdrop-filter: blur(18px);
}

.back-button {
  display: grid;
  width: 36px;
  height: 36px;
  padding: 0;
  place-items: center;
  color: #5365cc;
  cursor: pointer;
  border: 0;
  border-radius: 10px;
  background: #eef1ff;
}

.profile-title {
  color: #26334e;
  font-size: 17px;
  font-weight: 700;
}

.profile-spacer {
  width: 36px;
}

.profile-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 14px calc(24px + env(safe-area-inset-bottom));
}

.account-summary {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 4px 3px 16px;
  border-bottom: 1px solid #edf0f5;
}

.account-avatar {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  color: #fff;
  font-size: 22px;
  border-radius: 16px;
  background: linear-gradient(145deg, #4f68e8, #8b5dde);
  box-shadow: 0 8px 18px rgba(75, 85, 202, .2);
}

.account-summary > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.account-summary strong {
  color: #303d58;
  font-size: 17px;
}

.account-summary span {
  color: #929bad;
  font-size: 11px;
}

.account-tip {
  margin: 12px 0 4px;
  padding: 10px 12px;
  color: #6d7890;
  font-size: 12px;
  line-height: 1.55;
  border-radius: 12px;
  background: #eef3ff;
}

.app-menu {
  display: grid;
  gap: 7px;
  padding-top: 12px;
}

.app-menu > button {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) 18px;
  min-height: 64px;
  align-items: center;
  gap: 10px;
  padding: 8px 11px;
  color: #536079;
  text-align: left;
  cursor: pointer;
  border: 1px solid #e9ecf3;
  border-radius: 14px;
  background: #fafbfe;
}

.menu-icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  color: #586bd4;
  font-size: 18px;
  border-radius: 12px;
  background: #edf0ff;
}

.app-menu button > span:nth-child(2) {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.app-menu strong {
  color: #414e69;
  font-size: 13px;
}

.app-menu small {
  overflow: hidden;
  color: #98a1b2;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-menu button > svg {
  width: 16px;
  color: #a1a9b9;
}

.app-menu .danger-menu .menu-icon {
  color: #cf6572;
  background: #fff0f2;
}

.app-menu .danger-menu strong {
  color: #b95564;
}

.drawer-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 2px 15px;
  border-bottom: 1px solid #edf0f5;
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
}

.drawer-title > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.drawer-title strong {
  color: #303d58;
  font-size: 17px;
  user-select: none;
  -webkit-user-select: none;
}

.drawer-title span {
  color: #929bad;
  font-size: 11px;
  user-select: none;
  -webkit-user-select: none;
}

.drawer-title > button {
  display: inline-flex;
  flex: 0 0 auto;
  min-height: 36px;
  align-items: center;
  gap: 5px;
  padding: 0 11px;
  color: #5064d2;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 10px;
  background: #edf0ff;
}

.drawer-title.compact {
  flex: 0 0 auto;
  padding-bottom: 13px;
}

.install-guide-steps {
  display: grid;
  gap: 9px;
  padding: 15px 1px 4px;
}

.install-guide-steps p {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0;
  color: #56627c;
  font-size: 13px;
  font-weight: 650;
}

.install-guide-steps span {
  display: grid;
  width: 23px;
  height: 23px;
  flex: 0 0 auto;
  place-items: center;
  color: #5368d8;
  font-size: 11px;
  font-weight: 800;
  border-radius: 50%;
  background: #edf0ff;
}

.install-guide-confirm {
  width: 100%;
  min-height: 40px;
  margin-top: 17px;
  color: #fff;
  font-size: 13px;
  font-weight: 750;
  cursor: pointer;
  border: 0;
  border-radius: 11px;
  background: linear-gradient(140deg, #536bea, #7657d4);
}

.ios-guide-overlay {
  position: fixed;
  z-index: 3200;
  inset: 0;
  display: grid;
  place-items: end center;
}

.ios-guide-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(12, 16, 28, .45);
}

.ios-guide-panel {
  position: relative;
  z-index: 1;
  width: min(100%, 560px);
  max-height: min(70vh, 600px);
  overflow: auto;
  padding: 16px 16px calc(16px + env(safe-area-inset-bottom));
  border-radius: 22px 22px 0 0;
  background: #fff;
  box-shadow: 0 -16px 40px rgba(20, 30, 60, .2);
  -webkit-overflow-scrolling: touch;
}

@media (hover: hover) and (pointer: fine) {
  .app-menu > button:hover {
    border-color: #cfd6f4;
    background: #f4f6ff;
    transform: translateY(-1px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .app-menu > button {
    transition: none;
  }
}

.ios-guide-fade-enter-active,
.ios-guide-fade-leave-active {
  transition: opacity .18s ease;
}

.ios-guide-fade-enter-from,
.ios-guide-fade-leave-to {
  opacity: 0;
}
</style>
