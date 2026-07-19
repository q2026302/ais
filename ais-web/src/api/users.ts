import client from './client'
import { encryptPasswordDigestWithRsaPublicKey, md5Hex } from '@/utils/passwordCrypto'
import type { AuthRole, UserProfile } from './auth'

export interface ManagedUser extends UserProfile {}

export interface CreateUserPayload {
  username: string
  displayName: string
  email: string
  role: AuthRole
  enabled: boolean
}

export interface UpdateUserPayload {
  displayName: string
  email: string
  role: AuthRole
  enabled: boolean
}

async function encryptPassword(password: string) {
  const key = await client.get<{ keyId: string; publicKey: string }>('/api/auth/password-key')
    .then((r) => r.data)
  return {
    keyId: key.keyId,
    encryptedPassword: await encryptPasswordDigestWithRsaPublicKey(key.publicKey, md5Hex(password)),
  }
}

export const usersApi = {
  list(): Promise<ManagedUser[]> {
    return client.get('/api/admin/users').then((r) => r.data)
  },

  async create(payload: CreateUserPayload, password: string): Promise<ManagedUser> {
    const encrypted = await encryptPassword(password)
    return client.post('/api/admin/users', { ...payload, ...encrypted }).then((r) => r.data)
  },

  update(id: number, payload: UpdateUserPayload): Promise<ManagedUser> {
    return client.put(`/api/admin/users/${id}`, payload).then((r) => r.data)
  },

  remove(id: number): Promise<void> {
    return client.delete(`/api/admin/users/${id}`).then(() => undefined)
  },

  async resetPassword(id: number, password: string): Promise<void> {
    const encrypted = await encryptPassword(password)
    await client.put(`/api/admin/users/${id}/password`, encrypted)
  },
}
