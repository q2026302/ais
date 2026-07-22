/**
 * Derive thumbnail URL from original image URL by naming convention:
 * /api/images/generated/2026/07/22/abc123.png
 * → /api/images/generated/2026/07/22/abc123_thumb.png
 */
export function getThumbnailUrl(imageUrl: string | null | undefined): string {
  if (!imageUrl) return ''
  const [path = '', query] = imageUrl.split('?', 2)
  const extIndex = path.lastIndexOf('.')
  if (extIndex === -1) return imageUrl
  const thumbPath = path.substring(0, extIndex) + '_thumb.png'
  return query !== undefined ? `${thumbPath}?${query}` : thumbPath
}
