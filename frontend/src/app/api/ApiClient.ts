import { CsrfManager, CsrfUnavailableError } from '../security/CsrfManager'
import { ApiProblemError, ApiProtocolError, decodeProblemDetails } from './ProblemDetailsDecoder'

export type ApiMethod = 'GET' | 'HEAD' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
export type ApiResponseType = 'json' | 'blob'

export interface ApiResponseMetadata {
  readonly status: number
  readonly etag: string | null
  readonly location: string | null
  readonly retryAfter: string | null
  readonly contentRange: string | null
}

export interface ApiResult<T> {
  readonly body: T | null
  readonly metadata: ApiResponseMetadata
}

export interface Etagged<T> {
  readonly value: T
  readonly etag: string
}

export interface CursorPage<T> {
  readonly items: readonly T[]
  readonly nextCursor: string | null
}

export interface ApiRequestOptions {
  readonly json?: unknown
  readonly signal?: AbortSignal
  readonly responseType?: ApiResponseType
  readonly ifMatch?: string
}

const successful = new Set([200, 201, 202, 204, 206])

function apiTarget(path: string): string {
  if (!path.startsWith('/api/') || path.includes('\\') || path.includes('#')
      || /[\u0000-\u001f\u007f]/.test(path)) {
    throw new ApiProtocolError()
  }
  const origin = globalThis.location?.origin ?? 'https://frontend.invalid'
  let parsed: URL
  try {
    parsed = new URL(path, origin)
  } catch {
    throw new ApiProtocolError()
  }
  if (parsed.origin !== origin || !parsed.pathname.startsWith('/api/')) {
    throw new ApiProtocolError()
  }
  return path
}

function boundedHeader(response: Response, name: string): string | null {
  const value = response.headers.get(name)
  return value !== null && value.length <= 256 && !/[\r\n\0]/.test(value) ? value : null
}

export class ApiClient {
  constructor(
    private readonly csrf: CsrfManager,
    private readonly fetcher: typeof fetch = fetch,
  ) {}

  async request<T>(method: ApiMethod, path: string, options: ApiRequestOptions = {}): Promise<ApiResult<T>> {
    const target = apiTarget(path)
    const unsafe = method === 'POST' || method === 'PUT' || method === 'PATCH' || method === 'DELETE'
    if (!unsafe && Object.hasOwn(options, 'json')) throw new ApiProtocolError()

    const send = async (csrfRetry: boolean): Promise<ApiResult<T>> => {
      const headers = new Headers({ Accept: 'application/json, application/problem+json' })
      let body: string | undefined
      if (Object.hasOwn(options, 'json')) {
        try {
          body = JSON.stringify(options.json)
        } catch {
          throw new ApiProtocolError()
        }
        if (body === undefined) throw new ApiProtocolError()
        headers.set('Content-Type', 'application/json')
      }
      if (options.ifMatch !== undefined) headers.set('If-Match', options.ifMatch)
      if (unsafe) headers.set('X-CSRF-TOKEN', await this.csrf.forUnsafeRequest())

      let response: Response
      try {
        response = await this.fetcher(target, {
          method, headers, body, signal: options.signal,
          credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store',
        })
      } catch {
        throw new ApiProtocolError()
      }

      if (!response.ok || !successful.has(response.status)) {
        if (response.status < 400) throw new ApiProtocolError()
        let failure: ApiProblemError
        try {
          failure = await decodeProblemDetails(response)
        } catch {
          throw new ApiProtocolError()
        }
        if (unsafe && !csrfRetry && response.status === 403
            && failure.problem.code === 'csrf_invalid') {
          this.csrf.clear()
          try {
            await this.csrf.refresh()
          } catch {
            throw new CsrfUnavailableError()
          }
          return send(true)
        }
        throw failure
      }

      const metadata: ApiResponseMetadata = {
        status: response.status,
        etag: boundedHeader(response, 'ETag'),
        location: boundedHeader(response, 'Location'),
        retryAfter: boundedHeader(response, 'Retry-After'),
        contentRange: boundedHeader(response, 'Content-Range'),
      }
      if (response.status === 204 || method === 'HEAD') return { body: null, metadata }
      if (response.status === 206 && options.responseType !== 'blob') throw new ApiProtocolError()
      if (options.responseType === 'blob') {
        return { body: await response.blob() as T, metadata }
      }
      let text: string
      try {
        text = await response.text()
      } catch {
        throw new ApiProtocolError()
      }
      if (text.length === 0) return { body: null, metadata }
      if (!response.headers.get('Content-Type')?.toLowerCase().startsWith('application/json')) {
        throw new ApiProtocolError()
      }
      try {
        return { body: JSON.parse(text) as T, metadata }
      } catch {
        throw new ApiProtocolError()
      }
    }
    return send(false)
  }
}
