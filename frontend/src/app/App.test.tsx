import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App, AUTH_ROUTES } from './App'
import { AuthRuntime } from '../features/auth/AuthRuntime'
import { approvedGoogleNavigation } from '../features/auth/OidcNavigationCoordinator'

const challenge = 'A'.repeat(43)
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
})
const noContent = () => new Response(null, { status: 204 })

function mount(path: string, state: 'anonymous' | 'mfaRequired' | 'authenticated' = 'anonymous',
  handler?: (method: string, path: string, body: unknown) => Response,
  sessionBody?: object, securityBody: object = { email: 'person@example.test', passwordConfigured: true,
    mfaState: 'disabled', oidcLinks: [] }) {
  window.history.replaceState(null, '', path)
  const calls: Array<{ method: string; path: string; body: unknown }> = []
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const target = String(input), method = init?.method ?? 'GET'
    const body: unknown = init?.body ? JSON.parse(String(init.body)) : null
    calls.push({ method, path: target, body })
    if (target === '/api/auth/session') return json(sessionBody ?? (state === 'mfaRequired' ? { state, challengeId: challenge } : { state }))
    if (target === '/api/auth/csrf') return json({ csrfToken: 'synthetic-proof' })
    if (target === '/api/notes') return json({ items: [], nextCursor: null })
    if (target === '/api/me/security') return json(securityBody)
    return handler?.(method, target, body) ?? noContent()
  })
  vi.stubGlobal('fetch', fetcher)
  const auth = new AuthRuntime()
  render(<App auth={auth} />)
  return { auth, calls, fetcher }
}

afterEach(() => {
  cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('authentication browser journey', () => {
  it('registers exactly the nine authorized routes', () => {
    expect(AUTH_ROUTES).toEqual(['/', '/signup', '/verify-email', '/login', '/mfa',
      '/forgot-password', '/reset-password', '/auth/complete', '/reauth'])
  })

  it('bootstraps anonymous session and CSRF, then shows landing', async () => {
    const { calls } = mount('/')
    await screen.findByRole('heading', { name: /Your thoughts/i })
    expect(calls.map(call => call.path)).toContain('/api/auth/session')
    expect(calls.map(call => call.path)).toContain('/api/auth/csrf')
    expect(screen.getByRole('link', { name: 'Create an account' })).toBeTruthy()
  })

  it('gates anonymous, full-session, and pre-MFA routes', async () => {
    const first = mount('/reauth')
    await screen.findByRole('heading', { name: 'Welcome back' })
    expect(window.location.pathname).toBe('/login')
    cleanup()
    mount('/login', 'authenticated')
    await screen.findByRole('heading', { name: 'Notes' })
    expect(window.location.pathname).toBe('/notes')
    cleanup()
    mount('/reauth', 'mfaRequired')
    await screen.findByRole('heading', { name: 'Verify it’s you' })
    expect(window.location.pathname).toBe('/mfa')
    expect(first.auth.state).toBe('anonymous')
  })

  it('uses enumeration-safe signup copy', async () => {
    const { calls } = mount('/signup', 'anonymous', () => json(null, 202))
    await screen.findByRole('heading', { name: 'Create your account' })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'person@example.test' } })
    fireEvent.change(screen.getByLabelText('Create password'), { target: { value: 'Synthetic-password-123!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
    await screen.findByText(/if the details can be used/i)
    expect(calls.some(call => call.path === '/api/auth/registrations' && call.method === 'POST')).toBe(true)
  })

  it('captures and immediately scrubs verification fragment before confirmation', async () => {
    const token = 'synthetic.verify-token'
    const { calls } = mount('/verify-email?source=synthetic#token=' + token)
    expect(window.location.hash).toBe('')
    expect(window.location.search).toBe('')
    await screen.findByRole('button', { name: 'Confirm email' })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm email' }))
    await screen.findByText(/Email confirmed/i)
    expect(calls.find(call => call.path === '/api/auth/email-verification/confirmations')?.body).toEqual({ token })
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })

  it('drops a one-time link token when its flow is cancelled', async () => {
    const { auth } = mount('/verify-email#token=synthetic.verify-token')
    await screen.findByRole('button', { name: 'Confirm email' })
    fireEvent.click(screen.getByRole('link', { name: 'Notes & Knowledge' }))
    await waitFor(() => expect(auth.continuation.verificationToken).toBeNull())
  })

  it('logs in without MFA and refreshes CSRF', async () => {
    const { auth, calls } = mount('/login', 'anonymous', (_method, path) =>
      path === '/api/auth/login/password' ? json({ state: 'authenticated' }) : noContent())
    await screen.findByRole('heading', { name: 'Welcome back' })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'person@example.test' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'Synthetic-password-123!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
    await screen.findByRole('heading', { name: 'Notes' })
    expect(auth.state).toBe('authenticated')
    expect(calls.filter(call => call.path === '/api/auth/csrf').length).toBeGreaterThanOrEqual(2)
  })

  it('continues an MFA password login in memory and elevates with TOTP', async () => {
    const { auth, calls } = mount('/login', 'anonymous', (_method, path) =>
      path === '/api/auth/login/password' ? json({ state: 'mfaRequired', challengeId: challenge }, 202)
        : json({ state: 'authenticated' }))
    await screen.findByRole('heading', { name: 'Welcome back' })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'person@example.test' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'Synthetic-password-123!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
    await screen.findByRole('heading', { name: 'Verify it’s you' })
    expect(window.location.href).not.toContain(challenge)
    fireEvent.change(screen.getByLabelText('Authenticator code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Verify and continue' }))
    await screen.findByRole('heading', { name: 'Notes' })
    expect(calls.some(call => call.path === `/api/auth/mfa/challenges/${challenge}/totp`)).toBe(true)
    expect(auth.continuation.challengeId).toBeNull()
  })

  it('rejects unsafe Google navigation targets', () => {
    expect(approvedGoogleNavigation('https://accounts.google.com/o/oauth2/v2/auth?client_id=fake')).toContain('accounts.google.com')
    for (const target of ['http://accounts.google.com/o/oauth2/v2/auth',
      'https://accounts.google.com.evil.test/o/oauth2/v2/auth', 'javascript:alert(1)',
      'https://accounts.google.com@evil.test/o/oauth2/v2/auth', '/api/auth/session']) {
      expect(approvedGoogleNavigation(target)).toBeNull()
    }
  })

  it('does not navigate to an unapproved Google start destination', async () => {
    mount('/login', 'anonymous', (_method, path) => path === '/api/auth/oidc/google/authorizations'
      ? json({ authorizationUrl: 'https://accounts.google.com.evil.test/o/oauth2/v2/auth' }) : noContent())
    await screen.findByRole('heading', { name: 'Welcome back' })
    fireEvent.click(screen.getByRole('button', { name: 'Continue with Google' }))
    await screen.findByText(/Something went wrong/i)
    expect(window.location.pathname).toBe('/login')
  })

  it('keeps a completed reset anonymous and scrubs its fragment', async () => {
    const token = 'synthetic.reset-token'
    const { auth, calls } = mount('/reset-password#token=' + token)
    expect(window.location.hash).toBe('')
    await screen.findByRole('button', { name: 'Set new password' })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'New-synthetic-password-123' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'New-synthetic-password-123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Set new password' }))
    await screen.findByText(/password was reset/i)
    expect(auth.state).toBe('anonymous')
    expect(calls.find(call => call.path === '/api/auth/password-reset/confirmations')?.body).toEqual({ token, newPassword: 'New-synthetic-password-123' })
  })

  it('sends no email in password recent authentication', async () => {
    const { calls } = mount('/reauth', 'authenticated')
    await screen.findByRole('heading', { name: 'Confirm your identity' })
    fireEvent.change(await screen.findByLabelText('Password'), { target: { value: 'Synthetic-password-123!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm with password' }))
    await screen.findByText(/Identity confirmed/i)
    expect(calls.find(call => call.path === '/api/auth/reauth/password')?.body).toEqual({ email: null, password: 'Synthetic-password-123!' })
  })

  it('guides a passwordless account to the existing reset flow without Google reauth', async () => {
    const { calls } = mount('/reauth', 'authenticated', undefined, undefined,
      { email: 'person@example.test', passwordConfigured: false, mfaState: 'disabled', oidcLinks: [] })
    await screen.findByRole('heading', { name: 'Confirm your identity' })
    await screen.findByText(/set an application password first/i)
    expect(screen.getByRole('link', { name: 'Set an application password' }).getAttribute('href'))
      .toBe('/forgot-password')
    expect(screen.queryByRole('button', { name: 'Confirm with Google' })).toBeNull()
    expect(screen.queryByLabelText('Password')).toBeNull()
    expect(calls.some(call => call.path.includes('/reauth/oidc/'))).toBe(false)
  })

  it('clears the authenticated viewer on logout', async () => {
    const { auth } = mount('/', 'authenticated')
    await screen.findByRole('heading', { name: 'Notes' })
    const oldScope = auth.session.viewerScope
    fireEvent.click(screen.getByRole('button', { name: 'Log out' }))
    await waitFor(() => expect(auth.state).toBe('anonymous'))
    expect(auth.session.viewerScope.kind).toBe('anonymous')
    expect(oldScope.kind).toBe('authenticated')
  })

  it('completes an authenticated callback through session and CSRF rebootstrap', async () => {
    const { calls } = mount('/auth/complete', 'authenticated')
    await screen.findByRole('heading', { name: 'Notes' })
    expect(calls.filter(call => call.path === '/api/auth/session').length).toBeGreaterThanOrEqual(2)
    expect(calls.filter(call => call.path === '/api/auth/csrf').length).toBeGreaterThanOrEqual(2)
  })

  it('resumes an OIDC pre-MFA callback without exposing the challenge in a URL', async () => {
    const { auth } = mount('/auth/complete', 'mfaRequired')
    await screen.findByRole('heading', { name: 'Verify it’s you' })
    expect(auth.continuation.challengeId).toBe(challenge)
    expect(window.location.pathname).toBe('/mfa')
    expect(window.location.href).not.toContain(challenge)
  })

  it('requires a primary-login restart when pre-MFA projection lacks a current challenge', async () => {
    mount('/mfa', 'mfaRequired', undefined, { state: 'mfaRequired' })
    await screen.findByRole('heading', { name: 'Restart sign in' })
    expect(screen.queryByLabelText('Authenticator code')).toBeNull()
  })

  it('does not pretend an anonymous OIDC return succeeded', async () => {
    mount('/auth/complete')
    await screen.findByText(/sign-in could not be completed/i)
    expect(screen.getByRole('link', { name: 'Log in' })).toBeTruthy()
  })

  it('uses blind acceptance for password reset requests', async () => {
    mount('/forgot-password', 'anonymous', () => json(null, 202))
    await screen.findByRole('heading', { name: 'Reset your password' })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'unknown@example.test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Request reset link' }))
    await screen.findByText(/if the details can be used/i)
  })

  it('presents a safe service-unavailable state without raw response details', async () => {
    mount('/login', 'anonymous', () => new Response(JSON.stringify({
      type: 'about:blank', title: 'Unavailable', status: 503, instance: '/api/auth/login/password',
      code: 'dependency_unavailable', traceId: 'tr_' + 'a'.repeat(32), detail: 'PRIVATE INTERNAL DETAIL',
    }), { status: 503, headers: { 'Content-Type': 'application/problem+json' } }))
    await screen.findByRole('heading', { name: 'Welcome back' })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'person@example.test' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'Synthetic-password-123!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
    await screen.findByText(/temporarily unavailable/i)
    expect(screen.queryByText(/PRIVATE INTERNAL DETAIL/i)).toBeNull()
  })

  it('shows only bounded retry guidance for a rate-limited blind request', async () => {
    mount('/forgot-password', 'anonymous', () => new Response(JSON.stringify({
      type: 'about:blank', title: 'Rate limited', status: 429, instance: '/api/auth/password-reset/requests',
      code: 'rate_limited', traceId: 'tr_' + 'b'.repeat(32), detail: 'PRIVATE INTERNAL DETAIL',
    }), { status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '60' } }))
    await screen.findByRole('heading', { name: 'Reset your password' })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'unknown@example.test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Request reset link' }))
    await screen.findByText(/try again in 60 seconds/i)
    expect(screen.queryByText(/PRIVATE INTERNAL DETAIL/i)).toBeNull()
  })
})
