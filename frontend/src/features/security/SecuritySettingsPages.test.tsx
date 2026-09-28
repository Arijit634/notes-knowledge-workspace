import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App, PRODUCT_ROUTES } from '../../app/App'
import { AuthRuntime } from '../auth/AuthRuntime'
import type { SecuritySummary } from './SecurityApi'

const qrValues = vi.hoisted(() => [] as string[])
vi.mock('qrcode.react', () => ({
  QRCodeCanvas: ({ value }: { value: string }) => {
    qrValues.push(value)
    return <canvas role="img" aria-label="Authenticator setup QR code" />
  },
}))

const setup = { enrollmentId: 'synthetic-enrollment', manualSecret: 'SYNTHETICSETUPKEY',
  otpauthUri: 'otpauth://totp/Synthetic?secret=SYNTHETICSETUPKEY' }
const summary: SecuritySummary = { email: 'owner@example.test', passwordConfigured: true,
  mfaState: 'disabled', oidcLinks: [] }
const sessions = { sessions: [
  { sessionHandle: 'opaque-current', current: true, client: 'This browser',
    createdAt: '2026-09-01T00:00:00Z', lastSeenAt: '2026-09-02T00:00:00Z', expiresAt: '2026-09-30T00:00:00Z' },
  { sessionHandle: 'opaque-other', current: false, client: 'Other browser',
    createdAt: '2026-09-01T00:00:00Z', lastSeenAt: '2026-09-02T00:00:00Z', expiresAt: '2026-09-30T00:00:00Z' },
] }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body),
  { status, headers: { 'Content-Type': 'application/json' } })
const noContent = (status = 204) => new Response(null, { status })
const problem = (status: number, code: string) => new Response(JSON.stringify({
  type: 'about:blank', title: 'Request rejected', status, code, instance: '/api/me/security',
  traceId: `tr_${'a'.repeat(32)}`, detail: 'INTERNAL PRIVATE DETAIL',
}), { status, headers: { 'Content-Type': 'application/problem+json' } })

function mount(path: string, options: {
  state?: 'anonymous' | 'mfaRequired' | 'authenticated'
  handler?: (method: string, path: string, body: unknown) => Response
  summary?: SecuritySummary
} = {}) {
  window.history.replaceState(null, '', path)
  const calls: Array<{ method: string; path: string; body: unknown; csrf: string | null }> = []
  const state = options.state ?? 'authenticated'
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const target = String(input), method = init?.method ?? 'GET'
    const body: unknown = init?.body ? JSON.parse(String(init.body)) : null
    calls.push({ method, path: target, body,
      csrf: new Headers(init?.headers).get('X-CSRF-TOKEN') })
    if (target === '/api/auth/session') return json({ state })
    if (target === '/api/auth/csrf') return json({ csrfToken: 'synthetic-csrf' })
    if (target === '/api/me/security' && method === 'GET') return json(options.summary ?? summary)
    if (target === '/api/me/security/sessions' && method === 'GET') return json(sessions)
    return options.handler?.(method, target, body) ?? noContent()
  }))
  const auth = new AuthRuntime()
  render(<App auth={auth} />)
  return { auth, calls }
}

afterEach(() => {
  cleanup(); qrValues.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks()
  window.localStorage.clear(); window.sessionStorage.clear()
  window.history.replaceState(null, '', '/')
})

describe('security settings browser journey', () => {
  it('registers only the existing nine routes and the three protected security routes', () => {
    expect(PRODUCT_ROUTES).toEqual(['/', '/signup', '/verify-email', '/login', '/mfa',
      '/forgot-password', '/reset-password', '/auth/complete', '/reauth',
      '/settings/security', '/settings/security/mfa', '/settings/security/sessions'])
  })

  it.each(['/settings/security', '/settings/security/mfa', '/settings/security/sessions'])(
    'blocks anonymous and pre-MFA access to %s', async path => {
      mount(path, { state: 'anonymous' })
      await screen.findByRole('heading', { name: 'Welcome back' })
      expect(window.location.pathname).toBe('/login')
      cleanup()
      mount(path, { state: 'mfaRequired' })
      await screen.findByRole('heading', { name: 'Restart sign in' })
      expect(screen.queryByText('owner@example.test')).toBeNull()
    })

  it('renders only the safe security projection and navigates to MFA and sessions', async () => {
    const { calls } = mount('/settings/security')
    await screen.findByText('Current email: owner@example.test')
    expect(screen.getByText('A password is configured.')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Manage MFA' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Manage sessions' })).toBeTruthy()
    expect(calls.some(call => call.path === '/api/me/security')).toBe(true)
    expect(screen.queryByText(/issuer|subject|verifier|userId/i)).toBeNull()
  })

  it('changes password with only newPassword, then refreshes session and CSRF', async () => {
    const { calls } = mount('/settings/security')
    await screen.findByText('Current email: owner@example.test')
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'SyntheticNewPassword!' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'SyntheticNewPassword!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    await screen.findByText('Password updated.')
    expect(calls.find(call => call.path === '/api/me/security/password')).toMatchObject({
      method: 'PUT', body: { newPassword: 'SyntheticNewPassword!' }, csrf: 'synthetic-csrf',
    })
    expect((screen.getByLabelText('New password') as HTMLInputElement).value).toBe('')
    expect(calls.filter(call => call.path === '/api/auth/session').length).toBeGreaterThan(1)
    expect(calls.filter(call => call.path === '/api/auth/csrf').length).toBeGreaterThan(1)
    expect(window.localStorage.length).toBe(0)
  })

  it('requests email change with generic acceptance without changing the displayed email', async () => {
    const { calls } = mount('/settings/security', { handler: (_method, path) =>
      path.endsWith('/requests') ? noContent(202) : noContent() })
    await screen.findByText('Current email: owner@example.test')
    fireEvent.change(screen.getByLabelText('New email'), { target: { value: 'new@example.test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Request email change' }))
    await screen.findByText(/if the details can be used/i)
    expect(calls.find(call => call.path.endsWith('/email-change/requests'))?.body)
      .toEqual({ newEmail: 'new@example.test' })
    expect(screen.getByText('Current email: owner@example.test')).toBeTruthy()
  })

  it('scrubs email-change fragment before rendering and clears token after confirmation', async () => {
    const token = 'synthetic.email-change-token'
    const { auth, calls } = mount(`/settings/security#token=${token}`)
    expect(window.location.hash).toBe('')
    await screen.findByRole('button', { name: 'Confirm email change' })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm email change' }))
    await screen.findByText('Email address updated.')
    expect(calls.find(call => call.path.endsWith('/email-change/confirmations'))?.body)
      .toEqual({ token })
    expect(auth.continuation.emailChangeToken).toBeNull()
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })

  it('drops email-change token on route exit', async () => {
    const { auth } = mount('/settings/security#token=synthetic.email-change-token')
    await screen.findByRole('button', { name: 'Confirm email change' })
    fireEvent.click(screen.getByRole('link', { name: 'MFA' }))
    await waitFor(() => expect(auth.continuation.emailChangeToken).toBeNull())
  })

  it('handles Google unlink conflict safely and uses deliberate confirmation', async () => {
    const linked = { ...summary, oidcLinks: [{ linkId: '01990a55-9e12-7ac4-8f5b-31aa4a91d401',
      provider: 'google' as const, linkedAt: '2026-09-01T00:00:00Z' }] }
    const { calls } = mount('/settings/security', { summary: linked, handler: (_method, path) =>
      path.includes('/oidc-links/') ? problem(409, 'invalid_lifecycle_transition') : noContent() })
    await screen.findByRole('button', { name: 'Remove Google sign-in' })
    fireEvent.click(screen.getByRole('button', { name: 'Remove Google sign-in' }))
    expect(screen.getByRole('alertdialog')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Remove sign-in' }))
    await screen.findByText(/conflicts with the current account state/i)
    expect(calls.filter(call => call.path.includes('/oidc-links/'))).toHaveLength(1)
  })

  it('removes a Google link by its opaque locator and refetches the summary', async () => {
    const linked = { ...summary, oidcLinks: [{ linkId: '01990a55-9e12-7ac4-8f5b-31aa4a91d401',
      provider: 'google' as const, linkedAt: '2026-09-01T00:00:00Z' }] }
    const { calls } = mount('/settings/security', { summary: linked })
    await screen.findByRole('button', { name: 'Remove Google sign-in' })
    fireEvent.click(screen.getByRole('button', { name: 'Remove Google sign-in' }))
    fireEvent.click(screen.getByRole('button', { name: 'Remove sign-in' }))
    await screen.findByText('Google sign-in removed.')
    expect(calls.filter(call => call.path.endsWith(linked.oidcLinks[0]!.linkId))).toHaveLength(1)
    expect(calls.filter(call => call.path === '/api/me/security').length).toBeGreaterThan(1)
  })

  it('rejects an unsafe Google linking destination without exposing protocol material', async () => {
    const { calls } = mount('/settings/security', { handler: (_method, path) =>
      path.endsWith('/link-authorizations') ? json({ authorizationUrl: 'https://accounts.google.com.evil.test/oauth' }) : noContent() })
    await screen.findByRole('button', { name: 'Link Google sign-in' })
    fireEvent.click(screen.getByRole('button', { name: 'Link Google sign-in' }))
    await screen.findByText(/action could not be completed/i)
    expect(calls.some(call => call.path.endsWith('/link-authorizations'))).toBe(true)
    expect(window.location.pathname).toBe('/settings/security')
  })

  it('renders exact backend provisioning URI as QR, supports manual copy, and clears setup on cancel', async () => {
    const clipboard = vi.fn(async () => undefined)
    const databaseOpen = vi.fn()
    const errorLog = vi.spyOn(console, 'error')
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: clipboard } })
    vi.stubGlobal('indexedDB', { open: databaseOpen })
    const { auth, calls } = mount('/settings/security/mfa', { handler: (_method, path) =>
      path.endsWith('/enrollments') ? json(setup, 201) : noContent() })
    await screen.findByRole('button', { name: 'Set up MFA' })
    fireEvent.click(screen.getByRole('button', { name: 'Set up MFA' }))
    await screen.findByRole('img', { name: 'Authenticator setup QR code' })
    expect(qrValues).toEqual([setup.otpauthUri])
    expect(screen.getByText(setup.manualSecret)).toBeTruthy()
    expect(clipboard).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Copy setup key' }))
    await waitFor(() => expect(clipboard).toHaveBeenCalledWith(setup.manualSecret))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel setup' }))
    expect(screen.queryByText(setup.manualSecret)).toBeNull()
    expect(auth.queries.getQueryCache().getAll().every(query =>
      !JSON.stringify(query.queryKey).includes(setup.manualSecret))).toBe(true)
    expect(calls.every(call => !call.path.includes(setup.enrollmentId))).toBe(true)
    expect(window.location.href).not.toContain(setup.manualSecret)
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
    expect(databaseOpen).not.toHaveBeenCalled()
    expect(JSON.stringify(errorLog.mock.calls)).not.toContain(setup.manualSecret)
    expect(document.querySelector('a[download]')).toBeNull()
  })

  it('activates MFA only after manual TOTP verification and shows replacement codes once', async () => {
    const { auth, calls } = mount('/settings/security/mfa', { handler: (_method, path) =>
      path.endsWith('/enrollments') ? json(setup, 201)
        : path.endsWith('/confirmation') ? json({ recoveryCodes: ['SYNTHETIC-RECOVERY-1'] }) : noContent() })
    await screen.findByRole('button', { name: 'Set up MFA' })
    fireEvent.click(screen.getByRole('button', { name: 'Set up MFA' }))
    await screen.findByText(setup.manualSecret)
    fireEvent.change(screen.getByLabelText('Current authenticator code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Verify and enable MFA' }))
    await screen.findByText('SYNTHETIC-RECOVERY-1')
    expect(screen.queryByText(setup.manualSecret)).toBeNull()
    expect(calls.find(call => call.path.endsWith('/confirmation'))).toMatchObject({
      path: `/api/me/security/mfa/totp/enrollments/${setup.enrollmentId}/confirmation`,
      body: { code: '123456' },
    })
    expect(window.location.href).not.toContain(setup.enrollmentId)
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
    expect(auth.queries.getQueryCache().getAll().every(query =>
      !JSON.stringify(query.state.data).includes(setup.manualSecret))).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.queryByText('SYNTHETIC-RECOVERY-1')).toBeNull()
  })

  it('clears enrollment material on route exit and on authority loss', async () => {
    const { auth } = mount('/settings/security/mfa', { handler: (_method, path) =>
      path.endsWith('/enrollments') ? json(setup, 201) : noContent() })
    await screen.findByRole('button', { name: 'Set up MFA' })
    fireEvent.click(screen.getByRole('button', { name: 'Set up MFA' }))
    await screen.findByText(setup.manualSecret)
    fireEvent.click(screen.getByRole('link', { name: 'Security' }))
    await waitFor(() => expect(screen.queryByText(setup.manualSecret)).toBeNull())
    fireEvent.click(screen.getByRole('link', { name: 'MFA' }))
    await screen.findByRole('button', { name: 'Set up MFA' })
    fireEvent.click(screen.getByRole('button', { name: 'Set up MFA' }))
    await screen.findByText(setup.manualSecret)
    await auth.establish('anonymous')
    await waitFor(() => expect(screen.queryByText(setup.manualSecret)).toBeNull())
    expect(auth.queries.getQueryCache().getAll()).toHaveLength(0)
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })

  it('confirms MFA disable and regenerates codes into a one-time surface', async () => {
    const active: SecuritySummary = { ...summary, mfaState: 'active' }
    const { calls } = mount('/settings/security/mfa', { summary: active, handler: (_method, path) =>
      path.endsWith('/recovery-codes') ? json({ recoveryCodes: ['SYNTHETIC-NEW-CODE'] }) : noContent() })
    await screen.findByRole('button', { name: 'Regenerate recovery codes' })
    fireEvent.click(screen.getByRole('button', { name: 'Regenerate recovery codes' }))
    expect(calls.some(call => call.path.endsWith('/recovery-codes'))).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Replace codes' }))
    await screen.findByText('SYNTHETIC-NEW-CODE')
    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    await screen.findByRole('button', { name: 'Disable MFA' })
    fireEvent.click(screen.getByRole('button', { name: 'Disable MFA' }))
    fireEvent.click(screen.getByRole('alertdialog').querySelectorAll('button')[1]!)
    await screen.findByText('MFA disabled.')
    expect(calls.some(call => call.path.endsWith('/mfa/totp') && call.method === 'DELETE')).toBe(true)
  })

  it('lists safe session descriptors and revokes another without signing out', async () => {
    const { auth, calls } = mount('/settings/security/sessions')
    await screen.findByText('Other browser')
    expect(screen.getByText('Current session')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Revoke session' }))
    fireEvent.click(screen.getByRole('button', { name: 'Revoke sessions' }))
    await screen.findByText('Sessions updated.')
    expect(auth.state).toBe('authenticated')
    expect(calls.some(call => call.path.endsWith('/opaque-other') && call.method === 'DELETE')).toBe(true)
    expect(screen.queryByText('opaque-other')).toBeNull()
  })

  it('revokes other sessions without clearing the current authenticated viewer', async () => {
    const { auth, calls } = mount('/settings/security/sessions')
    await screen.findByText('Other browser')
    fireEvent.click(screen.getByRole('button', { name: 'Revoke other sessions' }))
    fireEvent.click(screen.getByRole('button', { name: 'Revoke sessions' }))
    await screen.findByText('Sessions updated.')
    expect(auth.state).toBe('authenticated')
    expect(calls.some(call => call.path.endsWith('/revoke-others'))).toBe(true)
  })

  it.each(['current', 'all'] as const)('clears local authority on %s session revocation', async kind => {
    const { auth, calls } = mount('/settings/security/sessions')
    await screen.findByText('Other browser')
    fireEvent.click(screen.getByRole('button', { name: kind === 'current' ? 'Revoke this session' : 'Revoke all sessions' }))
    fireEvent.click(screen.getByRole('alertdialog').querySelectorAll('button')[1]!)
    await waitFor(() => expect(auth.state).toBe('anonymous'))
    await waitFor(() => expect(['/','/login']).toContain(window.location.pathname))
    expect(auth.queries.getQueryCache().getAll()).toHaveLength(0)
    expect(calls.some(call => call.path === (kind === 'current'
      ? '/api/me/security/sessions/opaque-current' : '/api/me/security/sessions/revoke-all'))).toBe(true)
  })

  it('deletes account only after dialog confirmation and clears local authority', async () => {
    const { auth, calls } = mount('/settings/security')
    await screen.findByRole('button', { name: 'Delete account' })
    fireEvent.click(screen.getByRole('button', { name: 'Delete account' }))
    expect(calls.some(call => call.path === '/api/me/account')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Delete my account' }))
    await waitFor(() => expect(auth.state).toBe('anonymous'))
    expect(calls.find(call => call.path === '/api/me/account')?.body).toEqual({ confirmAccountDeletion: true })
    expect(auth.queries.getQueryCache().getAll()).toHaveLength(0)
  })

  it('uses typed recent-auth only and never renders raw problem detail', async () => {
    mount('/settings/security', { handler: (_method, path) =>
      path.endsWith('/password') ? problem(403, 'recent_authentication_required') : noContent() })
    await screen.findByRole('button', { name: 'Change password' })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'Synthetic!' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'Synthetic!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    await screen.findByRole('heading', { name: 'Confirm your identity' })
    expect(window.location.pathname).toBe('/reauth')
    expect(screen.queryByText('INTERNAL PRIVATE DETAIL')).toBeNull()
  })

  it.each([[429, 'rate_limited', /too many requests/i],
    [503, 'service_unavailable', /temporarily unavailable/i],
    [403, 'csrf_invalid', /request protection changed/i]] as const)(
    'shows only safe guidance for %s security mutation failure', async (status, code, copy) => {
      const { calls } = mount('/settings/security', { handler: (_method, path) =>
        path.endsWith('/password') ? problem(status, code) : noContent() })
      await screen.findByRole('button', { name: 'Change password' })
      fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'Synthetic!' } })
      fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'Synthetic!' } })
      fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
      await screen.findByText(copy)
      expect(screen.queryByText('INTERNAL PRIVATE DETAIL')).toBeNull()
      expect(calls.filter(call => call.path.endsWith('/password'))).toHaveLength(1)
    })
})
