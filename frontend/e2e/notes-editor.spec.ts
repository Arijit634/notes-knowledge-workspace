import { expect, test, type Page } from '@playwright/test'

const id = '01990a55-9e12-7ac4-8f5b-31aa4a91d401'

async function fakeNotesBackend(page: Page) {
  let note: { id: string; title: string; markdown: string; lifecycle: string; pinned: boolean;
    tags: string[]; aiEnabled: boolean; createdAt: string; updatedAt: string } | null = null
  let revision = 0
  const calls: Array<{ method: string; path: string; body: unknown }> = []
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname, method = request.method()
    if (!path.startsWith('/api/')) { await route.continue(); return }
    const body: unknown = request.postData() ? request.postDataJSON() : null
    calls.push({ method, path, body })
    let status = 200, response: unknown = {}, headers: Record<string, string> = {}
    if (path === '/api/auth/session') response = { state: 'authenticated' }
    else if (path === '/api/auth/csrf') response = { csrfToken: 'synthetic-csrf' }
    else if (path === '/api/me/note-preferences') response = { defaultAiEnabledForNewNotes: false }
    else if (path === '/api/notes' && method === 'GET') response = { items: note ? [note] : [], nextCursor: null }
    else if (path === '/api/notes' && method === 'POST') {
      const input = body as { title: string; markdown: string; aiEnabled: boolean }
      note = { id, ...input, lifecycle: 'active', pinned: false, tags: [],
        createdAt: '2026-09-30T00:00:00Z', updatedAt: '2026-09-30T00:00:00Z' }
      revision = 1; response = note; status = 201
      headers = { ETag: '"n1"', Location: `/api/notes/${id}` }
    } else if (path === `/api/notes/${id}` && method === 'GET' && note) {
      response = note; headers = { ETag: `"n${revision}"` }
    } else if (path === `/api/notes/${id}` && method === 'PUT' && note) {
      const input = body as { title: string; markdown: string }
      note = { ...note, ...input, updatedAt: '2026-09-30T00:00:01Z' }
      revision++; response = note; headers = { ETag: `"n${revision}"` }
    } else throw new Error(`Unexpected test request: ${method} ${path}`)
    await route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(response) })
  })
  return calls
}

test('new Note stays local until Create; explicit Save and dirty navigation work', async ({ page }) => {
  const calls = await fakeNotesBackend(page)
  let externalImageRequests = 0
  await page.route('https://untrusted.invalid/**', async route => {
    externalImageRequests++; await route.abort()
  })
  await page.goto('/notes/new')
  await expect(page.getByRole('heading', { name: 'Create a note' })).toBeVisible()
  await page.getByLabel('Title').fill('Watch later')
  await page.getByRole('textbox', { name: 'Markdown' }).fill('**Movie**\n\n![remote](https://untrusted.invalid/remote-image)')
  await expect(page.getByText('Movie', { exact: true })).toBeVisible()
  expect(await page.locator('.notes-preview img').count()).toBe(0)
  expect(externalImageRequests).toBe(0)
  expect(calls.filter(call => call.method === 'POST' && call.path === '/api/notes')).toHaveLength(0)
  await page.getByRole('button', { name: 'Create note' }).click()
  await expect(page).toHaveURL(new RegExp(`/notes/${id}$`))
  await page.getByLabel('Title').fill('Updated watch list')
  await page.getByRole('link', { name: 'All notes' }).click()
  await expect(page.getByRole('dialog', { name: 'Leave with unsaved changes?' })).toBeVisible()
  await page.getByRole('button', { name: 'Keep editing' }).click()
  await expect(page.getByLabel('Title')).toHaveValue('Updated watch list')
  await page.getByLabel('Title').press('ControlOrMeta+s')
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toHaveLength(1)
  expect(externalImageRequests).toBe(0)
})
