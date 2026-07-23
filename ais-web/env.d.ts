/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

export {}

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    requiresAdmin?: boolean
    /** Hide desktop app chrome (mobile workbench entries). */
    embedded?: boolean
    /** Which formal mobile entry rendered the workbench: mobile | feishu. */
    mobileEntry?: 'mobile' | 'feishu'
  }
}
