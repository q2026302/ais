import { resolveAppUrl } from '@/utils/appBasePath'

export function getThumbnailUrl(messageId: number | null | undefined, size: 'small' | 'medium' = 'small'): string {
  if (messageId == null) return ''
  return resolveAppUrl(`/api/images/${messageId}/thumbnail?size=${size}`) || ''
}

export function getAttachmentThumbnailUrl(attachmentId: number | null | undefined, size: 'small' | 'medium' = 'small'): string {
  if (attachmentId == null) return ''
  return resolveAppUrl(`/api/attachments/${attachmentId}/thumbnail?size=${size}`) || ''
}
