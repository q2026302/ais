<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
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
import client from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { isRestrictedWebView } from '@/utils/downloadImage'
import { getAppBasePath } from '@/utils/appBasePath'
import {
  getMobileWorkspaceSource,
  mobileWorkspacePath,
  withMobileSource,
} from '@/utils/mobileWorkspace'

defineOptions({
  name: 'FeishuProfilePage',
})

interface DeferredInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>
}

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const mobileSource = computed(() => getMobileWorkspaceSource(route) ?? 'mobile')

const deferredInstallPrompt = ref<DeferredInstallPromptEvent | null>(null)
const isPwaStandalone = ref(false)
const isRestrictedBrowser = ref(false)
const isIosSafari = ref(false)
const iosInstallGuideVisible = ref(false)
const buildInfo = ref({ version: '', commit: '', buildTime: '' })
let standaloneMediaQuery: MediaQueryList | null = null

const accountRoleLabel = computed(() => (auth.isAdmin ? '管理员' : '普通用户'))
const showPwaInstall = computed(
  () => !isPwaStandalone.value && !isRestrictedBrowser.value && deferredInstallPrompt.value != null,
)
const showIosInstallGuide = computed(
  () => !isPwaStandalone.value && !isRestrictedBrowser.value && isIosSafari.value,
)
const showBrowserInstallHint = computed(() => !isPwaStandalone.value && isRestrictedBrowser.value)

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

function getMobileInstallUrl() {
  if (typeof window === 'undefined') return mobileWorkspacePath('pwa')
  const url = new URL(window.location.href)
  const currentPath = url.pathname.replace(/\/$/, '')
  url.pathname = /\/(?:mobile|feishu|pwa)(?:\/.*)?$/.test(currentPath)
    ? `${currentPath.replace(/\/(?:mobile|feishu|pwa)(?:\/.*)?$/, '')}/pwa`
    : `${getAppBasePath().replace(/\/$/, '')}/pwa`
  url.search = ''
  url.hash = ''
  return url.href
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
  const isIos =
    /iPad|iPhone|iPod/i.test(ua) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
  return (
    isIos &&
    /Safari/i.test(ua) &&
    !/(CriOS|FxiOS|EdgiOS|OPiOS|YaBrowser|GSA|DuckDuckGo|FBAN|FBAV|Instagram|Line|MicroMessenger|Lark|Feishu)/i.test(
      ua,
    )
  )
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

async function openAppPage(name: 'profile' | 'admin' | 'admin-users' | 'home') {
  await router.push(withMobileSource({ name }, mobileSource.value))
}

async function openMobileAdmin() {
  // Desktop admin stays the full management surface; mobile deep admin is later work.
  await openAppPage('admin')
}

async function handleLogout() {
  await auth.logout()
  await router.replace({
    name: 'login',
    query: { redirect: mobileWorkspacePath(mobileSource.value) },
  })
}

async function loadBuildInfo() {
  try {
    const { data } = await client.get<{ version: string; commit: string; buildTime: string }>(
      '/api/version',
    )
    buildInfo.value = data
  } catch {
    // ignore version lookup failures
  }
}

onMounted(() => {
  if (typeof window !== 'undefined' && typeof navigator !== 'undefined') {
    isRestrictedBrowser.value = isRestrictedWebView()
    isIosSafari.value = !isRestrictedBrowser.value && detectIosSafari()
    updatePwaDisplayMode()
    window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
    window.addEventListener('appinstalled', handleAppInstalled)
    standaloneMediaQuery = window.matchMedia?.('(display-mode: standalone)') ?? null
    standaloneMediaQuery?.addEventListener('change', updatePwaDisplayMode)
  }
  void loadBuildInfo()
})

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
    window.removeEventListener('appinstalled', handleAppInstalled)
  }
  standaloneMediaQuery?.removeEventListener('change', updatePwaDisplayMode)
  standaloneMediaQuery = null
})
</script>

<template>
  <div class="profile-page">
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
        <span>
          <strong>安装 AIS</strong>
          <small>添加到设备主屏幕</small>
        </span>
        <ArrowRight />
      </button>
      <button
        v-else-if="showIosInstallGuide"
        type="button"
        aria-label="查看添加到主屏幕说明"
        @click="iosInstallGuideVisible = true"
      >
        <span class="menu-icon"><Promotion /></span>
        <span>
          <strong>添加到主屏幕</strong>
          <small>使用 Safari 分享菜单</small>
        </span>
        <ArrowRight />
      </button>
      <button
        v-else-if="showBrowserInstallHint"
        type="button"
        aria-label="复制浏览器安装链接"
        @click="copyMobileInstallUrl"
      >
        <span class="menu-icon"><CopyDocument /></span>
        <span>
          <strong>在浏览器中打开以安装</strong>
          <small>复制安装链接后在浏览器打开</small>
        </span>
        <ArrowRight />
      </button>
      <button type="button" @click="openAppPage('profile')">
        <span class="menu-icon"><User /></span>
        <span>
          <strong>个人中心</strong>
          <small>资料、模型偏好与消费记录</small>
        </span>
        <ArrowRight />
      </button>
      <button v-if="auth.isAdmin" type="button" @click="openMobileAdmin()">
        <span class="menu-icon"><Setting /></span>
        <span>
          <strong>系统设置</strong>
          <small>用户、会话和操作日志</small>
        </span>
        <ArrowRight />
      </button>
      <button type="button" @click="openAppPage('home')">
        <span class="menu-icon"><Monitor /></span>
        <span>
          <strong>完整工作台</strong>
          <small>进入桌面版界面（可返回）</small>
        </span>
        <ArrowRight />
      </button>
      <button
        v-if="auth.securityEnabled"
        class="danger-menu"
        type="button"
        @click="handleLogout"
      >
        <span class="menu-icon"><SwitchButton /></span>
        <span>
          <strong>退出登录</strong>
          <small>安全退出当前账户</small>
        </span>
        <ArrowRight />
      </button>
    </div>

    <div v-if="buildInfo.version" class="build-info">
      v{{ buildInfo.version }} · {{ buildInfo.commit }} · {{ buildInfo.buildTime }}
    </div>

    <el-drawer
      v-model="iosInstallGuideVisible"
      direction="btt"
      size="auto"
      class="h5-drawer install-guide-drawer"
      :with-header="false"
    >
      <div class="drawer-title compact">
        <div>
          <strong>添加到主屏幕</strong>
          <span>请在 Safari 中完成安装</span>
        </div>
        <button
          type="button"
          aria-label="关闭添加到主屏幕说明"
          @click="iosInstallGuideVisible = false"
        >
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
        aria-label="关闭添加到主屏幕说明"
        @click="iosInstallGuideVisible = false"
      >
        我知道了
      </button>
    </el-drawer>
  </div>
</template>

<style scoped>
.profile-page {
  box-sizing: border-box;
  min-height: 100%;
  padding: 16px 14px 28px;
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
  box-shadow: 0 8px 18px rgba(75, 85, 202, 0.2);
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

.build-info {
  margin-top: 22px;
  color: #9aa3b5;
  font-size: 11px;
  text-align: center;
  line-height: 1.5;
}

.drawer-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 2px 15px;
  border-bottom: 1px solid #edf0f5;
  -webkit-user-select: none;
  user-select: none;
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
  -webkit-user-select: none;
  user-select: none;
}

.drawer-title span {
  color: #929bad;
  font-size: 11px;
  -webkit-user-select: none;
  user-select: none;
}

.drawer-title > button {
  display: inline-flex;
  flex: 0 0 auto;
  min-height: 36px;
  min-width: 36px;
  align-items: center;
  justify-content: center;
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

:deep(.h5-drawer.el-drawer) {
  max-width: 760px;
  margin: 0 auto;
  border-radius: 24px 24px 0 0;
  background: #fff;
  box-shadow: 0 -18px 50px rgba(30, 42, 78, 0.2);
}

:deep(.h5-drawer .el-drawer__body) {
  padding: 18px 16px calc(18px + env(safe-area-inset-bottom));
  overflow-y: auto;
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
</style>
