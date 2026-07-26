import type { RouteLocationNormalizedLoaded, RouteLocationRaw } from 'vue-router'

/** Formal mobile workbench entries shared by route metadata and navigation. */
export const MOBILE_ENTRIES = ['mobile', 'feishu', 'pwa'] as const

export type MobileEntry = (typeof MOBILE_ENTRIES)[number]
/** Query/source value that marks navigation as coming from a mobile workbench entry. */
export type MobileWorkspaceSource = MobileEntry

const MOBILE_SOURCES = new Set<string>(MOBILE_ENTRIES)

export function isMobileWorkspaceSource(value: unknown): value is MobileWorkspaceSource {
  return typeof value === 'string' && MOBILE_SOURCES.has(value)
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
