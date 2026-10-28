import { test, expect } from '@playwright/test'
import { flagshipBackend, answer, noteId } from './flagship-fixture'
for (const width of [1440, 390, 320]) test(`explicit date scope survives history and never silently widens at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 }); await flagshipBackend(page)
  let posts = 0, savedDuring: unknown = null
  const now = new Date().toISOString(), expiry = new Date(Date.now() + 86400000).toISOString()
  await page.route('**/api/knowledge/**', async route => {
    const p = new URL(route.request().url()).pathname
    const entry = { id: noteId, query: 'Synthetic period question', status: 'completed', submittedAt: now, expiresAt: expiry, operation: null, result: answer, savedDuring }
    if (p === '/api/knowledge/query') { posts++; savedDuring = route.request().postDataJSON().scope.savedDuring; return route.fulfill({ json: answer, headers: { 'Content-Location': `/api/knowledge/history/${noteId}` } }) }
    if (p === '/api/knowledge/history') return route.fulfill({ json: { items: posts ? [{ ...entry, result: null }] : [], nextCursor: null } })
    if (p === `/api/knowledge/history/${noteId}`) return route.fulfill({ json: entry })
    return route.fallback()
  })
  await page.goto('/ask'); await page.getByLabel('What would you like to find?').fill('Synthetic period question')
  await page.getByLabel('Saved during', { exact: true }).selectOption('custom'); await expect(page.getByRole('button', { name: 'Ask your knowledge', exact: true })).toBeDisabled()
  await page.getByLabel('From', { exact: true }).fill('2021-01-01'); await page.getByLabel('Through', { exact: true }).fill('2021-12-31')
  await page.getByRole('button', { name: 'Ask your knowledge', exact: true }).click(); await expect(page.getByRole('heading', { name: 'From your knowledge' })).toBeVisible()
  const expected = await page.evaluate(() => ({ from: new Date(2021, 0, 1).toISOString(), toExclusive: new Date(2022, 0, 1).toISOString() })); expect(savedDuring).toEqual(expected)
  const boundary = page.getByLabel('Search date boundary'), label = await boundary.innerText()
  await page.getByLabel('Saved during', { exact: true }).selectOption('any'); await expect(boundary).toHaveText(label); expect(posts).toBe(1)
  await page.reload(); await page.getByRole('button', { name: 'Synthetic period question', exact: true }).click(); await expect(boundary).toHaveText(label)
  await expect(page.getByLabel('Saved during', { exact: true })).toHaveValue('custom'); expect(posts).toBe(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
