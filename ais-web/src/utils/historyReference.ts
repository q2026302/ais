import type { DrawReference, UploadResponse } from '@/types'

export interface HistoryReferenceSource {
  url: string
  thumbUrl?: string
}

export interface ReuseAttachmentRequest {
  fileUrl: string
  originalName?: string
  contentType?: string
}

/** Build a reference item from a freshly uploaded attachment (a real attachment record). */
export function referenceFromUpload(upload: UploadResponse): DrawReference {
  return {
    key: `upload-${upload.id}`,
    name: upload.originalName,
    contentType: upload.contentType,
    fileSize: upload.fileSize,
    url: upload.fileUrl,
    thumbnailUrl: upload.thumbnailUrl || '',
    kind: 'upload',
    attachmentId: upload.id,
  }
}

/**
 * Build a reference item that reuses an existing server-side file (a history
 * generated image or an existing attachment). It carries no attachment id: the
 * draw request sends its URL via `referenceUrls`, so no attachment is created and
 * the physical file is never copied.
 */
export function referenceFromHistory(
  source: HistoryReferenceSource,
  name: string,
  contentType: string,
): DrawReference {
  return {
    key: `history-${source.url}`,
    name,
    contentType,
    fileSize: 0,
    url: source.url,
    thumbnailUrl: source.thumbUrl || '',
    kind: 'history',
  }
}

/** Remove the SPA servlet context before sending a resource URL to the API. */
export function stripAppBasePath(fileUrl: string, appBasePath: string): string {
  const base = appBasePath.replace(/\/$/, '')
  return fileUrl.startsWith(base) ? fileUrl.slice(base.length) || '/' : fileUrl
}

/** Reference URL to send to the draw endpoint (server-side path, servlet context stripped). */
export function referenceUrlForBackend(fileUrl: string, appBasePath: string): string {
  return stripAppBasePath(fileUrl, appBasePath)
}

/** Build the JSON body used by the server-side attachment reuse endpoint. */
export function buildReuseAttachmentRequest(
  fileUrl: string,
  appBasePath: string,
  originalName?: string,
  contentType?: string,
): ReuseAttachmentRequest {
  const request: ReuseAttachmentRequest = { fileUrl: stripAppBasePath(fileUrl, appBasePath) }
  if (originalName) request.originalName = originalName
  if (contentType) request.contentType = contentType
  return request
}
