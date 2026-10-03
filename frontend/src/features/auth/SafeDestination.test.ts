import { describe, expect, it } from 'vitest'
import { safeDestination } from './SafeDestination'

describe('memory-only login destination allowlist', () => {
  it.each(['/notes', '/notes/new', '/notes/01990a55-9e12-7ac4-8f5b-31aa4a91d401', '/settings/security', '/settings/security/mfa', '/settings/security/sessions'])('accepts implemented route %s', value => {
    expect(safeDestination(value)).toBe(value)
  })
  it.each([null, undefined, {}, 'https://example.test', '//example.test', '\\example.test', '/notes\\evil', '/notes\n', '/api/notes', '/login', '/auth/complete', '/reauth', '/notes?next=https://example.test', '/notes#private', '/notes/../login', '/notes/%2e%2e/login', '/notes/not-an-id', '/settings', 'javascript:alert(1)'])('rejects unapproved destination %j', value => {
    expect(safeDestination(value)).toBeNull()
  })
})
