import { test, expect } from '@playwright/test'
import { flagshipBackend, answer, noteId, operationId } from './flagship-fixture'

for (const width of [1440, 834, 390, 320]) test(`search history and long request progress at ${width}px`, async ({ page }, info) => {
  await page.setViewportSize({ width, height: 900 })
  await flagshipBackend(page)
  const now = Date.now(), submittedAt = new Date(now - 1800000).toISOString(), expiresAt = new Date(now + 3600000).toISOString()
  let entries = [{ id: noteId, query: 'Which fictional footballers did I save?', status: 'running', submittedAt, expiresAt, operation: { operationId, status: 'running', submittedAt, expiresAt, historyId: noteId, totalSources: 1194, inspectedSources: 600, result: null as unknown }, result: null as unknown }]
  let polls = 0, posts = 0
  await page.route('**/api/knowledge/**', async route => {
    const request = route.request(), url = new URL(request.url()), method = request.method()
    const reply = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body), headers: { 'Cache-Control': 'no-store' } })
    if (url.pathname === '/api/knowledge/history' && method === 'GET') return reply({ items: entries.map(e => ({ ...e, result: null, operation: null })), nextCursor: null })
    if (url.pathname === `/api/knowledge/history/${noteId}` && method === 'GET') return entries.length ? reply(entries[0]) : reply({}, 404)
    if (url.pathname.startsWith('/api/knowledge/history') && method === 'DELETE') { expect(request.headers()['x-csrf-token']).toBe('synthetic-proof'); entries = []; return route.fulfill({ status: 204 }) }
    if (url.pathname.startsWith('/api/knowledge/operations/') && method === 'GET') { polls++; return reply(entries[0].operation) }
    if (url.pathname === '/api/knowledge/query') { posts++; return reply(answer) }
    return route.fallback()
  })
  await page.goto('/ask'); await page.getByRole('button', { name: entries[0].query, exact: true }).click()
  await expect(page.getByText('600 of 1194 sources checked', { exact: true })).toBeVisible()
  await expect(page.getByRole('progressbar', { name: 'Sources checked' })).toHaveAttribute('value', '600')
  await expect(page.getByText(/Roughly .*m remaining/)).toBeVisible()
  const before = polls; await page.getByRole('button', { name: 'Check status', exact: true }).click(); await expect.poll(() => polls).toBeGreaterThan(before)
  entries[0].operation.inspectedSources = 1194
  await page.getByRole('button', { name: 'Check status', exact: true }).click()
  await expect(page.getByText('Sources checked. Finishing results…', { exact: true })).toBeVisible()
  await expect(page.getByText(/Roughly .*m remaining/)).not.toBeVisible()
  expect(posts).toBe(0)
  await page.getByRole('link', { name: 'All notes', exact: true }).click(); await page.getByRole('link', { name: 'AI Search', exact: true }).click()
  await expect(page.getByText('1194 of 1194 sources checked', { exact: true })).toBeVisible()
  await page.screenshot({ path: info.outputPath(`history-progress-${width}.png`), fullPage: true })
  await page.getByRole('button', { name: 'Remove search: Which fictional footballers did I save?', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Remove this search?' }); await expect(dialog).toContainText('running search will be cancelled')
  await dialog.getByRole('button', { name: 'Remove', exact: true }).click(); await expect(dialog).not.toBeVisible()
  await expect(page.getByText('No saved searches yet.', { exact: true })).toBeVisible(); expect(posts).toBe(0)
  await page.reload(); await expect(page.getByText('No saved searches yet.', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

for (const width of [1440, 834, 390, 320]) test(`pending search stays visible across navigation at ${width}px`, async ({ page }, info) => {
  await page.setViewportSize({ width, height: 900 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await flagshipBackend(page)
  let posts = 0, release!: () => void
  const responseReady = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/knowledge/query', async route => {
    posts++; await responseReady
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(answer) })
  })
  await page.goto('/ask')
  await page.getByLabel('What would you like to find?').fill('Find the fictional blue square')
  await page.getByRole('button', { name: 'Ask your knowledge', exact: true }).click()
  const activity = page.getByRole('region', { name: 'AI search activity' })
  await expect(activity).toBeVisible()
  await expect(page.getByRole('button', { name: 'Searching…', exact: true })).toBeDisabled()
  await expect(activity.getByRole('timer')).toHaveText(/\d+:\d{2}/)
  await expect(activity.locator('.search-spinner')).toHaveCSS('animation-name', 'none')
  await expect(activity.getByRole('progressbar')).toHaveCount(0)
  await page.getByRole('link', { name: 'All notes', exact: true }).click()
  await page.getByRole('link', { name: 'AI Search', exact: true }).click()
  await expect(activity).toBeVisible(); expect(posts).toBe(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: info.outputPath(`pending-search-${width}.png`), fullPage: true })
  release()
  await expect(activity).not.toBeVisible()
  await expect(page.getByRole('button', { name: 'Ask your knowledge', exact: true })).toBeEnabled()
  await expect(page.getByText(answer.aiAnswer.claims[0], { exact: true })).toBeVisible()
  expect(posts).toBe(1)
})
