import { expect, test, type Page } from '@playwright/test'

type Call = { method: string; path: string; body: unknown }

async function fakeSecurityBackend(page: Page) {
  let state: 'authenticated' | 'anonymous' = 'authenticated'
  let mfaState = 'disabled'
  const calls: Call[] = []
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname, method = request.method()
    if (!path.startsWith('/api/')) { await route.continue(); return }
    const body: unknown = request.postData() ? request.postDataJSON() : null
    calls.push({ method, path, body })
    let status = 200
    let response: unknown = {}
    if (path === '/api/auth/session') response = { state }
    else if (path === '/api/auth/csrf') response = { csrfToken: 'synthetic-csrf-proof' }
    else if (path === '/api/me/security' && method === 'GET') response = {
      email: 'owner@example.test', passwordConfigured: true, mfaState, oidcLinks: [],
    }
    else if (path === '/api/me/security/sessions' && method === 'GET') response = { sessions: [
      { sessionHandle: 'opaque-current', current: true, client: 'This browser',
        createdAt: '2026-09-01T00:00:00Z', lastSeenAt: '2026-09-02T00:00:00Z', expiresAt: '2026-09-30T00:00:00Z' },
      { sessionHandle: 'opaque-other', current: false, client: 'Other browser',
        createdAt: '2026-09-01T00:00:00Z', lastSeenAt: '2026-09-02T00:00:00Z', expiresAt: '2026-09-30T00:00:00Z' },
    ] }
    else if (path === '/api/me/security/oidc/google/link-authorizations' && method === 'POST') {
      response = { authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?client_id=synthetic' }
    }
    else if (path === '/api/me/security/mfa/totp/enrollments' && method === 'POST') {
      status = 201
      response = { enrollmentId: 'synthetic-enrollment', manualSecret: 'SYNTHETICSETUPKEY',
        otpauthUri: 'otpauth://totp/Synthetic?secret=SYNTHETICSETUPKEY' }
    } else if (path.endsWith('/confirmation') && path.includes('/mfa/totp/enrollments/')) {
      mfaState = 'active'; response = { recoveryCodes: ['SYNTHETIC-RECOVERY-1'] }
    } else if (path === '/api/me/security/mfa/recovery-codes') {
      response = { recoveryCodes: ['SYNTHETIC-RECOVERY-2'] }
    } else if (path === '/api/me/security/sessions/revoke-all' || path === '/api/me/account'
        || path === '/api/me/security/sessions/opaque-current') {
      state = 'anonymous'; status = 204; response = null
    } else if (method === 'PUT' || method === 'POST' || method === 'DELETE') {
      status = path.endsWith('/email-change/requests') ? 202 : 204; response = null
    } else throw new Error(`Unexpected test request: ${method} ${path}`)
    await route.fulfill({ status, contentType: response === null ? undefined : 'application/json',
      body: response === null ? '' : JSON.stringify(response) })
  })
  return calls
}

test('authenticated security summary and password change stay protected', async ({ page }) => {
  const calls = await fakeSecurityBackend(page)
  await page.goto('/settings/security')
  await expect(page.getByText('Current email: owner@example.test')).toBeVisible()
  await page.locator('summary').filter({ hasText: 'Change password' }).click()
  await page.getByLabel('New password', { exact: true }).fill('SyntheticNewPassword!')
  await page.getByLabel('Confirm new password').fill('SyntheticNewPassword!')
  await page.getByRole('button', { name: 'Change password' }).click()
  await expect(page.getByText('Password updated.')).toBeVisible()
  expect(calls.find(call => call.path === '/api/me/security/password')?.body)
    .toEqual({ newPassword: 'SyntheticNewPassword!' })
  await expect.poll(() => calls.filter(call => call.path === '/api/auth/csrf').length)
    .toBeGreaterThan(1)
})

test('email-change fragment is scrubbed before confirmation', async ({ page }) => {
  const calls = await fakeSecurityBackend(page)
  await page.goto('/settings/security#token=SYNTHETIC-EMAIL-TOKEN')
  await expect(page).toHaveURL(/\/settings\/security$/)
  await page.getByRole('button', { name: 'Confirm email change' }).click()
  await expect(page.getByText('Email address updated.')).toBeVisible()
  expect(calls.find(call => call.path.endsWith('/email-change/confirmations'))?.body)
    .toEqual({ token: 'SYNTHETIC-EMAIL-TOKEN' })
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
})

test('Google linking uses only the approved top-level provider destination', async ({ page }) => {
  const calls = await fakeSecurityBackend(page)
  await page.route('https://accounts.google.com/o/oauth2/v2/auth?*', async route => {
    await route.fulfill({ status: 200, contentType: 'text/html', body: '<h1>Provider double</h1>' })
  })
  await page.goto('/settings/security')
  await page.getByRole('button', { name: 'Link Google sign-in' }).click()
  await expect(page).toHaveURL(/^https:\/\/accounts\.google\.com\/o\/oauth2\/v2\/auth\?client_id=synthetic$/)
  expect(calls.some(call => call.path.endsWith('/link-authorizations'))).toBe(true)
})

test('MFA setup renders QR and manual key, verifies, then shows codes once', async ({ page }) => {
  const calls = await fakeSecurityBackend(page)
  await page.goto('/settings/security/mfa')
  await page.getByRole('button', { name: 'Set up MFA' }).click()
  await expect(page.getByRole('img', { name: 'Authenticator setup QR code' })).toBeVisible()
  await expect(page.getByText('SYNTHETICSETUPKEY')).toBeVisible()
  await page.getByLabel('Current authenticator code').fill('123456')
  await page.getByRole('button', { name: 'Verify and enable MFA' }).click()
  await expect(page.getByText('SYNTHETIC-RECOVERY-1')).toBeVisible()
  await expect(page.getByText('SYNTHETICSETUPKEY')).toHaveCount(0)
  expect(calls.find(call => call.path.endsWith('/confirmation'))?.body).toEqual({ code: '123456' })
  await page.getByRole('button', { name: 'Done' }).click()
  await expect(page.getByText('SYNTHETIC-RECOVERY-1')).toHaveCount(0)
})

test('session list marks current and revokes another session', async ({ page }) => {
  const calls = await fakeSecurityBackend(page)
  await page.goto('/settings/security/sessions')
  await expect(page.getByText('Current session')).toBeVisible()
  await expect(page.getByText('Other browser')).toBeVisible()
  await page.getByRole('button', { name: 'Revoke session', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Revoke sessions' }).click()
  await expect(page.getByText('Sessions updated.')).toBeVisible()
  expect(calls.some(call => call.path === '/api/me/security/sessions/opaque-other')).toBe(true)
})

test('revoke all clears authenticated browser authority', async ({ page }) => {
  const calls = await fakeSecurityBackend(page)
  await page.goto('/settings/security/sessions')
  await page.getByRole('button', { name: 'Revoke all sessions' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Revoke all sessions' }).click()
  await expect(page).toHaveURL(/\/(?:login)?$/)
  expect(calls.some(call => call.path === '/api/me/security/sessions/revoke-all')).toBe(true)
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
})

test('Account deletion clears authenticated browser authority', async ({ page }) => {
  const calls = await fakeSecurityBackend(page)
  await page.goto('/settings/security')
  await page.getByRole('button', { name: 'Delete account' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete my account' }).click()
  await expect(page).toHaveURL(/\/(?:login)?$/)
  expect(calls.find(call => call.path === '/api/me/account')?.body)
    .toEqual({ confirmAccountDeletion: true })
})
