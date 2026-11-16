import { expect, test, type Page } from '@playwright/test'

const challenge = 'B'.repeat(43)
function observeFragmentAtAuthRequests(page: Page) {
  const scrubbed: boolean[] = []
  page.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/auth/')) {
      // Keep evidence boolean-only; never capture the security-link fragment.
      scrubbed.push(new URL(page.url()).hash === '')
    }
  })
  return scrubbed
}
async function fakeBackend(page: Page, initial: 'anonymous' | 'mfaRequired' | 'authenticated' = 'anonymous',
  passwordConfigured = true) {
  let state = initial
  let passwordAvailable = passwordConfigured
  await page.route('**/api/notes', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], nextCursor: null }) }))
  await page.route('**/api/me/security', route => route.fulfill({ status: 200,
    contentType: 'application/json', body: JSON.stringify({ email: 'person@example.test',
      passwordConfigured: passwordAvailable, mfaState: 'disabled', oidcLinks: [] }) }))
  await page.route('**/api/me/profile', route => route.fulfill({ json: { displayName: 'Synthetic writer', biography: '', handle: null, avatar: null, updatedAt: null } }))
  await page.route('**/api/auth/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const method = request.method()
    if (method === 'POST' && state !== 'anonymous' && [
      '/api/auth/registrations', '/api/auth/login/password',
      '/api/auth/password-reset/requests', '/api/auth/password-reset/confirmations',
      '/api/auth/email-verification/requests', '/api/auth/email-verification/confirmations',
    ].includes(path)) return route.fulfill({ status: 403, contentType: 'application/problem+json',
      body: JSON.stringify({ type: 'about:blank', title: 'Request rejected', status: 403,
        code: 'access_denied', instance: path, traceId: `tr_${'a'.repeat(32)}` }) })
    let status = 200, body: object | null = {}
    if (path === '/api/auth/session') body = state === 'mfaRequired' ? { state, challengeId: challenge } : { state }
    else if (path === '/api/auth/csrf') body = { csrfToken: 'synthetic-csrf-proof' }
    else if (path === '/api/auth/registrations' || path === '/api/auth/password-reset/requests'
        || path === '/api/auth/email-verification/requests') { status = 202; body = {} }
    else if (path === '/api/auth/login/password') {
      if (!passwordAvailable) return route.fulfill({ status: 401,
        contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank',
          title: 'Request rejected', status: 401, code: 'invalid_credentials', instance: path,
          traceId: `tr_${'a'.repeat(32)}` }) })
      const data = request.postDataJSON() as { email?: string }
      state = data.email === 'mfa@example.test' ? 'mfaRequired' : 'authenticated'
      status = state === 'mfaRequired' ? 202 : 200
      body = state === 'mfaRequired' ? { state, challengeId: challenge } : { state }
    } else if (path.includes('/mfa/challenges/')) { state = 'authenticated'; body = { state } }
    else if (path === '/api/auth/logout') { state = 'anonymous'; status = 204; body = null }
    else if (path === '/api/auth/password-reset/confirmations') { passwordAvailable = true; status = 204; body = null }
    else if (path === '/api/auth/email-verification/confirmations'
        || path === '/api/auth/reauth/password') { status = 204; body = null }
    else throw new Error('Unexpected authentication request: ' + method + ' ' + path)
    await route.fulfill({ status, contentType: body === null ? undefined : 'application/json', body: body === null ? '' : JSON.stringify(body) })
  })
}

test('anonymous root redirects to login and signup uses generic accepted copy', async ({ page }) => {
  await fakeBackend(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
  await expect(page).toHaveURL('/login')
  await page.getByRole('link', { name: 'Create an account' }).click()
  // Login also has Email. Wait for the destination form before filling it.
  await expect(page.getByRole('heading', { name: 'Create your account', exact: true })).toBeVisible()
  await page.getByLabel('Email').fill('person@example.test')
  await page.getByLabel('Create password').fill('Synthetic-password-123!')
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page.getByText(/if the details can be used/i)).toBeVisible()
})

test('verification fragment is scrubbed and confirmation succeeds', async ({ page }) => {
  const scrubbed = observeFragmentAtAuthRequests(page)
  await fakeBackend(page)
  await page.goto('/verify-email#token=synthetic.verify-token')
  const confirm = page.getByRole('button', { name: 'Confirm email' })
  // Cold dev-module loading precedes synchronous ingress; wait for app readiness,
  // not an arbitrary five-second bundle-load deadline. No auth request may race ingress.
  await expect(confirm).toBeVisible({ timeout: 20_000 })
  await expect(page).toHaveURL(/\/verify-email$/)
  await confirm.click()
  await expect(page.getByText(/Email confirmed/i)).toBeVisible()
  expect(scrubbed.length).toBeGreaterThan(0)
  expect(scrubbed.every(Boolean)).toBe(true)
})

test('password login without MFA reaches the Notes library', async ({ page }) => {
  await fakeBackend(page)
  await page.goto('/login')
  await page.getByLabel('Email').fill('person@example.test')
  await page.getByLabel('Password').fill('Synthetic-password-123!')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Notes', exact: true })).toBeVisible()
  await expect(page).toHaveURL(/\/notes$/)
})

test('password login with MFA and recovery-code continuation reaches full authority', async ({ page }) => {
  await fakeBackend(page)
  await page.goto('/login')
  await page.getByLabel('Email').fill('mfa@example.test')
  await page.getByLabel('Password').fill('Synthetic-password-123!')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page).toHaveURL(/\/mfa$/)
  await page.getByRole('button', { name: 'Recovery code' }).click()
  await page.getByLabel('Recovery code').fill('synthetic-recovery-code')
  await page.getByRole('button', { name: 'Verify and continue' }).click()
  await expect(page.getByRole('heading', { name: 'Notes', exact: true })).toBeVisible()
  expect(page.url()).not.toContain(challenge)
})

test('forgotten password request is enumeration-safe', async ({ page }) => {
  await fakeBackend(page)
  await page.goto('/forgot-password')
  await page.getByLabel('Email').fill('unknown@example.test')
  await page.getByRole('button', { name: 'Request reset link' }).click()
  await expect(page.getByText(/if the details can be used/i)).toBeVisible()
})

test('reset fragment is scrubbed and successful reset leads to login CTA', async ({ page }) => {
  const scrubbed = observeFragmentAtAuthRequests(page)
  await fakeBackend(page)
  await page.goto('/reset-password#token=synthetic.reset-token')
  await expect(page.getByLabel('New password', { exact: true })).toBeVisible({ timeout: 20_000 })
  await expect(page).toHaveURL(/\/reset-password$/)
  await page.getByLabel('New password', { exact: true }).fill('New-synthetic-password-123!')
  await page.getByLabel('Confirm new password', { exact: true }).fill('New-synthetic-password-123!')
  await page.getByRole('button', { name: 'Set new password' }).click()
  await expect(page.getByText(/password was reset/i)).toBeVisible()
  await expect(page.getByRole('link', { name: 'Log in' })).toBeVisible()
  expect(scrubbed.length).toBeGreaterThan(0)
  expect(scrubbed.every(Boolean)).toBe(true)
})

test('anonymous and pre-MFA authority cannot enter recent authentication', async ({ page }) => {
  await fakeBackend(page)
  await page.goto('/reauth')
  await expect(page).toHaveURL(/\/login$/)
  await fakeBackend(page, 'mfaRequired')
  await page.goto('/reauth')
  await expect(page).toHaveURL(/\/mfa$/)
})

test('recent authentication offers password proof only when configured', async ({ page }) => {
  await fakeBackend(page, 'authenticated')
  await page.goto('/reauth')
  await expect(page.getByRole('button', { name: 'Confirm with password' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Confirm with Google' })).toHaveCount(0)
})

test('passwordless recent authentication guides to existing recovery', async ({ page }) => {
  await fakeBackend(page, 'authenticated', false)
  await page.goto('/reauth')
  await expect(page.getByRole('button', { name: 'Sign out and set password', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Confirm with Google' })).toHaveCount(0)
})

test('passwordless MFA prerequisite signs out before recovery and permits first-password login', async ({ page }) => {
  await fakeBackend(page, 'authenticated', false)
  await page.route('**/api/me/security/mfa/totp/enrollments', route => route.fulfill({
    status: 403, contentType: 'application/problem+json', body: JSON.stringify({
      type: 'about:blank', title: 'Confirm your identity', status: 403,
      code: 'recent_authentication_required', instance: '/api/me/security/mfa/totp/enrollments',
      traceId: `tr_${'a'.repeat(32)}`,
    }),
  }))
  const recoveryStatuses: number[] = []
  page.on('response', response => {
    if (new URL(response.url()).pathname === '/api/auth/password-reset/requests') recoveryStatuses.push(response.status())
  })
  await page.goto('/settings/security/mfa')
  await page.getByRole('button', { name: 'Set up MFA', exact: true }).click()
  await expect(page).toHaveURL('/reauth')
  await page.getByRole('button', { name: 'Sign out and set password', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Set an app password', exact: true })).toBeVisible()
  await page.getByLabel('Email', { exact: true }).fill('person@example.test')
  await page.getByRole('button', { name: 'Send password link', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Check your email', exact: true })).toBeVisible()
  expect(recoveryStatuses).toEqual([202])
  await page.goto('/reset-password#token=synthetic.reset-token')
  await page.getByLabel('New password', { exact: true }).fill('New-synthetic-password-123!')
  await page.getByLabel('Confirm new password', { exact: true }).fill('New-synthetic-password-123!')
  await page.getByRole('button', { name: 'Set new password', exact: true }).click()
  await expect(page.getByText(/Your password was reset/)).toBeVisible()
  await page.getByRole('link', { name: 'Log in', exact: true }).click()
  await page.getByLabel('Email', { exact: true }).fill('person@example.test')
  await page.getByLabel('Password', { exact: true }).fill('New-synthetic-password-123!')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page).toHaveURL('/notes')
})

test('authenticated authority cannot remain on login', async ({ page }) => {
  await fakeBackend(page, 'authenticated')
  await page.goto('/login')
  await expect(page).toHaveURL(/\/notes$/)
  await expect(page.getByRole('heading', { name: 'Notes', exact: true })).toBeVisible()
})
