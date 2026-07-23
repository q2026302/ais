<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, MagicStick, Refresh } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { md5Hex } from '@/utils/passwordCrypto'
import {
  scrollElementIntoVisualViewport,
  subscribeVisualViewport,
} from '@/utils/visualViewport'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const captchaCode = ref('')
const captchaId = ref('')
const captchaImage = ref('')
const captchaEnabled = ref(true)
const loading = ref(false)
const captchaLoading = ref(false)
const pageRef = ref<HTMLElement | null>(null)
let stopVisualViewport: (() => void) | null = null

function onFieldFocus(event: FocusEvent) {
  const target = event.target
  if (!(target instanceof HTMLElement)) return
  scrollElementIntoVisualViewport(target, { block: 'center', behavior: 'smooth' })
}

onMounted(async () => {
  if (typeof window !== 'undefined') {
    // Keep login form within the visual viewport while the soft keyboard is open.
    stopVisualViewport = subscribeVisualViewport(() => {}, {
      cssTarget: () => pageRef.value,
    })
  }
  if (!auth.bootstrapped) {
    await auth.bootstrap()
  }
  if (auth.isAuthenticated) {
    await redirectAfterLogin()
    return
  }
  captchaEnabled.value = auth.captchaEnabled
  if (captchaEnabled.value) {
    await refreshCaptcha()
  }
})

onBeforeUnmount(() => {
  stopVisualViewport?.()
  stopVisualViewport = null
})

async function refreshCaptcha() {
  captchaLoading.value = true
  try {
    const result = await authApi.captcha()
    captchaEnabled.value = result.enabled
    if (!result.enabled) {
      captchaId.value = ''
      captchaImage.value = ''
      captchaCode.value = ''
      return
    }
    captchaId.value = result.captchaId || ''
    captchaImage.value = result.imageBase64 || ''
    captchaCode.value = ''
  } catch (error: any) {
    ElMessage.error(error.message || '验证码加载失败')
  } finally {
    captchaLoading.value = false
  }
}

async function handleLogin() {
  if (!username.value.trim() || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  if (captchaEnabled.value && !captchaCode.value.trim()) {
    ElMessage.warning('请输入验证码')
    return
  }
  loading.value = true
  try {
    await auth.login({
      username: username.value.trim(),
      passwordDigest: md5Hex(password.value),
      captchaId: captchaEnabled.value ? captchaId.value : undefined,
      captchaCode: captchaEnabled.value ? captchaCode.value.trim() : undefined,
    })
    ElMessage.success(auth.isAdmin ? '管理员登录成功' : '登录成功')
    await redirectAfterLogin()
  } catch (error: any) {
    ElMessage.error(error.message || '登录失败')
    if (captchaEnabled.value) {
      await refreshCaptcha()
    }
  } finally {
    password.value = ''
    loading.value = false
  }
}

async function redirectAfterLogin() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  const target = redirect.startsWith('/') ? redirect : '/'
  if (target.startsWith('/admin') && !auth.isAdmin) {
    ElMessage.warning('当前账号无管理权限，已进入首页')
    await router.replace('/')
    return
  }
  await router.replace(target)
}
</script>

<template>
  <main ref="pageRef" class="login-page">
    <div class="login-layout">
      <section class="login-intro" aria-label="产品介绍">
        <div class="intro-brand">
          <span class="intro-mark"><MagicStick /></span>
          <span>
            <strong>AIS</strong>
            <small>AI 创作工作台</small>
          </span>
        </div>
        <div class="intro-copy">
          <span class="intro-kicker">CREATE WITHOUT LIMITS</span>
          <h1>让每一个灵感，<br /><em>都能看得见。</em></h1>
          <p>用自然语言探索创意、生成图片，并在一个清晰顺手的工作台里管理你的创作。</p>
        </div>
        <ul class="intro-features">
          <li><span>✦</span><div><strong>对话式创作</strong><small>从想法到画面，随时继续你的灵感</small></div></li>
          <li><span>✦</span><div><strong>模型灵活切换</strong><small>按任务选择更合适的对话与绘图模型</small></div></li>
          <li><span>✦</span><div><strong>作品集中管理</strong><small>会话、参考素材与生成图片一处归档</small></div></li>
        </ul>
      </section>

      <form class="login-card" @submit.prevent="handleLogin" @focusin="onFieldFocus">
        <div class="card-heading">
          <span class="card-kicker">WELCOME BACK</span>
          <h2>登录工作台</h2>
          <p>登录后继续你的 AI 创作。</p>
        </div>

        <label class="field">
          <span>用户名</span>
          <el-input
            v-model="username"
            clearable
            placeholder="请输入用户名"
            autocomplete="username"
            size="large"
            autofocus
          />
        </label>

        <label class="field">
          <span>密码</span>
          <el-input
            v-model="password"
            type="password"
            show-password
            placeholder="请输入密码"
            autocomplete="current-password"
            size="large"
          />
        </label>

        <label v-if="captchaEnabled" class="field">
          <span>验证码</span>
          <div class="captcha-row">
            <el-input
              v-model="captchaCode"
              placeholder="输入图中的字符"
              size="large"
              maxlength="8"
            />
            <button
              type="button"
              class="captcha-image"
              :disabled="captchaLoading"
              title="点击刷新验证码"
              aria-label="刷新验证码"
              @click="refreshCaptcha"
            >
              <img v-if="captchaImage" :src="captchaImage" alt="验证码图片，点击刷新" />
              <span v-else>{{ captchaLoading ? '加载中' : '点击获取' }}</span>
            </button>
            <el-button
              :icon="Refresh"
              circle
              :loading="captchaLoading"
              aria-label="刷新验证码"
              title="刷新验证码"
              @click="refreshCaptcha"
            />
          </div>
        </label>

        <el-button type="primary" size="large" native-type="submit" :loading="loading" class="submit">
          <span>进入工作台</span>
          <el-icon v-if="!loading"><ArrowRight /></el-icon>
        </el-button>
        <p class="security-note">你的登录信息将通过加密连接安全传输。</p>
      </form>
    </div>
    <p class="login-footer">AIS · 专注于把想法变成画面</p>
  </main>
</template>

<style scoped>
.login-page {
  --vv-height: 100dvh;
  --vv-offset-top: 0px;
  position: relative;
  box-sizing: border-box;
  /* Prefer visual viewport height when JS has measured it (soft keyboard safe). */
  min-height: 100vh;
  min-height: 100dvh;
  min-height: var(--vv-height, 100dvh);
  max-height: none;
  display: grid;
  place-items: center;
  /* Allow scrolling so focused fields / submit are never permanently covered. */
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  padding: 48px 28px 34px;
  /* Extra bottom room so the submit button can scroll above the keyboard. */
  padding-bottom: max(34px, calc(env(safe-area-inset-bottom, 0px) + 24px));
  background:
    radial-gradient(circle at 5% 12%, rgba(91, 111, 248, .2), transparent 28rem),
    radial-gradient(circle at 92% 9%, rgba(169, 94, 246, .18), transparent 24rem),
    linear-gradient(135deg, #f8f9ff 0%, #f2f4fd 100%);
}
.login-page::before,
.login-page::after {
  position: absolute;
  content: '';
  pointer-events: none;
  border: 1px solid rgba(107, 121, 227, .1);
  border-radius: 50%;
}
.login-page::before { width: 500px; height: 500px; right: -230px; bottom: -270px; }
.login-page::after { width: 320px; height: 320px; left: -185px; bottom: -145px; }
.login-layout {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(300px, 1fr) minmax(360px, 430px);
  width: min(100%, 980px);
  min-height: 560px;
  overflow: hidden;
  border: 1px solid rgba(222, 226, 245, .95);
  border-radius: 28px;
  background: rgba(255, 255, 255, .72);
  box-shadow: 0 28px 90px rgba(45, 59, 129, .15);
  backdrop-filter: blur(18px);
}
.login-intro {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 42px 48px 40px;
  color: #fff;
  background:
    radial-gradient(circle at 82% 18%, rgba(255, 255, 255, .22), transparent 14rem),
    linear-gradient(145deg, #4e65e8 0%, #6354d8 52%, #8e50d8 100%);
}
.intro-brand, .intro-brand > span:last-child { display: flex; }
.intro-brand { align-items: center; gap: 12px; }
.intro-mark {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  font-size: 22px;
  border: 1px solid rgba(255,255,255,.32);
  border-radius: 14px;
  background: rgba(255,255,255,.18);
  box-shadow: 0 10px 22px rgba(31, 34, 115, .18);
}
.intro-brand > span:last-child { flex-direction: column; gap: 3px; line-height: 1.1; }
.intro-brand strong { font-size: 17px; letter-spacing: -.25px; }
.intro-brand small { color: rgba(255,255,255,.72); font-size: 11px; }
.intro-copy { max-width: 390px; margin-top: 52px; }
.intro-kicker, .card-kicker { font-size: 10px; font-weight: 800; letter-spacing: .18em; }
.intro-kicker { color: rgba(255,255,255,.68); }
.intro-copy h1 { margin: 14px 0 16px; font-size: clamp(32px, 4vw, 48px); font-weight: 800; line-height: 1.18; letter-spacing: -.06em; }
.intro-copy h1 em { color: #d9d8ff; font-style: normal; }
.intro-copy p { max-width: 360px; margin: 0; color: rgba(255,255,255,.75); font-size: 14px; line-height: 1.8; }
.intro-features { display: grid; gap: 16px; margin: 48px 0 0; padding: 0; list-style: none; }
.intro-features li { display: flex; align-items: flex-start; gap: 10px; }
.intro-features li > span { color: #d7d4ff; font-size: 16px; line-height: 1.3; }
.intro-features div { display: flex; flex-direction: column; gap: 2px; }
.intro-features strong { font-size: 12px; }
.intro-features small { color: rgba(255,255,255,.62); font-size: 11px; }
.login-card { display: flex; flex-direction: column; justify-content: center; padding: 48px 52px; background: rgba(255,255,255,.92); }
.card-heading { margin-bottom: 30px; }
.card-kicker { color: #6575df; }
.card-heading h2 { margin: 9px 0 7px; color: #253354; font-size: 28px; letter-spacing: -.05em; }
.card-heading p { margin: 0; color: #8791aa; font-size: 13px; }
.field { display: flex; flex-direction: column; gap: 8px; margin-bottom: 18px; }
.field > span { color: #55617b; font-size: 13px; font-weight: 700; }
.captcha-row { display: grid; grid-template-columns: minmax(0, 1fr) 122px 40px; gap: 8px; align-items: center; }
.captcha-image { height: 40px; padding: 0; overflow: hidden; cursor: pointer; border: 1px solid #e0e5f5; border-radius: 10px; background: #f7f8fd; transition: border-color .18s ease, box-shadow .18s ease; }
.captcha-image:hover { border-color: #aab5fa; box-shadow: 0 4px 12px rgba(82, 103, 246, .12); }
.captcha-image:disabled { cursor: wait; opacity: .65; }
.captcha-image img { display: block; width: 100%; height: 100%; object-fit: cover; }
.captcha-image span { display: grid; place-items: center; height: 100%; color: #8a94aa; font-size: 11px; }
.submit { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; height: 46px; margin-top: 8px; border-radius: 12px; background: linear-gradient(110deg, #536bf5, #795be8); box-shadow: 0 10px 22px rgba(82, 103, 246, .25); }

/* Real border so the field itself is rounded, not only the focus glow */
.login-card :deep(.el-input .el-input__wrapper) {
  border: 1px solid #e0e5f5;
  border-radius: 10px;
  box-shadow: none;
  transition: border-color .2s ease, box-shadow .2s ease;
}
.login-card :deep(.el-input .el-input__wrapper:hover) {
  border-color: #aab5fa;
  box-shadow: none;
}
.login-card :deep(.el-input .el-input__wrapper.is-focus) {
  border-color: rgba(91, 111, 248, .65);
  box-shadow: 0 0 0 3px rgba(91, 111, 248, .16);
}
.login-card :deep(.el-input .el-input__inner),
.login-card :deep(.el-input .el-input__inner:focus) {
  outline: none;
  border: 0;
  box-shadow: none;
}
.login-card :deep(.el-input__inner) {
  height: 40px;
  font-size: 14px;
}
.login-card :deep(.el-button:focus-visible) {
  outline: none;
  box-shadow: 0 10px 22px rgba(82, 103, 246, .25), 0 0 0 3px rgba(91, 111, 248, .22);
}
.security-note { margin: 16px 0 0; color: #a2aabd; font-size: 11px; text-align: center; }
.login-footer { position: absolute; z-index: 1; bottom: 13px; margin: 0; color: #9aa3ba; font-size: 11px; }

@media (max-width: 760px) {
  .login-page {
    align-content: start;
    place-items: stretch center;
    padding: 24px 16px calc(42px + env(safe-area-inset-bottom, 0px));
  }
  .login-layout {
    display: block;
    min-height: 0;
    max-width: 100%;
    overflow: visible;
    border-radius: 22px;
  }
  .login-intro { padding: 26px 24px 24px; }
  .intro-copy { margin-top: 34px; }
  .intro-copy h1 { margin: 10px 0; font-size: 30px; }
  .intro-copy p { font-size: 13px; }
  .intro-features { display: none; }
  .login-card { padding: 30px 24px 28px; }
  .card-heading { margin-bottom: 24px; }
  /* Keep footer from colliding with the last field when the page is short. */
  .login-footer { position: static; margin-top: 18px; text-align: center; }
}
@media (max-width: 420px) {
  .login-page { padding-inline: 12px; }
  .login-card { padding-inline: 18px; }
  .captcha-row { grid-template-columns: minmax(0, 1fr) 105px 36px; gap: 6px; }
}
</style>
