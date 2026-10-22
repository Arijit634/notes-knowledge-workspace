import { expect, test } from '@playwright/test'
import { createHmac } from 'node:crypto'
import { control, csrf, logout, password, register } from './stack-support'

test.beforeEach(async () => { await control('isolate-transient-rate-state') })

function totp(secret: string) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567', bytes: number[] = []
  let bits = 0, count = 0
  for (const character of secret.replaceAll(' ', '').toUpperCase()) {
    const value = alphabet.indexOf(character)
    if (value < 0) throw new Error('Unexpected synthetic enrollment encoding')
    bits = (bits << 5) | value; count += 5
    if (count >= 8) { count -= 8; bytes.push((bits >>> count) & 255) }
  }
  const key = Buffer.from(bytes), counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000)))
  const digest = createHmac('sha1', key).update(counter).digest(), offset = digest.at(-1)! & 15
  const code = ((digest.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0')
  key.fill(0); digest.fill(0); bytes.fill(0)
  return code
}

test('real MFA enrollment, recovery login and session revocation preserve restricted authority', async ({ page, browser }, info) => {
  const login = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/login/password'
    && response.request().method() === 'POST' && response.status() === 200)
  const email = await register(page)
  const sessionHeader = (await (await login).headersArray()).find(header =>
    header.name.toLowerCase() === 'set-cookie' && /^SESSION=/.test(header.value))?.value ?? ''
  // Compare only derived flags: an assertion must never print cookie material.
  expect({ present: !!sessionHeader, httpOnly: /;\s*HttpOnly(?:;|$)/i.test(sessionHeader),
    secure: /;\s*Secure(?:;|$)/i.test(sessionHeader), lax: /;\s*SameSite=Lax(?:;|$)/i.test(sessionHeader) })
    .toEqual({ present: true, httpOnly: true, secure: true, lax: true })
  // Assertions only expose booleans, never the actual cookie or factor material.
  const descriptor = (await page.context().cookies()).find(cookie => cookie.name === 'SESSION')
  expect(!!descriptor?.httpOnly).toBe(true)
  expect(!!descriptor?.secure).toBe(true)
  if (info.project.name === 'webkit' && process.platform === 'win32') {
    // Pinned upstream's own reporting test expects failure on Windows WebKit:
    // https://github.com/microsoft/playwright/blob/v1.63.0/tests/library/browsercontext-cookies.spec.ts#L122
    // Wire Lax is mandatory above; this does NOT qualify native Safari enforcement.
    expect(descriptor?.sameSite === 'None').toBe(true)
    info.annotations.push({ type: 'qualification-limit', description: 'Windows WebKit SameSite reporting limitation; actual server Lax/HttpOnly/Secure attributes verified; Safari enforcement not qualified.' })
  } else expect(descriptor?.sameSite === 'Lax').toBe(true)
  await page.goto('/settings/security/mfa')
  await page.getByRole('button', { name: 'Set up MFA', exact: true }).click()
  const setup = page.getByRole('region', { name: 'Set up an authenticator', exact: true })
  await expect(setup).toBeVisible()
  const secret = (await setup.locator('code').textContent())?.trim()
  if (!secret) throw new Error('Synthetic enrollment not supplied')
  await page.getByLabel('Current authenticator code', { exact: true }).fill(totp(secret))
    .catch(() => { throw new Error('Synthetic factor entry unavailable') })
  await page.getByRole('button', { name: 'Verify and enable MFA', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Save your recovery codes now' })).toBeVisible()
  const recovery = await page.locator('section[aria-labelledby="codes-heading"] li code').first().textContent()
  if (!recovery) throw new Error('Synthetic recovery proof not supplied')
  await page.getByRole('button', { name: 'Done', exact: true }).click()
  await expect(page.locator('section[aria-labelledby="codes-heading"]')).toHaveCount(0)
  await page.goto('/notes'); await logout(page)
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page).toHaveURL('/mfa')
  expect([401, 403]).toContain((await page.request.get('/api/notes')).status())
  await page.getByRole('button', { name: 'Recovery code', exact: true }).click()
  await page.getByLabel('Recovery code', { exact: true }).fill(recovery)
    .catch(() => { throw new Error('Synthetic recovery entry unavailable') })
  await page.getByRole('button', { name: 'Verify and continue', exact: true }).click()
  await expect(page).toHaveURL('/notes')
  await page.goto('/settings/security/sessions')
  await expect(page.getByText('Current session', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Revoke all sessions', exact: true }).click()
  await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === '/api/me/security/sessions/revoke-all'
      && response.request().method() === 'POST' && response.status() === 204),
    page.getByRole('alertdialog', { name: 'Revoke all sessions?' }).getByRole('button', { name: 'Revoke all sessions', exact: true }).click(),
  ])
  expect((await page.request.get('/api/notes')).status()).toBe(401)
  const anonymous = await browser.newContext({ baseURL: 'https://localhost:4175' })
  try {
    expect((await anonymous.request.get('/api/me/security')).status()).toBe(401)
    expect((await anonymous.request.post('/api/me/security/sessions/revoke-all', { headers: { 'X-CSRF-TOKEN': await csrf(anonymous.request) } })).status()).toBe(401)
  } finally { await anonymous.close() }
})

test('real Redis enforces the default password-login budget', async ({ page }) => {
  const email = await register(page)
  await logout(page)
  // Account setup is separate from the measured rejection sequence. No clearing,
  // sleeping, retrying or policy overrides occur after the first probe request.
  await control('isolate-transient-rate-state')
  const proof = await csrf(page.request)
  const request = () => page.request.post('/api/auth/login/password', {
    headers: { 'X-CSRF-TOKEN': proof }, data: { email, password: password + '-wrong' },
  })
  for (let index = 0; index < 10; index++) expect((await request()).status()).toBe(401)
  const rejected = await request()
  expect(rejected.status()).toBe(429)
  expect(Number(rejected.headers()['retry-after'])).toBeGreaterThan(0)
})
