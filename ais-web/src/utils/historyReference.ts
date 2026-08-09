export interface HistoryReferenceSource {
  url: string
  thumbUrl?: string
}

export interface ReuseAttachmentRequest {
  fileUrl: string
  originalName?: string
  contentType?: string
}

/** Select the server-side file that matches the reference panel's quality choice. */
export function selectHistorySourceUrl(item: HistoryReferenceSource, useOriginal: boolean): string {
  return useOriginal ? item.url : (item.thumbUrl || item.url)
}

/** Remove the SPA servlet context before sending a resource URL to the API. */
export function stripAppBasePath(fileUrl: string, appBasePath: string): string {
  const base = appBasePath.replace(/\/$/, '')
  return fileUrl.startsWith(base) ? fileUrl.slice(base.length) || '/' : fileUrl
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
