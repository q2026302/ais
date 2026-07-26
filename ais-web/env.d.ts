/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

import type { MobileEntry } from '@/utils/mobileWorkspace'

export {}

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    requiresAdmin?: boolean
    /** Hide desktop app chrome (mobile workbench entries). */
    embedded?: boolean
    /** Which formal mobile entry rendered the workbench. */
    mobileEntry?: MobileEntry
  }
}
