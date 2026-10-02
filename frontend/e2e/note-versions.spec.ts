import { expect, test, type Page } from '@playwright/test'

const id = '01990a55-9e12-7ac4-8f5b-31aa4a91d401'
const versionId = '01990a55-9e12-7ac4-8f5b-31aa4a91d402'
const checkpoint = { id: versionId, title: 'Historical title', markdown: '**Historical body**',
  sourceRevision: 1, checkpointKind: 'policy', createdAt: '2026-10-01T00:00:00Z' }

// Browser contract double only; checkpoint persistence/concurrency is proved separately on PostgreSQL 18.
async function backend(page: Page, stale: boolean) {
  let restored = false
  const mutations: string[] = []
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) { await route.continue(); return }
    const method = request.method()
    let status = 200, response: unknown = {}, headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (method !== 'GET') mutations.push(method + ' ' + path)
    if (path === '/api/auth/session') response = { state: 'authenticated' }
    else if (path === '/api/auth/csrf') response = { csrfToken: 'synthetic-csrf' }
    else if (path === `/api/notes/${id}/versions`) response = { items: [checkpoint], nextCursor: null }
    else if (path === `/api/notes/${id}/versions/${versionId}`) response = checkpoint
    else if (path === `/api/notes/${id}/versions/${versionId}/restore`) {
      expect(method).toBe('POST')
      expect(request.headers()['if-match']).toBe('"n1"')
      expect(request.headers()['x-csrf-token']).toBe('synthetic-csrf')
      expect(request.postDataJSON()).toEqual({ confirmRestore: true })
      if (stale) {
        status = 412; headers = { 'Content-Type': 'application/problem+json' }
        response = { type: 'about:blank', title: 'Changed', status, code: 'stale_write', instance: path, traceId: `tr_${'a'.repeat(32)}` }
      } else restored = true
    } else if (path !== `/api/notes/${id}` && path !== '/api/notes') throw new Error('Unexpected synthetic request')
    const core = { id, title: restored ? checkpoint.title : 'Current title', markdown: restored ? checkpoint.markdown : 'Current body',
      pinned: true, aiEnabled: false, tags: ['kept'], lifecycle: 'active', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:01Z' }
    if ((path === `/api/notes/${id}` || path.endsWith('/restore')) && status === 200) {
      response = core; headers.ETag = restored ? '"n2"' : '"n1"'
    }
    if (path === '/api/notes') response = { items: [core], nextCursor: null }
    await route.fulfill({ status, headers, body: JSON.stringify(response) })
  })
  return mutations
}

test('history inspect and accessible confirmation deliberately replace a dirty draft', async ({ page }) => {
  const mutations = await backend(page, false)
  await page.goto(`/notes/${id}`)
  await expect(page.getByLabel('Title', { exact: true })).toHaveValue('Current title')
  await page.getByLabel('Markdown', { exact: true }).fill(' Exact unsaved draft\n ')
  await page.getByRole('button', { name: 'Show version history' }).click()
  await page.getByRole('button', { name: 'Inspect Historical title' }).click()
  await expect(page.getByLabel('Markdown', { exact: true })).toHaveValue(' Exact unsaved draft\n ')
  await page.getByRole('button', { name: 'Restore this checkpoint' }).click()
  const dialog = page.getByRole('dialog', { name: 'Restore saved checkpoint?' })
  await expect(dialog).toBeVisible()
  await expect(page.getByRole('button', { name: 'Keep current note' })).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(page.getByRole('button', { name: 'Restore and discard draft' })).toBeFocused()
  await page.keyboard.press('Control+s')
  expect(mutations).toEqual([])
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: 'Restore this checkpoint' })).toBeFocused()
  await expect(page.getByLabel('Markdown', { exact: true })).toHaveValue(' Exact unsaved draft\n ')
  await page.getByRole('button', { name: 'Restore this checkpoint' }).click()
  await page.getByRole('button', { name: 'Restore and discard draft' }).click()
  await expect(page.getByLabel('Title', { exact: true })).toHaveValue('Historical title')
  await expect(page.getByLabel('Markdown', { exact: true })).toHaveValue('**Historical body**')
  await expect(page.getByRole('button', { name: 'Save note', exact: true })).toBeDisabled()
  await expect(dialog).not.toBeVisible()
  expect(mutations).toEqual([`POST /api/notes/${id}/versions/${versionId}/restore`])
})

test('stale history restore keeps the exact dirty draft and requires reconciliation', async ({ page }) => {
  const mutations = await backend(page, true)
  await page.goto(`/notes/${id}`)
  await expect(page.getByLabel('Title', { exact: true })).toHaveValue('Current title')
  await page.getByLabel('Markdown', { exact: true }).fill(' Exact dirty draft\n ')
  await page.getByRole('button', { name: 'Show version history' }).click()
  await page.getByRole('button', { name: 'Inspect Historical title' }).click()
  await page.getByRole('button', { name: 'Restore this checkpoint' }).click()
  await page.getByRole('button', { name: 'Restore and discard draft' }).click()
  await expect(page.getByRole('region', { name: 'Save conflict' })).toBeVisible()
  await expect(page.getByLabel('Markdown', { exact: true })).toHaveValue(' Exact dirty draft\n ')
  await expect(page.getByRole('button', { name: 'Restore this checkpoint' })).toBeDisabled()
  expect(mutations).toEqual([`POST /api/notes/${id}/versions/${versionId}/restore`])
})
