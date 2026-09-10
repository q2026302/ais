import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi, type AuthRole, type LoginPayload } from '@/api/auth'
import { useFavoriteStore } from '@/stores/favorite'

const TOKEN_KEY = 'ais.auth.token'
const ROLE_KEY = 'ais.auth.role'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const role = ref<AuthRole | null>((localStorage.getItem(ROLE_KEY) as AuthRole | null) || null)
  const securityEnabled = ref(true)
  const captchaEnabled = ref(true)
  const bootstrapped = ref(false)
  /** Persisted id of the signed-in user; the work library uses it to spot own records. */
  const userId = ref<number | null>(null)

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

  /** Refresh role and user id from the server and share the identity with the work library. */
  async function loadIdentity() {
    const me = await authApi.me()
    if (!me.authenticated) {
      clear()
      return
    }
    const nextUserId = me.id ?? null
    // A different identity than the one we had cached (anomalous switch, token
    // swap): drop the previous account's work-library state before reusing it.
    if (userId.value != null && nextUserId != null && userId.value !== nextUserId) {
      useFavoriteStore().reset()
    }
    userId.value = nextUserId
    role.value = me.role || role.value
    persist()
    useFavoriteStore().setCurrentUser(userId.value)
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
        try {
          // Security-disabled still acts as the persisted administrator; resolve
          // its id so own-record detection works in the work library.
          await loadIdentity()
        } catch {
          // Identity stays unknown; the library simply skips own-record syncing.
        }
        bootstrapped.value = true
        return
      }
      if (!token.value) {
        bootstrapped.value = true
        return
      }
      await loadIdentity()
    } catch {
      // Keep local token; request interceptor will clear on 401.
    } finally {
      bootstrapped.value = true
    }
  }

  async function login(payload: LoginPayload) {
    const result = await authApi.login(payload)
    // Signing in (possibly as a different account) must never reuse the previous
    // account's work-library state.
    useFavoriteStore().reset()
    token.value = result.token
    role.value = result.role
    securityEnabled.value = true
    persist()
    try {
      await loadIdentity()
    } catch {
      // The token is valid even if the identity refresh fails; retry on next boot.
    }
    return result
  }

  function clear() {
    token.value = null
    role.value = null
    userId.value = null
    persist()
    // The work library is per-user; never leak the previous account's state.
    useFavoriteStore().reset()
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
    userId,
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
