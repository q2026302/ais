import { resolveAppUrl } from '@/utils/appBasePath'

/**
 * Thumbnail URL for a message image:
 * /api/images/{id}/thumbnail
 *
 * The backend resolves the original image from the message record and
 * serves (or lazily generates) the thumbnail file.
 */
export function getThumbnailUrl(messageId: number | null | undefined): string {
  if (messageId == null) return ''
  return resolveAppUrl(`/api/images/${messageId}/thumbnail`) || ''
}

/**
 * Thumbnail URL for a user-uploaded attachment image:
 * /api/attachments/{id}/thumbnail
 */
export function getAttachmentThumbnailUrl(attachmentId: number | null | undefined): string {
  if (attachmentId == null) return ''
  return resolveAppUrl(`/api/attachments/${attachmentId}/thumbnail`) || ''
}
