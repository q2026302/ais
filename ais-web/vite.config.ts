import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

function normalizeContextPath(value: string | undefined, fallback = '/ais') {
  const normalized = (value || fallback).trim().replace(/\/+$/, '')
  return normalized === '/' ? '' : normalized || fallback
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
