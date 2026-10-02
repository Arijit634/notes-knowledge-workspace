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
  plugins: [react()],
  // Dev only. Keep browser /api calls and cookies on the frontend origin;
  // preserve Host/Origin and never turn the proxy into an external relay.
  server: command === 'serve' ? {
    proxy: { '^/api(?:[/?]|$)': { target: backendOrigin(), changeOrigin: false } },
  } : undefined,
  test: {
    environment: 'jsdom',
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
  },
}))
