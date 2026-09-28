import type { AuthRuntime } from '../auth/AuthRuntime'
import type { ApiMethod } from '../../app/api/ApiClient'
import { ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'

export const securityKeys = {
  summary: (viewer: string) => ['security', viewer, 'summary'] as const,
  sessions: (viewer: string) => ['security', viewer, 'sessions'] as const,
}

export type SecuritySummary = {
  email: string
  passwordConfigured: boolean
  mfaState: 'disabled' | 'enrollmentPending' | 'active'
  oidcLinks: Array<{ linkId: string; provider: 'google'; linkedAt: string }>
}

export type SessionView = {
  sessionHandle: string
  current: boolean
  client: string
  createdAt: string
  lastSeenAt: string
  expiresAt: string
}

export type Enrollment = { enrollmentId: string; manualSecret: string; otpauthUri: string }

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function required<T>(value: T | null): T {
  if (value === null) throw new ApiProtocolError()
  return value
}

export async function securitySummary(auth: AuthRuntime): Promise<SecuritySummary> {
  const raw: unknown = required((await auth.api.request<unknown>('GET', '/api/me/security')).body)
  if (!record(raw) || typeof raw.email !== 'string' || typeof raw.passwordConfigured !== 'boolean'
      || !['disabled', 'enrollmentPending', 'active'].includes(String(raw.mfaState))
      || !Array.isArray(raw.oidcLinks) || !raw.oidcLinks.every(link => record(link)
        && typeof link.linkId === 'string' && link.provider === 'google'
        && typeof link.linkedAt === 'string')) throw new ApiProtocolError()
  return {
    email: raw.email as string,
    passwordConfigured: raw.passwordConfigured as boolean,
    mfaState: raw.mfaState as SecuritySummary['mfaState'],
    oidcLinks: raw.oidcLinks.map(link => ({
      linkId: link.linkId as string, provider: 'google' as const, linkedAt: link.linkedAt as string,
    })),
  }
}

export async function listSessions(auth: AuthRuntime): Promise<SessionView[]> {
  const raw: unknown = required((await auth.api.request<unknown>('GET', '/api/me/security/sessions')).body)
  if (!record(raw) || !Array.isArray(raw.sessions) || !raw.sessions.every(item => record(item)
      && typeof item.sessionHandle === 'string' && typeof item.current === 'boolean'
      && typeof item.client === 'string' && typeof item.createdAt === 'string'
      && typeof item.lastSeenAt === 'string' && typeof item.expiresAt === 'string')) {
    throw new ApiProtocolError()
  }
  return raw.sessions.map(item => ({
    sessionHandle: item.sessionHandle as string, current: item.current as boolean,
    client: item.client as string, createdAt: item.createdAt as string,
    lastSeenAt: item.lastSeenAt as string, expiresAt: item.expiresAt as string,
  }))
}

async function mutate<T>(auth: AuthRuntime, method: ApiMethod, path: string, json?: unknown): Promise<T | null> {
  const options = json === undefined ? { retryOnCsrfInvalid: false }
    : { json, retryOnCsrfInvalid: false }
  return (await auth.api.request<T>(method, path, options)).body
}

export const securityApi = {
  changePassword: (auth: AuthRuntime, newPassword: string) =>
    mutate(auth, 'PUT', '/api/me/security/password', { newPassword }),
  requestEmailChange: (auth: AuthRuntime, newEmail: string) =>
    mutate(auth, 'POST', '/api/me/security/email-change/requests', { newEmail }),
  confirmEmailChange: (auth: AuthRuntime, token: string) =>
    mutate(auth, 'POST', '/api/me/security/email-change/confirmations', { token }),
  beginGoogleLink: async (auth: AuthRuntime) => {
    const body = required(await mutate<{ authorizationUrl?: unknown }>(auth, 'POST',
      '/api/me/security/oidc/google/link-authorizations'))
    return body.authorizationUrl
  },
  unlinkGoogle: (auth: AuthRuntime, linkId: string) =>
    mutate(auth, 'DELETE', `/api/me/security/oidc-links/${encodeURIComponent(linkId)}`),
  beginEnrollment: async (auth: AuthRuntime): Promise<Enrollment> => {
    const body: unknown = required(await mutate<unknown>(auth, 'POST',
      '/api/me/security/mfa/totp/enrollments'))
    if (!record(body) || typeof body.enrollmentId !== 'string'
        || typeof body.manualSecret !== 'string' || typeof body.otpauthUri !== 'string'
        || !body.otpauthUri.startsWith('otpauth://totp/')) throw new ApiProtocolError()
    return body as Enrollment
  },
  confirmEnrollment: async (auth: AuthRuntime, enrollmentId: string, code: string) => {
    const body: unknown = required(await mutate<unknown>(auth, 'POST',
      `/api/me/security/mfa/totp/enrollments/${encodeURIComponent(enrollmentId)}/confirmation`, { code }))
    return recoveryCodes(body)
  },
  disableMfa: (auth: AuthRuntime) => mutate(auth, 'DELETE', '/api/me/security/mfa/totp'),
  regenerateCodes: async (auth: AuthRuntime) => recoveryCodes(required(await mutate<unknown>(
    auth, 'POST', '/api/me/security/mfa/recovery-codes'))),
  revokeOne: (auth: AuthRuntime, handle: string) =>
    mutate(auth, 'DELETE', `/api/me/security/sessions/${encodeURIComponent(handle)}`),
  revokeOthers: (auth: AuthRuntime) =>
    mutate(auth, 'POST', '/api/me/security/sessions/revoke-others'),
  revokeAll: (auth: AuthRuntime) =>
    mutate(auth, 'POST', '/api/me/security/sessions/revoke-all'),
  deleteAccount: (auth: AuthRuntime) =>
    mutate(auth, 'DELETE', '/api/me/account', { confirmAccountDeletion: true }),
}

function recoveryCodes(raw: unknown): string[] {
  if (!record(raw) || !Array.isArray(raw.recoveryCodes)
      || !raw.recoveryCodes.every(code => typeof code === 'string')) throw new ApiProtocolError()
  return raw.recoveryCodes
}
