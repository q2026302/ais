import client from './client'
import { resolveAppUrl } from '@/utils/appBasePath'
import type { Favorite, FavoriteReferenceImage, PageResponse } from '@/types'

/**
 * Work-library (作品库 / 收藏) API.
 *
 * The backend returns raw context-relative paths; `resolveAppUrl` prefixes the
 * servlet context the same way `sessionApi.normalizeMessage` does for messages.
 * Signature-less fields (`?sig=...`) ride along untouched.
 */
function normalizeReference(reference: FavoriteReferenceImage): FavoriteReferenceImage {
  return {
    ...reference,
    fileUrl: resolveAppUrl(reference.fileUrl) || reference.fileUrl,
    thumbnailUrl: resolveAppUrl(reference.thumbnailUrl) || null,
  }
}

function normalizeFavorite(favorite: Favorite): Favorite {
  return {
    ...favorite,
    imageUrl: resolveAppUrl(favorite.imageUrl) || favorite.imageUrl,
    thumbnailUrl: resolveAppUrl(favorite.thumbnailUrl) || null,
    referenceImages: (favorite.referenceImages || []).map(normalizeReference),
  }
}

export const favoriteApi = {
  /** Page through the current user's work library (admins see every user's). */
  list(page = 0, size = 20): Promise<PageResponse<Favorite>> {
    return client
      .get('/api/favorites', { params: { page, size } })
      .then((r) => {
        const data = r.data as PageResponse<Favorite>
        return { ...data, content: (data.content || []).map(normalizeFavorite) }
      })
  },

  /** Save a generated-image message to the work library. Idempotent. */
  add(messageId: number): Promise<Favorite> {
    return client.post('/api/favorites', { messageId }).then((r) => normalizeFavorite(r.data as Favorite))
  },

  /** Remove one saved work by its own record id. Idempotent. */
  remove(favoriteId: number): Promise<void> {
    return client.delete(`/api/favorites/${favoriteId}`)
  },
}
