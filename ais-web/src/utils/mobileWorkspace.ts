import type { RouteLocationNormalizedLoaded, RouteLocationRaw } from 'vue-router'
import { isStandaloneDisplayMode } from '@/utils/visualViewport'

/** Formal mobile workbench entries shared by route metadata and navigation. */
export const MOBILE_ENTRIES = ['mobile', 'feishu', 'pwa'] as const

export type MobileEntry = (typeof MOBILE_ENTRIES)[number]
/** Query/source value that marks navigation as coming from a mobile workbench entry. */
export type MobileWorkspaceSource = MobileEntry

const MOBILE_SOURCES = new Set<string>(MOBILE_ENTRIES)
const MOBILE_VIEWPORT_QUERY = '(max-width: 768px)'

export function isMobileWorkspaceSource(value: unknown): value is MobileWorkspaceSource {
  return typeof value === 'string' && MOBILE_SOURCES.has(value)
}

/** Feishu / Lark in-app browser (mobile or desktop embedded H5). */
export function isFeishuUserAgent(win: Window & typeof globalThis = window): boolean {
  if (typeof win === 'undefined') return false
  try {
    return /Lark|Feishu|LarkLocale/i.test(win.navigator?.userAgent || '')
  } catch {
    return false
  }
}

/**
 * Phone / tablet client that should use the mobile workbench instead of HomeView.
 * Prefer CSS media query; fall back to coarse pointer / touch points.
 */
export function isMobileClient(win: Window & typeof globalThis = window): boolean {
  if (typeof win === 'undefined') return false
  try {
    if (win.matchMedia?.(MOBILE_VIEWPORT_QUERY).matches) return true
    if (win.matchMedia?.('(hover: none) and (pointer: coarse)').matches) return true
    const touchPoints = win.navigator?.maxTouchPoints ?? 0
    if (touchPoints > 0 && win.matchMedia?.('(max-width: 1024px)').matches) return true
  } catch {
    /* ignore */
  }
  return false
}

/**
 * Default landing path after login when no explicit `redirect` query is present.
 * Priority: PWA standalone → Feishu UA → mobile viewport → desktop home.
 */
export function resolveDefaultPostLoginPath(
  win: Window & typeof globalThis = window,
): string {
  if (typeof win === 'undefined') return '/'
  if (isStandaloneDisplayMode(win) || win.location.pathname.includes('/pwa')) {
    return '/pwa/sessions'
  }
  if (isFeishuUserAgent(win)) return '/feishu/sessions'
  if (isMobileClient(win)) return '/mobile/sessions'
  return '/'
}

/** Normalize a redirect query value; fall back to environment-aware default. */
export function resolvePostLoginTarget(
  redirect: unknown,
  win: Window & typeof globalThis = window,
): string {
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    if (redirect === '/login') return resolveDefaultPostLoginPath(win)
    return redirect
  }
  return resolveDefaultPostLoginPath(win)
}

/**
 * Resolve which mobile workbench entry is active:
 * - route meta / path / name when on the workbench itself
 * - `?source=mobile|feishu|pwa` when visiting desktop pages from a mobile entry
 */
export function getMobileWorkspaceSource(
  route: Pick<RouteLocationNormalizedLoaded, 'name' | 'path' | 'query' | 'meta'>,
): MobileWorkspaceSource | null {
  const fromQuery = route.query.source
  if (isMobileWorkspaceSource(fromQuery)) return fromQuery

  const fromMeta = route.meta.mobileEntry
  if (isMobileWorkspaceSource(fromMeta)) return fromMeta

  const name = route.name?.toString()
  if (
    name === 'feishu-sessions' ||
    name === 'feishu-chat' ||
    name === 'feishu-gallery' ||
    name === 'feishu-profile' ||
    route.path.startsWith('/feishu/')
  ) {
    return 'feishu'
  }
  if (
    name === 'mobile-sessions' ||
    name === 'mobile-chat' ||
    name === 'mobile-gallery' ||
    name === 'mobile-profile' ||
    route.path.startsWith('/mobile/')
  ) {
    return 'mobile'
  }
  if (
    name === 'pwa-sessions' ||
    name === 'pwa-chat' ||
    name === 'pwa-gallery' ||
    name === 'pwa-profile' ||
    route.path.startsWith('/pwa/')
  ) {
    return 'pwa'
  }
  return null
}

export function mobileWorkspaceLocation(source: MobileWorkspaceSource): RouteLocationRaw {
  return { path: mobileWorkspacePath(source) }
}

export function mobileWorkspacePath(source: MobileWorkspaceSource): string {
  return `/${source}`
}

/** Attach mobile source query when navigating from a mobile workbench entry. */
export function withMobileSource(
  location: { name: string } | { path: string },
  source: MobileWorkspaceSource | null,
): RouteLocationRaw {
  if (!source) return location
  return { ...location, query: { source } }
}
