import type { RouteLocationNormalizedLoaded, RouteLocationRaw } from 'vue-router'

/** Query/source value that marks navigation as coming from a mobile workbench entry. */
export type MobileWorkspaceSource = 'mobile' | 'feishu'

const MOBILE_SOURCES = new Set<string>(['mobile', 'feishu'])

export function isMobileWorkspaceSource(value: unknown): value is MobileWorkspaceSource {
  return typeof value === 'string' && MOBILE_SOURCES.has(value)
}

/**
 * Resolve which mobile workbench entry is active:
 * - route meta / path / name when on the workbench itself
 * - `?source=mobile|feishu` when visiting desktop pages from a mobile entry
 */
export function getMobileWorkspaceSource(
  route: Pick<RouteLocationNormalizedLoaded, 'name' | 'path' | 'query' | 'meta'>,
): MobileWorkspaceSource | null {
  const fromQuery = route.query.source
  if (isMobileWorkspaceSource(fromQuery)) return fromQuery

  const fromMeta = route.meta.mobileEntry
  if (isMobileWorkspaceSource(fromMeta)) return fromMeta

  if (route.name === 'feishu-h5' || route.path === '/feishu') return 'feishu'
  if (route.name === 'mobile-workbench' || route.path === '/mobile') return 'mobile'
  return null
}

export function mobileWorkspaceLocation(source: MobileWorkspaceSource): RouteLocationRaw {
  return source === 'feishu' ? { name: 'feishu-h5' } : { name: 'mobile-workbench' }
}

export function mobileWorkspacePath(source: MobileWorkspaceSource): string {
  return source === 'feishu' ? '/feishu' : '/mobile'
}

/** Attach mobile source query when navigating from a mobile workbench entry. */
export function withMobileSource(
  location: { name: string } | { path: string },
  source: MobileWorkspaceSource | null,
): RouteLocationRaw {
  if (!source) return location
  return { ...location, query: { source } }
}
