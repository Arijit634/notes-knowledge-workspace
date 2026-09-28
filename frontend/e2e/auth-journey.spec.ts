import { expect, test, type Page } from '@playwright/test'

const challenge = 'B'.repeat(43)
async function fakeBackend(page: Page, initial: 'anonymous' | 'mfaRequired' | 'authenticated' = 'anonymous') {
  let state = initial
  await page.route('**/api/auth/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const method = request.method()
    let status = 200, body: object | null = {}
    if (path === '/api/auth/session') body = state === 'mfaRequired' ? { state, challengeId: challenge } : { state }
    else if (path === '/api/auth/csrf') body = { csrfToken: 'synthetic-csrf-proof' }
    else if (path === '/api/auth/registrations' || path === '/api/auth/password-reset/requests'
        || path === '/api/auth/email-verification/requests') { status = 202; body = {} }
    else if (path === '/api/auth/login/password') {
      const data = request.postDataJSON() as { email?: string }
      state = data.email === 'mfa@example.test' ? 'mfaRequired' : 'authenticated'
      status = state === 'mfaRequired' ? 202 : 200
      body = state === 'mfaRequired' ? { state, challengeId: challenge } : { state }
    } else if (path.includes('/mfa/challenges/')) { state = 'authenticated'; body = { state } }
    else if (path === '/api/auth/logout') { state = 'anonymous'; status = 204; body = null }
    else if (path === '/api/auth/email-verification/confirmations'
        || path === '/api/auth/password-reset/confirmations'
        || path === '/api/auth/reauth/password') { status = 204; body = null }
    else throw new Error('Unexpected authentication request: ' + method + ' ' + path)
    await route.fulfill({ status, contentType: body === null ? undefined : 'application/json', body: body === null ? '' : JSON.stringify(body) })
  })
}

test('anonymous landing to signup uses generic accepted copy', async ({ page }) => {
  await fakeBackend(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: /quieter place/i })).toBeVisible()
  await page.getByRole('link', { name: 'Create an account' }).click()
  await page.getByLabel('Email').fill('person@example.test')
  await page.getByLabel('Create password').fill('Synthetic-password-123!')
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page.getByText(/if the details can be used/i)).toBeVisible()
})

test('verification fragment is scrubbed and confirmation succeeds', async ({ page }) => {
  await fakeBackend(page)
  await page.goto('/verify-email#token=synthetic.verify-token')
  await expect(page).toHaveURL(/\/verify-email$/)
  await page.getByRole('button', { name: 'Confirm email' }).click()
  await expect(page.getByText(/Email confirmed/i)).toBeVisible()
})

test('password login without MFA reaches authenticated landing', async ({ page }) => {
  await fakeBackend(page)
  await page.goto('/login')
  await page.getByLabel('Email').fill('person@example.test')
  await page.getByLabel('Password').fill('Synthetic-password-123!')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page.getByText('You are signed in.')).toBeVisible()
  await expect(page).toHaveURL(/\/$/)
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
  await expect(page.getByText('You are signed in.')).toBeVisible()
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
  await fakeBackend(page)
  await page.goto('/reset-password#token=synthetic.reset-token')
  await expect(page).toHaveURL(/\/reset-password$/)
  await page.getByLabel('New password', { exact: true }).fill('New-synthetic-password-123!')
  await page.getByLabel('Confirm new password', { exact: true }).fill('New-synthetic-password-123!')
  await page.getByRole('button', { name: 'Set new password' }).click()
  await expect(page.getByText(/password was reset/i)).toBeVisible()
  await expect(page.getByRole('link', { name: 'Log in' })).toBeVisible()
})

test('anonymous and pre-MFA authority cannot enter recent authentication', async ({ page }) => {
  await fakeBackend(page)
  await page.goto('/reauth')
  await expect(page).toHaveURL(/\/login$/)
  await fakeBackend(page, 'mfaRequired')
  await page.goto('/reauth')
  await expect(page).toHaveURL(/\/mfa$/)
})

test('authenticated authority cannot remain on login', async ({ page }) => {
  await fakeBackend(page, 'authenticated')
  await page.goto('/login')
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText('You are signed in.')).toBeVisible()
})
