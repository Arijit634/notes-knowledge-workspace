import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

function backendOrigin(): string {
  const value = process.env.NKW_BACKEND_ORIGIN ?? 'http://127.0.0.1:8080'
  try {
    const origin = new URL(value)
    if (['http:', 'https:'].includes(origin.protocol)
        && ['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname)
        && !origin.username && !origin.password && origin.pathname === '/' && !origin.search && !origin.hash) {
      return origin.origin
    }
  } catch { /* Report only the configuration key, never an unsafe supplied value. */ }
  throw new Error('NKW_BACKEND_ORIGIN must be an HTTP(S) loopback origin without credentials, path, query or fragment')
}

export default defineConfig(({ command }) => ({
  plugins: [react(), {
    name: 'disable-development-editor-launch',
    apply: 'serve',
    configureServer(server) {
      // Keep this optional editor/OS-command surface disabled as defense in depth,
      // including after the narrowly qualified Vite security patch.
      // Register before Vite's own middleware; production output is unaffected.
      server.middlewares.use('/__open-in-editor', (_request, response) => {
        response.statusCode = 404
        response.end()
      })
    },
  }],
  // Crepe uses Vue internally for editor controls, not application state/routing.
  define: { __VUE_OPTIONS_API__: false, __VUE_PROD_DEVTOOLS__: false, __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: false },
  // Dev only. Keep browser /api calls and cookies on the frontend origin;
  // preserve Host/Origin and never turn the proxy into an external relay.
  server: command === 'serve' ? {
    proxy: { '^/api(?:[/?]|$)': { target: backendOrigin(), changeOrigin: false } },
  } : undefined,
  test: {
    environment: 'jsdom',
    exclude: ['e2e/**', 'e2e-integrated/**', 'node_modules/**', 'dist/**'],
  },
}))
