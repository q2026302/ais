import { resolveAppUrl } from '@/utils/appBasePath'

/**
 * Thumbnail URL for a message image:
 * /api/images/{id}/thumbnail?size=small|medium|large
 *
 * The backend resolves the original image from the message record and
 * serves (or lazily generates) the thumbnail file for the requested size.
 */
export function getThumbnailUrl(messageId: number | null | undefined, size: 'small' | 'medium' | 'large' = 'small'): string {
  if (messageId == null) return ''
  return resolveAppUrl(`/api/images/${messageId}/thumbnail?size=${size}`) || ''
}

/**
 * Thumbnail URL for a user-uploaded attachment image:
 * /api/attachments/{id}/thumbnail
 */
export function getAttachmentThumbnailUrl(attachmentId: number | null | undefined, size: 'small' | 'medium' = 'small'): string {
  if (attachmentId == null) return ''
  return resolveAppUrl(`/api/attachments/${attachmentId}/thumbnail?size=${size}`) || ''
}
