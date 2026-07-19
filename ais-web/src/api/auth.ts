import client from './client'
import { encryptPasswordDigestWithRsaPublicKey, md5Hex } from '@/utils/passwordCrypto'

export type AuthRole = 'USER' | 'ADMIN'

export interface LoginResponse {
  token: string
  role: AuthRole
  subject: string
  expiresInSeconds: number
}

export interface UserProfile {
  id: number | null
  username: string
  subject?: string
  displayName: string | null
  email: string | null
  role: AuthRole
  enabled: boolean
  createdAt: string | null
  updatedAt: string | null
}

export interface AuthMeResponse extends Partial<UserProfile> {
  authenticated: boolean
  role?: AuthRole
  subject?: string
  securityEnabled: boolean
  message?: string
}

export interface AuthStatusResponse {
  securityEnabled: boolean
  hasUsers: boolean
  captchaEnabled: boolean
  initialAdminConfigured: boolean
  passwordTransport?: string
  passwordStorage?: string
}

export interface CaptchaResponse {
  enabled: boolean
  captchaId?: string
  imageBase64?: string
  expiresInSeconds?: number
}

export interface PasswordKeyResponse {
  keyId: string
  publicKey: string
  algorithm: string
  expiresInSeconds: number
}

export interface LoginPayload {
  username: string
  passwordDigest: string
  captchaId?: string
  captchaCode?: string
}

export const authApi = {
  async login(payload: LoginPayload): Promise<LoginResponse> {
    const key = await client.get<PasswordKeyResponse>('/api/auth/password-key').then((r) => r.data)
    const encryptedPassword = await encryptPasswordDigestWithRsaPublicKey(
      key.publicKey,
      payload.passwordDigest,
    )
    return client
      .post('/api/auth/login', {
        username: payload.username,
        keyId: key.keyId,
        encryptedPassword,
        captchaId: payload.captchaId,
        captchaCode: payload.captchaCode,
      })
      .then((r) => r.data)
  },

  captcha(): Promise<CaptchaResponse> {
    return client.get('/api/auth/captcha').then((r) => r.data)
  },

  passwordKey(): Promise<PasswordKeyResponse> {
    return client.get('/api/auth/password-key').then((r) => r.data)
  },

  me(): Promise<AuthMeResponse> {
    return client.get('/api/auth/me').then((r) => r.data)
  },

  status(): Promise<AuthStatusResponse> {
    return client.get('/api/auth/status').then((r) => r.data)
  },

  updateProfile(payload: { displayName: string; email: string }): Promise<UserProfile> {
    return client.put('/api/auth/me', payload).then((r) => r.data)
  },

  async changePassword(currentPassword: string, newPassword: string): Promise<{ ok: boolean }> {
    const [currentKey, newKey] = await Promise.all([authApi.passwordKey(), authApi.passwordKey()])
    const [currentEncryptedPassword, newEncryptedPassword] = await Promise.all([
      encryptPasswordDigestWithRsaPublicKey(currentKey.publicKey, md5Hex(currentPassword)),
      encryptPasswordDigestWithRsaPublicKey(newKey.publicKey, md5Hex(newPassword)),
    ])
    return client.put('/api/auth/password', {
      currentKeyId: currentKey.keyId,
      currentEncryptedPassword,
      newKeyId: newKey.keyId,
      newEncryptedPassword,
    }).then((r) => r.data)
  },

  logout(): Promise<{ ok: boolean }> {
    return client.post('/api/auth/logout').then((r) => r.data)
  },
}
