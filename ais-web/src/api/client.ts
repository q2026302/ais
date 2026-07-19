import axios from 'axios'
import { getAppBasePath } from '@/utils/appBasePath'

const TOKEN_KEY = 'ais.auth.token'
const ROLE_KEY = 'ais.auth.role'

const client = axios.create({
  baseURL: getAppBasePath(),
  timeout: 300000, // Chat default; image requests override this with a longer timeout
  headers: {
    'Content-Type': 'application/json',
  },
})

function responseErrorMessage(data: unknown): string | null {
  if (typeof data === 'string' && data.trim()) return data.trim()
  if (!data || typeof data !== 'object') return null

  const payload = data as Record<string, unknown>
  if (typeof payload.message === 'string' && payload.message.trim()) return payload.message.trim()
  if (typeof payload.detail === 'string' && payload.detail.trim()) return payload.detail.trim()
  if (typeof payload.error === 'string' && payload.error.trim()) return payload.error.trim()
  if (payload.error && typeof payload.error === 'object') {
    const providerError = payload.error as Record<string, unknown>
    if (typeof providerError.message === 'string' && providerError.message.trim()) {
      return providerError.message.trim()
    }
    try {
      return JSON.stringify(providerError)
    } catch {
      return String(providerError)
    }
  }
  return null
}

async function blobErrorMessage(data: unknown): Promise<string | null> {
  if (!(data instanceof Blob)) return responseErrorMessage(data)
  try {
    const text = await data.text()
    if (!text.trim()) return null
    try {
      return responseErrorMessage(JSON.parse(text))
    } catch {
      return text.trim()
    }
  } catch {
    return null
  }
}

function clearStoredAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ROLE_KEY)
}

client.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token && token !== 'security-disabled') {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  // multipart uploads should not force JSON content-type
  if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
    if (config.headers) {
      delete (config.headers as Record<string, unknown>)['Content-Type']
    }
  }
  return config
})

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (axios.isCancel(error)) {
      const cancelled = new Error('请求已终止')
      cancelled.name = 'CanceledError'
      return Promise.reject(cancelled)
    }

    const status = error.response?.status
    const requestUrl = String(error.config?.url || '')
    if (status === 401 && !requestUrl.includes('/api/auth/login') && !requestUrl.includes('/api/auth/status')) {
      clearStoredAuth()
      const current = `${window.location.pathname}${window.location.search}`
      const basePath = getAppBasePath().replace(/\/$/, '')
      if (!current.startsWith(`${basePath}/login`)) {
        const redirect = encodeURIComponent(current || `${basePath}/`)
        window.location.assign(`${basePath}/login?redirect=${redirect}`)
      }
    }

    const msg =
      (await blobErrorMessage(error.response?.data)) ||
      responseErrorMessage(error.response?.data) ||
      error.message ||
      'Unknown error'
    console.error('API Error:', msg)
    return Promise.reject(new Error(msg))
  },
)

export default client
