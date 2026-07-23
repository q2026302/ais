import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import { VitePWA } from 'vite-plugin-pwa'

function normalizeContextPath(value: string | undefined, fallback = '/ais') {
  const normalized = (value || fallback).trim().replace(/\/+$/, '')
  return normalized === '/' ? '' : normalized || fallback
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

type WorkboxRouteMatchOptions = {
  sameOrigin: boolean
  request: Request
  url: URL
}

/**
 * Workbox serializes route callbacks into the generated service worker. Build
 * the callback without a closure so its path pattern remains available there.
 */
function createSameOriginImageMatcher(pathPattern: RegExp) {
  return new Function(
    '{ sameOrigin, request, url }',
    `return sameOrigin && request.destination === 'image' && ${pathPattern}.test(url.pathname)`,
  ) as (options: WorkboxRouteMatchOptions) => boolean
}

// Workbox's CacheableResponsePlugin can check status codes, but it cannot
// wildcard-match Content-Type. This self-contained plugin is serialized into
// sw.js and ensures only actual image/* responses are written to a cache.
const imageContentTypePlugin = {
  cacheWillUpdate: async ({ response }: { response: Response }) => {
    const contentType = response.headers.get('content-type') || ''
    return contentType.toLowerCase().startsWith('image/') ? response : null
  },
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const contextPath = normalizeContextPath(env.VITE_APP_CONTEXT_PATH)
  const backendContextPath = normalizeContextPath(
    env.VITE_BACKEND_CONTEXT_PATH,
    contextPath || '/ais',
  )
  const backendOrigin = env.VITE_BACKEND_ORIGIN || 'http://localhost:11111'
  const contextApiPrefix = `${contextPath}/api` || '/api'
  // Vite keeps regular production assets relative so Spring can serve the SPA
  // below its servlet context. PWA metadata and the worker instead need an
  // explicit deployment root: their URLs must be stable when the page is at
  // /ais/mobile, and the worker must control the whole AIS context.
  const pwaBase = `${contextPath || ''}/`
  const pwaStartUrl = `${pwaBase}mobile`
  const apiPath = `${contextPath || ''}/api`
  const apiPathPattern = new RegExp(`^${escapeRegExp(apiPath)}(?:/|$)`)
  // These are the persistent binary image paths served by WebConfig. Keep
  // thumbnails separate because the frontend loads these exact controller URLs
  // first: /api/images/{id}/thumbnail and /api/attachments/{id}/thumbnail.
  const thumbnailImagePathPattern = new RegExp(
    `^${escapeRegExp(apiPath)}/(?:images|attachments)/\\d+/thumbnail$`,
  )
  const originalImagePathPattern = new RegExp(
    `^${escapeRegExp(apiPath)}/(?:images|attachments)/[^/]+$`,
  )

  // The backend is normally served below /ais. When the Vite page is opened at
  // http://localhost:5173/, axios requests /api/...; prepend the backend
  // context before proxying. If the page is opened at /ais/, forward that
  // already-prefixed request unchanged (or translate it to another backend
  // context when VITE_BACKEND_CONTEXT_PATH is configured).
  const proxy: Record<string, object> = {
    '/api': {
      target: backendOrigin,
      changeOrigin: true,
      rewrite: (path: string) => `${backendContextPath}${path}`,
    },
  }
  if (contextApiPrefix !== '/api') {
    proxy[contextApiPrefix] = {
      target: backendOrigin,
      changeOrigin: true,
      rewrite: (path: string) => `${backendContextPath}${path.slice(contextPath.length)}`,
    }
  }

  return {
    // Embedded production resources must remain relative to the servlet context;
    // the dev server should use an absolute root so / and /ais/ both work.
    base: mode === 'production' ? './' : '/',
    plugins: [
      vue(),
      vueDevTools(),
      VitePWA({
        // PWA URLs cannot inherit Vite's relative production base: from
        // /ais/mobile, an absolute context path keeps manifest and SW lookup
        // stable while regular application assets remain relative.
        base: pwaBase,
        buildBase: pwaBase,
        scope: pwaBase,
        registerType: 'prompt',
        injectRegister: false,
        devOptions: {
          enabled: false,
        },
        manifest: {
          name: 'AIS AI 创作工作台',
          short_name: 'AIS',
          lang: 'zh-CN',
          display: 'standalone',
          theme_color: '#4f67e8',
          background_color: '#f7f9fd',
          start_url: pwaStartUrl,
          scope: pwaBase,
          icons: [
            {
              src: 'pwa-192x192.png',
              sizes: '192x192',
              type: 'image/png',
            },
            {
              src: 'pwa-512x512.png',
              sizes: '512x512',
              type: 'image/png',
            },
            {
              src: 'pwa-maskable-512x512.png',
              sizes: '512x512',
              type: 'image/png',
              purpose: 'maskable',
            },
          ],
        },
        includeAssets: [
          'favicon.ico',
          'favicon.svg',
          'apple-touch-icon.png',
        ],
        workbox: {
          // The SPA shell is precached for offline navigation. All JSON and
          // business APIs have no runtime route. The only exception is the
          // backend's persistent image binary endpoints below, which also pass
          // the request-destination and response Content-Type image guards.
          navigateFallback: `${pwaBase}index.html`,
          navigateFallbackDenylist: [apiPathPattern],
          runtimeCaching: [
            {
              urlPattern: createSameOriginImageMatcher(thumbnailImagePathPattern),
              handler: 'CacheFirst',
              method: 'GET',
              options: {
                cacheName: 'ais-image-thumbnails',
                cacheableResponse: { statuses: [200] },
                plugins: [imageContentTypePlugin],
                expiration: {
                  maxEntries: 160,
                  maxAgeSeconds: 30 * 24 * 60 * 60,
                  purgeOnQuotaError: true,
                },
              },
            },
            {
              urlPattern: createSameOriginImageMatcher(originalImagePathPattern),
              handler: 'CacheFirst',
              method: 'GET',
              options: {
                cacheName: 'ais-image-originals',
                cacheableResponse: { statuses: [200] },
                plugins: [imageContentTypePlugin],
                expiration: {
                  maxEntries: 24,
                  maxAgeSeconds: 7 * 24 * 60 * 60,
                  purgeOnQuotaError: true,
                },
              },
            },
          ],
          cleanupOutdatedCaches: true,
          clientsClaim: true,
          skipWaiting: false,
        },
      }),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      proxy,
    },
  }
})
