import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi, type AuthRole, type LoginPayload } from '@/api/auth'

const TOKEN_KEY = 'ais.auth.token'
const ROLE_KEY = 'ais.auth.role'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const role = ref<AuthRole | null>((localStorage.getItem(ROLE_KEY) as AuthRole | null) || null)
  const securityEnabled = ref(true)
  const captchaEnabled = ref(true)
  const bootstrapped = ref(false)

  const isAuthenticated = computed(() => {
    if (!securityEnabled.value) return true
    return Boolean(token.value)
  })
  const isAdmin = computed(() => {
    if (!securityEnabled.value) return true
    return role.value === 'ADMIN'
  })

  function persist() {
    if (token.value) localStorage.setItem(TOKEN_KEY, token.value)
    else localStorage.removeItem(TOKEN_KEY)
    if (role.value) localStorage.setItem(ROLE_KEY, role.value)
    else localStorage.removeItem(ROLE_KEY)
  }

  async function bootstrap() {
    try {
      const status = await authApi.status()
      securityEnabled.value = status.securityEnabled
      captchaEnabled.value = status.captchaEnabled
      if (!status.securityEnabled) {
        token.value = token.value || 'security-disabled'
        role.value = 'ADMIN'
        persist()
        bootstrapped.value = true
        return
      }
      if (!token.value) {
        bootstrapped.value = true
        return
      }
      const me = await authApi.me()
      if (!me.authenticated) {
        clear()
      } else {
        role.value = me.role || role.value
        persist()
      }
    } catch {
      // Keep local token; request interceptor will clear on 401.
    } finally {
      bootstrapped.value = true
    }
  }

  async function login(payload: LoginPayload) {
    const result = await authApi.login(payload)
    token.value = result.token
    role.value = result.role
    securityEnabled.value = true
    persist()
    return result
  }

  function clear() {
    token.value = null
    role.value = null
    persist()
  }

  async function logout() {
    try {
      await authApi.logout()
    } catch {
      // ignore network errors on logout
    }
    clear()
  }

  return {
    token,
    role,
    securityEnabled,
    captchaEnabled,
    bootstrapped,
    isAuthenticated,
    isAdmin,
    bootstrap,
    login,
    logout,
    clear,
  }
})
