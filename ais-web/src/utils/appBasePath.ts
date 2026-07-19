/** Returns the servlet context in which the SPA was loaded, including a slash. */
export function getAppBasePath(): string {
  if (typeof document === 'undefined') return '/'
  const pathname = new URL('./', document.baseURI).pathname
  return pathname.endsWith('/') ? pathname : `${pathname}/`
}

/** Converts backend-relative resource URLs (for example /api/images/x.png)
 * into URLs below the current servlet context. */
export function resolveAppUrl(url: string | null | undefined): string | null | undefined {
  if (!url || !url.startsWith('/')) return url
  const base = getAppBasePath()
  return `${base.replace(/\/$/, '')}${url}`
}
