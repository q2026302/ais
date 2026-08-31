import type { Attachment, Message } from '@/types'

/**
 * Image/attachment URL helpers.
 *
 * Backend stage 1 moved `/api/images/**` and `/api/attachments/**` behind
 * authentication and now returns *signed* URLs (`?sig=...`, TTL 1h) in every
 * DTO field (`MessageResponse.imageUrl`, `AttachmentResponse.fileUrl`, …). The
 * one gap was the frontend-built thumbnail URLs, which were assembled from a
 * numeric id and carried no signature. The backend now also emits a signed
 * `thumbnailUrl` on `MessageResponse` / `AttachmentResponse`, and the helpers
 * below build the final render URL by appending the `size` query parameter.
 *
 * The signature binds only the path (`/api/images/{id}/thumbnail`), not the
 * query string, so `size` may be swapped freely without re-signing.
 */

export type ThumbnailSize = 'small' | 'medium'

/** Append (or replace) the `size` query parameter on an already-signed URL. */
function withSizeParam(url: string, size: ThumbnailSize): string {
  if (!url) return ''
  const base = url.split('#')[0] ?? url
  // The signed URL already carries `?sig=...`; drop any size the server may
  // have included so we always apply the caller's requested size exactly once.
  const stripped = base.replace(/[?&]size=[^&#]*/g, '')
  const join = stripped.includes('?') ? '&' : '?'
  return `${stripped}${join}size=${size}`
}

/**
 * Build the signed thumbnail URL for an AI-generated image message. Falls back
 * to an empty string when the message has no server-issued `thumbnailUrl`
 * (callers should then fall back to `message.imageUrl`).
 */
export function getThumbnailUrl(
  message: Message | number | null | undefined,
  size: ThumbnailSize = 'small',
): string {
  if (message == null) return ''
  // Legacy dead-code callers pass a numeric id; they cannot sign, so return empty.
  if (typeof message === 'number') return ''
  if (!message.thumbnailUrl) return ''
  return withSizeParam(message.thumbnailUrl, size)
}

/**
 * Build the signed thumbnail URL for a user-uploaded attachment. Falls back to
 * an empty string when the attachment has no server-issued `thumbnailUrl`
 * (callers should then fall back to `attachment.fileUrl`).
 */
export function getAttachmentThumbnailUrl(
  attachment: Attachment | number | null | undefined,
  size: ThumbnailSize = 'small',
): string {
  if (attachment == null) return ''
  // Legacy dead-code callers pass a numeric id; they cannot sign, so return empty.
  if (typeof attachment === 'number') return ''
  if (!attachment.thumbnailUrl) return ''
  return withSizeParam(attachment.thumbnailUrl, size)
}

/**
 * Read the `size` query value of a URL, or null when absent.
 */
function queryParam(url: string, key: string): string | null {
  const queryIndex = url.indexOf('?')
  if (queryIndex < 0) return null
  const query = url.slice(queryIndex + 1).split('#')[0] ?? ''
  for (const part of query.split('&')) {
    const eq = part.indexOf('=')
    if (eq < 0) continue
    if (part.slice(0, eq) === key) {
      try {
        return decodeURIComponent(part.slice(eq + 1).replace(/\+/g, ' '))
      } catch {
        return part.slice(eq + 1)
      }
    }
  }
  return null
}

/**
 * Decode a base64url string (no padding) to UTF-8 text.
 */
function base64UrlDecode(input: string): string {
  const base64 = input.replace(/-/g, '+').replace(/_/g, '/')
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
  const binary = atob(padded)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
  return new TextDecoder('utf-8').decode(bytes)
}

/**
 * Determine whether a signed resource URL's signature has expired.
 *
 * The `sig` value is `base64url(json).hmac`; the json payload carries the
 * server-side `exp` (unix seconds) and is readable (not encrypted). Reading it
 * client-side is used only to decide whether an `<img>` load failure is an
 * expiry problem worth re-fetching — never to mint or verify signatures.
 *
 * Returns false for URLs without a signature (public test images, legacy
 * data) so those failures never trigger a recovery refresh.
 */
export function signedUrlExpired(url: string): boolean {
  const sig = queryParam(url, 'sig')
  if (!sig) return false
  const body = sig.split('.')[0]
  if (!body) return false
  try {
    const payload = JSON.parse(base64UrlDecode(body)) as { exp?: unknown }
    const exp = typeof payload.exp === 'number' ? payload.exp : Number(payload.exp)
    if (!Number.isFinite(exp)) return false
    return exp * 1000 <= Date.now()
  } catch {
    return false
  }
}
