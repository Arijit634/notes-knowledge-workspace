import { createServer } from 'vite'
import { createServer as createHttpServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { afterEach, describe, expect, it, vi } from 'vitest'
import configuration from './vite.config'

async function config(command: 'serve' | 'build') {
  if (typeof configuration !== 'function') throw new Error('Expected Vite configuration factory')
  return configuration({ command, mode: command === 'serve' ? 'development' : 'production' })
}

afterEach(() => { vi.unstubAllEnvs() })

describe('development-only backend proxy', () => {
  it('defaults to local Spring Boot and matches only the /api namespace', async () => {
    vi.stubEnv('NKW_BACKEND_ORIGIN', undefined)
    const proxy = (await config('serve')).server!.proxy!
    const [pattern] = Object.keys(proxy)
    expect(proxy[pattern]).toEqual({ target: 'http://127.0.0.1:8080', changeOrigin: false })
    expect(['/api', '/api?synthetic=true', '/api/notes', '/api/auth/session'].every(path => new RegExp(pattern).test(path))).toBe(true)
    expect(['/', '/notes', '/apiary', '/other/api', '/https://external.invalid'].some(path => new RegExp(pattern).test(path))).toBe(false)
  })

  it.each(['http://localhost:8081', 'https://127.0.0.1:8443', 'http://[::1]:8080'])('allows explicit loopback override %s', async origin => {
    vi.stubEnv('NKW_BACKEND_ORIGIN', origin)
    expect((await config('serve')).server!.proxy!['^/api(?:[/?]|$)']).toEqual({ target: origin, changeOrigin: false })
  })

  it.each(['https://external.invalid', 'http://127.0.0.1.external.invalid', 'http://localhost:8080/api',
    'http://localhost:8080?key=synthetic', 'http://localhost:8080#fragment', 'http://synthetic:synthetic@localhost:8080',
    'file:///synthetic', 'not-an-origin'])('rejects non-origin or non-loopback configuration without echoing it', async value => {
    vi.stubEnv('NKW_BACKEND_ORIGIN', value)
    await expect(config('serve')).rejects.toThrow('NKW_BACKEND_ORIGIN must be an HTTP(S) loopback origin')
  })

  it('does not configure or evaluate a backend target for production builds', async () => {
    vi.stubEnv('NKW_BACKEND_ORIGIN', 'https://external.invalid')
    expect((await config('build')).server).toBeUndefined()
  })

  it('forwards local API paths, cookies and CSRF without changing the browser origin', async () => {
    const received: Array<{ path: string | undefined; cookie: string | undefined; csrf: string | string[] | undefined;
      host: string | undefined; origin: string | undefined }> = []
    const backend = createHttpServer((request, response) => {
      received.push({ path: request.url, cookie: request.headers.cookie, csrf: request.headers['x-csrf-token'],
        host: request.headers.host, origin: request.headers.origin })
      response.setHeader('Content-Type', 'application/json')
      response.setHeader('Set-Cookie', 'NKW_SESSION=synthetic-session; Path=/; HttpOnly; SameSite=Lax')
      response.end(JSON.stringify({ synthetic: true }))
    })
    await new Promise<void>(resolve => backend.listen(0, '127.0.0.1', resolve))
    vi.stubEnv('NKW_BACKEND_ORIGIN', `http://127.0.0.1:${(backend.address() as AddressInfo).port}`)
    const server = await createServer({ configFile: false, server: { ...(await config('serve')).server, host: '127.0.0.1', port: 0 } })
    try {
      await server.listen()
      const origin = `http://127.0.0.1:${(server.httpServer!.address() as AddressInfo).port}`
      const response = await fetch(`${origin}/api/proxy-fixture?cursor=synthetic`, { method: 'POST', headers: {
        Cookie: 'NKW_SESSION=synthetic-session', 'X-CSRF-TOKEN': 'synthetic-proof', Origin: origin,
      } })
      expect(await response.json()).toEqual({ synthetic: true })
      expect(response.headers.get('set-cookie')).toBe('NKW_SESSION=synthetic-session; Path=/; HttpOnly; SameSite=Lax')
      expect(received).toEqual([{ path: '/api/proxy-fixture?cursor=synthetic', cookie: 'NKW_SESSION=synthetic-session',
        csrf: 'synthetic-proof', host: new URL(origin).host, origin }])
      await fetch(`${origin}/apiary`)
      expect(received).toHaveLength(1)
    } finally {
      await server.close()
      await new Promise<void>((resolve, reject) => backend.close(error => error ? reject(error) : resolve()))
    }
  })
})
