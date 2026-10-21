import { describe, expect, it } from 'vitest'
import { ApiProblemError } from '../api/ProblemDetailsDecoder'
import { readResponseMetadata } from '../api/ApiResponseMetadata'
import { retryAfterSeconds } from './useRetryAfter'

function failure(raw: string, status = 429) { return new ApiProblemError({ type: 'about:blank', title: 'Request rejected', status, code: 'rate_limited', traceId: `tr_${'a'.repeat(32)}`, instance: '/api/public/search' }, readResponseMetadata(new Response(null, { status, headers: { 'Retry-After': raw } }))) }
describe('bounded Retry-After', () => {
  it('honors seconds and HTTP date without exposing response detail', () => { expect(retryAfterSeconds(failure('42'))).toBe(42); expect(retryAfterSeconds(failure('Wed, 21 Oct 2026 12:00:42 GMT'), Date.parse('2026-10-21T12:00:00Z'))).toBe(42) })
  it('caps delays and uses a safe default for malformed input', () => { expect(retryAfterSeconds(failure('99999'))).toBe(86400); expect(retryAfterSeconds(failure('not-a-delay'))).toBe(30); expect(retryAfterSeconds(failure('30', 503))).toBe(0); expect(retryAfterSeconds(new Error('synthetic'))).toBe(0) })
})
