import { expect, type Page, type APIRequestContext, type TestInfo } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { readFile, writeFile, rename } from 'node:fs/promises'
import { resolve } from 'node:path'

export const password = 'SyntheticBrowserPassword-731!'
export async function observation(info: TestInfo, name: string, value: unknown) {
  const path = info.outputPath(name + '.json')
  await writeFile(path, JSON.stringify(value, null, 2))
  await info.attach(name, { path, contentType: 'application/json' })
}
export const runtime = () => {
  if (!process.env.NKW_INTEGRATION_RUNTIME) throw new Error('Integration runtime missing')
  return process.env.NKW_INTEGRATION_RUNTIME
}
export async function control(action: 'verify-registration' | 'grant-moderation' | 'isolate-transient-rate-state', email?: string) {
  const id = randomUUID(), directory = runtime()
  const pending = resolve(directory, 'commands', id + '.tmp'), command = resolve(directory, 'commands', id + '.json')
  await writeFile(pending, JSON.stringify({ action, ...(email ? { email } : {}) }))
  await rename(pending, command)
  await expect.poll(async () => {
    try { return JSON.parse(await readFile(resolve(directory, 'responses', id + '.json'), 'utf8')).ok }
    catch { return null }
  }, { timeout: 30_000, message: 'Synthetic out-of-band fixture operation completed' }).toBe(true)
}
export async function register(page: Page) {
  const email = 'browser_' + randomUUID().replaceAll('-', '') + '@example.test'
  await page.goto('/signup')
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Create password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible()
  // The real delivery worker consumes its encrypted capability. Its captured
  // token is returned to the real verification HTTP endpoint inside Java only.
  await control('verify-registration')
  await login(page, email)
  return email
}
export async function login(page: Page, email: string) {
  await page.goto('/login')
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page).toHaveURL('/notes')
  await expect(page.getByRole('heading', { name: 'Notes', exact: true })).toBeVisible()
}
export async function logout(page: Page) {
  await page.locator('.workspace-account > summary').click()
  await page.getByRole('button', { name: 'Log out', exact: true }).first().click()
  await page.getByRole('dialog', { name: 'Log out of this workspace?' }).getByRole('button', { name: 'Log out', exact: true }).click()
  await expect(page).toHaveURL('/login')
}
export async function csrf(request: APIRequestContext) {
  const result = await request.get('/api/auth/csrf')
  expect(result.status()).toBe(200)
  return (await result.json()).csrfToken as string
}
export async function mutate(request: APIRequestContext, method: 'POST' | 'PUT' | 'DELETE', path: string, data?: unknown, etag?: string) {
  const token = await csrf(request)
  return request.fetch(path, { method, headers: { 'X-CSRF-TOKEN': token, ...(etag ? { 'If-Match': etag } : {}) },
    ...(data === undefined ? {} : { data }) })
}
export async function noOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
}
