/**
 * Servlet-context helpers.
 *
 * The SPA is typically served under a context path (default `/ais`) and then
 * uses client-side routes such as `/mobile/chat/:id`. `document.baseURI`
 * follows the current history URL, so a naive `new URL('./', baseURI)` on a
 * nested route yields `/ais/mobile/chat/` instead of `/ais/` — which breaks
 * thumbnail URLs (`getThumbnailUrl` → `/ais/mobile/chat/api/images/...`),
 * axios baseURL on deep-link reloads, and router history.
 *
 * We recover the servlet context by stripping known top-level SPA route
 * prefixes from the current pathname.
 */

/** Top-level client routes registered in `src/router/index.ts` (no leading slash). */
const SPA_ROUTE_SEGMENTS = ['login', 'mobile', 'feishu', 'pwa', 'admin', 'profile'] as const

const SPA_ROUTE_RE = new RegExp(
  `^(.*)/(?:${SPA_ROUTE_SEGMENTS.join('|')})(?:/|$)`,
)

let cachedBasePath: string | null = null

function normalizeBasePath(path: string): string {
  if (!path || path === '/') return '/'
  return path.endsWith('/') ? path : `${path}/`
}

/**
 * Derive the servlet context (with trailing slash) from a location pathname.
 * Pure — safe to unit-test without a DOM.
 *
 * Examples (context `/ais`):
 *   /ais/                    → /ais/
 *   /ais/mobile              → /ais/
 *   /ais/mobile/chat/12      → /ais/
 *   /ais/feishu/gallery      → /ais/
 *   /mobile/chat/1           → /          (no context, Vite dev root)
 */
export function deriveAppBasePath(pathname: string): string {
  const path = pathname.startsWith('/') ? pathname : `/${pathname}`

  // Exact SPA root without context: /mobile, /login, …
  for (const segment of SPA_ROUTE_SEGMENTS) {
    if (path === `/${segment}` || path.startsWith(`/${segment}/`)) {
      return '/'
    }
  }

  // Context + SPA route: /ais/mobile/chat/12 → capture "/ais"
  const match = SPA_ROUTE_RE.exec(path)
  if (match) {
    const context = match[1] || ''
    return normalizeBasePath(context || '/')
  }

  // Context root only: /ais or /ais/
  if (path.endsWith('/')) return path || '/'
  return normalizeBasePath(path)
}

/** Returns the servlet context in which the SPA was loaded, including a slash. */
export function getAppBasePath(): string {
  if (typeof document === 'undefined') return '/'
  if (cachedBasePath != null) return cachedBasePath

  // Honour an absolute <base href> when present (tests / alternate deployments).
  // Relative bases such as "./" follow the current history URL and must NOT be
  // treated as the servlet context — they reintroduce the nested-route bug.
  try {
    const baseEl = document.querySelector('base[href]')
    const href = baseEl?.getAttribute('href')?.trim()
    if (href && (href.startsWith('/') || /^[a-z][a-z0-9+.-]*:/i.test(href))) {
      const pathname = new URL(href, document.URL).pathname
      cachedBasePath = normalizeBasePath(pathname)
      return cachedBasePath
    }
  } catch {
    /* ignore */
  }

  const pathname =
    typeof window !== 'undefined' && window.location?.pathname
      ? window.location.pathname
      : new URL('./', document.baseURI).pathname

  cachedBasePath = deriveAppBasePath(pathname)
  return cachedBasePath
}

/**
 * Clears the memoized base path. Intended for tests; production code should
 * not need this because the servlet context is fixed for the document lifetime.
 */
export function resetAppBasePathCache(): void {
  cachedBasePath = null
}

/** Converts backend-relative resource URLs (for example /api/images/x.png)
 * into URLs below the current servlet context. */
export function resolveAppUrl(url: string | null | undefined): string | null | undefined {
  if (!url || !url.startsWith('/')) return url
  const base = getAppBasePath()
  return `${base.replace(/\/$/, '')}${url}`
}
