import { readResponseMetadata, type ApiResponseMetadata } from './ApiResponseMetadata'

export interface ValidationProblemField {
  readonly field: string
  readonly code: string
  readonly message: string
}

export interface SafeProblemDetails {
  readonly type: 'about:blank'
  readonly title: string
  readonly status: number
  readonly instance: string
  readonly code: string
  readonly traceId: string
  readonly errors?: readonly ValidationProblemField[]
}

export class ApiProtocolError extends Error {
  constructor() {
    super('The server response could not be processed safely.')
    this.name = 'ApiProtocolError'
  }
}

export class ApiProblemError extends Error {
  constructor(readonly problem: SafeProblemDetails, readonly metadata: ApiResponseMetadata) {
    super('The request could not be completed.')
    this.name = 'ApiProblemError'
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function matches(value: unknown, pattern: RegExp, maximum: number): value is string {
  return typeof value === 'string' && value.length <= maximum && pattern.test(value)
}

const titlePattern = /^[A-Za-z0-9][A-Za-z0-9 .,:'()/-]*$/
const codePattern = /^[a-z][a-z0-9_]*$/
const instancePattern = /^\/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*$/
const tracePattern = /^tr_[a-f0-9]{32}$/
const fieldPattern = /^[A-Za-z][A-Za-z0-9_.\[\]-]*$/
const messagePattern = /^[A-Za-z0-9][A-Za-z0-9 .,:'()/_-]*$/

/** Reads only the accepted backend fields; detail and unknown keys never survive decoding. */
export async function decodeProblemDetails(response: Response): Promise<ApiProblemError> {
  if (response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase()
      !== 'application/problem+json') {
    throw new ApiProtocolError()
  }

  let raw: unknown
  try {
    const text = await response.text()
    if (text.length > 16_384) throw new ApiProtocolError()
    raw = JSON.parse(text) as unknown
  } catch {
    throw new ApiProtocolError()
  }

  if (!isRecord(raw)
      || raw.type !== 'about:blank'
      || !matches(raw.title, titlePattern, 120)
      || raw.status !== response.status
      || !matches(raw.instance, instancePattern, 512)
      || !matches(raw.code, codePattern, 64)
      || !matches(raw.traceId, tracePattern, 35)) {
    throw new ApiProtocolError()
  }

  let errors: ValidationProblemField[] | undefined
  if (raw.errors !== undefined) {
    if (!Array.isArray(raw.errors) || raw.errors.length > 32) {
      throw new ApiProtocolError()
    }
    errors = raw.errors.map((entry: unknown) => {
      if (!isRecord(entry)
          || !matches(entry.field, fieldPattern, 80)
          || !matches(entry.code, codePattern, 64)
          || !matches(entry.message, messagePattern, 160)) {
        throw new ApiProtocolError()
      }
      return { field: entry.field, code: entry.code, message: entry.message }
    })
  }

  const problem: SafeProblemDetails = {
    type: 'about:blank',
    title: raw.title,
    status: raw.status,
    instance: raw.instance,
    code: raw.code,
    traceId: raw.traceId,
    ...(errors === undefined ? {} : { errors }),
  }
  return new ApiProblemError(problem, readResponseMetadata(response))
}
