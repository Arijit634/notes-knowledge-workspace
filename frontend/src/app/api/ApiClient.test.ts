import { describe, expect, it, vi } from 'vitest'
import { CsrfManager, CsrfUnavailableError } from '../security/CsrfManager'
import { ApiClient } from './ApiClient'
import { ApiProblemError, ApiProtocolError } from './ProblemDetailsDecoder'

const traceId = `tr_${'a'.repeat(32)}`

function problem(status: number, code: string, extra: Record<string, unknown> = {}): Response {
  return new Response(JSON.stringify({ type: 'about:blank', title: 'Request rejected', status,
    instance: '/api/notes', code, traceId, ...extra }), {
    status, headers: { 'Content-Type': 'application/problem+json' },
  })
}

describe('ApiClient transport', () => {
  it('uses same-origin credentials and captures protocol metadata without a success wrapper', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({ id: 'synthetic' }), {
      status: 200, headers: { 'Content-Type': 'application/json', ETag: '"revision-1"',
        Location: '/api/notes/synthetic', 'Retry-After': '4' },
    }))
    const result = await new ApiClient(new CsrfManager(), fetcher as typeof fetch)
      .request<{ id: string }>('GET', '/api/notes')
    expect(result.body).toEqual({ id: 'synthetic' })
    expect(result.metadata).toMatchObject({ status: 200, etag: '"revision-1"',
      location: '/api/notes/synthetic', retryAfter: '4' })
    expect(fetcher).toHaveBeenCalledWith('/api/notes', expect.objectContaining({
      credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store',
    }))
    const init = (fetcher.mock.calls as unknown as Array<[string, RequestInit]>)[0][1]
    expect(new Headers(init.headers).has('X-CSRF-TOKEN')).toBe(false)
  })

  it('sends If-Match and CSRF only on the unsafe request and does not replay ordinary conflicts', async () => {
    const csrf = new CsrfManager()
    csrf.set('synthetic-proof')
    const fetcher = vi.fn(async () => problem(412, 'precondition_failed'))
    const client = new ApiClient(csrf, fetcher as typeof fetch)
    await expect(client.request('PATCH', '/api/notes/synthetic', {
      json: { title: 'Synthetic' }, ifMatch: '"revision-1"',
    })).rejects.toBeInstanceOf(ApiProblemError)
    expect(fetcher).toHaveBeenCalledTimes(1)
    const init = (fetcher.mock.calls as unknown as Array<[string, RequestInit]>)[0][1]
    expect(new Headers(init.headers).get('X-CSRF-TOKEN')).toBe('synthetic-proof')
    expect(new Headers(init.headers).get('If-Match')).toBe('"revision-1"')
  })

  it('refreshes and replays exactly once for a valid csrf_invalid 403', async () => {
    const loader = vi.fn(async () => 'renewed-proof')
    const csrf = new CsrfManager(loader)
    csrf.set('old-proof')
    const fetcher = vi.fn()
      .mockResolvedValueOnce(problem(403, 'csrf_invalid'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    const result = await new ApiClient(csrf, fetcher as typeof fetch)
      .request('DELETE', '/api/notes/synthetic')
    expect(result.metadata.status).toBe(204)
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(loader).toHaveBeenCalledTimes(1)
    expect(new Headers((fetcher.mock.calls[0][1] as RequestInit).headers).get('X-CSRF-TOKEN'))
      .toBe('old-proof')
    expect(new Headers((fetcher.mock.calls[1][1] as RequestInit).headers).get('X-CSRF-TOKEN'))
      .toBe('renewed-proof')
  })

  it('never replays a second csrf_invalid 403 or another 403 code', async () => {
    const csrf = new CsrfManager(async () => 'renewed-proof')
    csrf.set('old-proof')
    const fetcher = vi.fn(async () => problem(403, 'csrf_invalid'))
    await expect(new ApiClient(csrf, fetcher as typeof fetch)
      .request('POST', '/api/notes', { json: {} })).rejects.toBeInstanceOf(ApiProblemError)
    expect(fetcher).toHaveBeenCalledTimes(2)
    const otherFetcher = vi.fn(async () => problem(403, 'forbidden'))
    await expect(new ApiClient(csrf, otherFetcher as typeof fetch)
      .request('POST', '/api/notes', { json: {} })).rejects.toBeInstanceOf(ApiProblemError)
    expect(otherFetcher).toHaveBeenCalledTimes(1)
  })

  it('never automatically retries a failed mutation for 429, 503 or network failure', async () => {
    const csrf = new CsrfManager()
    csrf.set('synthetic-proof')
    for (const status of [429, 503]) {
      const fetcher = vi.fn(async () => problem(status,
        status === 429 ? 'rate_limited' : 'service_unavailable'))
      await expect(new ApiClient(csrf, fetcher as typeof fetch)
        .request('POST', '/api/notes', { json: {} })).rejects.toBeInstanceOf(ApiProblemError)
      expect(fetcher).toHaveBeenCalledTimes(1)
    }
    const networkFailure = vi.fn(async () => { throw new Error('private network detail') })
    await expect(new ApiClient(csrf, networkFailure as typeof fetch)
      .request('POST', '/api/notes', { json: {} })).rejects.toBeInstanceOf(ApiProtocolError)
    expect(networkFailure).toHaveBeenCalledTimes(1)
  })

  it('fails closed when CSRF proof is unavailable before sending', async () => {
    const fetcher = vi.fn()
    await expect(new ApiClient(new CsrfManager(), fetcher as typeof fetch)
      .request('POST', '/api/notes', { json: {} })).rejects.toBeInstanceOf(CsrfUnavailableError)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('exposes only safe Problem Details fields, never detail or unknown data', async () => {
    const fetcher = vi.fn(async () => problem(400, 'validation_failed', {
      detail: 'private note content', unexpected: 'private token',
      errors: [{ field: 'title', code: 'required', message: 'Title is required' }],
    }))
    try {
      await new ApiClient(new CsrfManager(), fetcher as typeof fetch).request('GET', '/api/notes')
      throw new Error('Expected an API problem')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiProblemError)
      const caught = error as ApiProblemError
      expect(caught.message).not.toContain('private')
      expect(caught.problem).toEqual({ type: 'about:blank', title: 'Request rejected', status: 400,
        instance: '/api/notes', code: 'validation_failed', traceId,
        errors: [{ field: 'title', code: 'required', message: 'Title is required' }] })
    }
  })

  it('rejects external targets and malformed errors without leaking response text', async () => {
    const fetcher = vi.fn(async () => new Response('secret', { status: 500 }))
    const client = new ApiClient(new CsrfManager(), fetcher as typeof fetch)
    await expect(client.request('GET', '//outside.invalid/api/notes')).rejects.toBeInstanceOf(ApiProtocolError)
    await expect(client.request('GET', 'https://outside.invalid/api/notes')).rejects.toBeInstanceOf(ApiProtocolError)
    await expect(client.request('GET', 'javascript:alert(1)')).rejects.toBeInstanceOf(ApiProtocolError)
    await expect(client.request('GET', '/api/notes')).rejects.toBeInstanceOf(ApiProtocolError)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('handles 202 and 206 as distinct transport states', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 202, headers: { Location: '/api/jobs/synthetic' } }))
      .mockResolvedValueOnce(new Response('bytes', { status: 206, headers: { 'Content-Range': 'bytes 0-4/5' } }))
    const client = new ApiClient(new CsrfManager(), fetcher as typeof fetch)
    const accepted = await client.request('GET', '/api/jobs/synthetic')
    expect(accepted).toMatchObject({ body: null, metadata: { status: 202,
      location: '/api/jobs/synthetic' } })
    const partial = await client.request<Blob>('GET', '/api/media/synthetic', { responseType: 'blob' })
    expect(partial.body).toBeInstanceOf(Blob)
    expect(partial.metadata.contentRange).toBe('bytes 0-4/5')
  })

  it('does not add CSRF to HEAD or read a 204 body', async () => {
    const csrf = new CsrfManager()
    csrf.set('synthetic-proof')
    const fetcher = vi.fn(async () => new Response(null, { status: 204 }))
    const result = await new ApiClient(csrf, fetcher as typeof fetch).request('HEAD', '/api/notes')
    expect(result.body).toBeNull()
    const init = (fetcher.mock.calls as unknown as Array<[string, RequestInit]>)[0][1]
    expect(new Headers(init.headers).has('X-CSRF-TOKEN')).toBe(false)
  })

  it('never logs response bodies or persists auth-related state', async () => {
    const logged = vi.spyOn(console, 'log').mockImplementation(() => {})
    const errored = vi.spyOn(console, 'error').mockImplementation(() => {})
    const debugged = vi.spyOn(console, 'debug').mockImplementation(() => {})
    const localWrite = vi.spyOn(Storage.prototype, 'setItem')
    const csrf = new CsrfManager()
    csrf.set('synthetic-proof')
    const fetcher = vi.fn(async () => problem(400, 'malformed_request', {
      detail: 'PRIVATE_MARKER', arbitrary: 'PRIVATE_MARKER',
    }))
    try {
      await expect(new ApiClient(csrf, fetcher as typeof fetch).request('GET', '/api/notes'))
        .rejects.toBeInstanceOf(ApiProblemError)
      expect(logged).not.toHaveBeenCalled()
      expect(errored).not.toHaveBeenCalled()
      expect(debugged).not.toHaveBeenCalled()
      expect(localWrite).not.toHaveBeenCalled()
    } finally {
      logged.mockRestore()
      errored.mockRestore()
      debugged.mockRestore()
      localWrite.mockRestore()
    }
  })
})
