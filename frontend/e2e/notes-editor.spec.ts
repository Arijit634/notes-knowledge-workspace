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
    else if (path === '/api/notes' && method === 'GET') response = {
      items: note && note.lifecycle === (new URL(request.url()).searchParams.get('lifecycle') ?? 'active') ? [note] : [], nextCursor: null,
    }
    else if (path === '/api/notes' && method === 'POST') {
      const input = body as { title: string; markdown: string; aiEnabled: boolean }
      note = { id, ...input, lifecycle: 'active', pinned: false, tags: [],
        createdAt: '2026-09-30T00:00:00Z', updatedAt: '2026-09-30T00:00:00Z' }
      revision = 1; response = note; status = 201
      headers = { ETag: '"n1"', Location: `/api/notes/${id}` }
    } else if (path === `/api/notes/${id}` && method === 'GET' && note) {
      response = note; headers = { ETag: `"n${revision}"` }
    } else if (path === `/api/notes/${id}` && method === 'PUT' && note) {
      expect(request.headers()['if-match']).toBe(`"n${revision}"`)
      const input = body as { title: string; markdown: string }
      note = { ...note, ...input, updatedAt: '2026-09-30T00:00:01Z' }
      revision++; response = note; headers = { ETag: `"n${revision}"` }
    } else if (path === `/api/notes/${id}/tags` && method === 'PUT' && note) {
      expect(request.headers()['if-match']).toBe(`"n${revision}"`)
      note = { ...note, tags: (body as { tags: string[] }).tags }
      revision++; response = note; headers = { ETag: `"n${revision}"` }
    } else if (note && [ `/api/notes/${id}/pin`, `/api/notes/${id}/archive`, `/api/notes/${id}/return-from-archive` ].includes(path)) {
      expect(request.headers()['if-match']).toBe(`"n${revision}"`)
      expect(body).toBeNull()
      if (path.endsWith('/pin')) {
        expect(['PUT', 'DELETE']).toContain(method)
        const pinned = method === 'PUT'
        if (note.pinned !== pinned) revision++
        note = { ...note, pinned }
      } else {
        expect(method).toBe('POST')
        expect(note.lifecycle).toBe(path.endsWith('/archive') ? 'active' : 'archived')
        note = { ...note, lifecycle: path.endsWith('/archive') ? 'archived' : 'active' }
        revision++
      }
      response = note; headers = { ETag: `"n${revision}"` }
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

test('independent pin and archive commands keep dirty drafts and update list membership', async ({ page }) => {
  const calls = await fakeNotesBackend(page)
  await page.goto('/notes/new')
  await page.getByLabel('Title').fill('Original')
  await page.getByRole('button', { name: 'Create note' }).click()
  await expect(page).toHaveURL(new RegExp(`/notes/${id}$`))
  await page.getByLabel('Title').fill(' Unsaved title ')
  await page.getByRole('textbox', { name: 'Markdown' }).fill('Unsaved\n**Markdown** ')
  await page.getByRole('button', { name: 'Pin', exact: true }).click()
  await page.getByRole('button', { name: 'Unpin', exact: true }).click()
  await page.getByRole('button', { name: 'Archive', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Archive with unsaved changes?' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Archive and keep draft' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Archive', exact: true })).toBeFocused()
  await page.getByRole('button', { name: 'Archive', exact: true }).click()
  await page.getByRole('button', { name: 'Archive and keep draft' }).click()
  await expect(page.getByRole('button', { name: 'Return from archive' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save note' })).toBeDisabled()
  await page.getByRole('textbox', { name: 'Markdown' }).press('ControlOrMeta+s')
  expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toHaveLength(0)
  await expect(page.getByLabel('Title')).toHaveValue(' Unsaved title ')
  await expect(page.getByRole('textbox', { name: 'Markdown' })).toHaveValue('Unsaved\n**Markdown** ')
  await page.getByRole('button', { name: 'Return from archive' }).click()
  await expect(page.getByLabel('Title')).toHaveValue(' Unsaved title ')
  await expect(page.getByRole('textbox', { name: 'Markdown' })).toHaveValue('Unsaved\n**Markdown** ')
  await page.getByRole('button', { name: 'Save note' }).click()
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Archive', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Return from archive' })).toBeVisible()
  await page.getByRole('link', { name: 'All notes' }).click()
  await expect(page.getByText('No notes yet', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Archived notes' }).click()
  await page.getByRole('link', { name: /Unsaved title/ }).click()
  await page.getByRole('button', { name: 'Return from archive' }).click()
  await expect(page.getByRole('button', { name: 'Archive', exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'All notes' }).click()
  await expect(page.getByRole('link', { name: /Unsaved title/ })).toBeVisible()
  await page.getByRole('button', { name: 'Archived notes' }).click()
  await expect(page.getByText('No archived notes', { exact: true })).toBeVisible()
  expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toHaveLength(1)
})

test('explicit tags preserve an unsaved draft and advance its next Save validator', async ({ page }) => {
  const calls = await fakeNotesBackend(page)
  await page.goto('/notes/new')
  await page.getByLabel('Title').fill('Original')
  await page.getByRole('button', { name: 'Create note' }).click()
  await expect(page).toHaveURL(new RegExp(`/notes/${id}$`))
  await page.getByLabel('Title').fill(' Unsaved title ')
  await page.getByRole('textbox', { name: 'Markdown' }).fill('Unsaved\n**Markdown** ')
  await page.getByRole('button', { name: 'Edit tags' }).click()
  await page.getByLabel('Tags, one per line').fill('Films\nWatch-later')
  await page.getByRole('button', { name: 'Apply tags' }).click()
  await expect(page.getByRole('listitem').filter({ hasText: 'Films' })).toBeVisible()
  await expect(page.getByLabel('Title')).toHaveValue(' Unsaved title ')
  await expect(page.getByRole('textbox', { name: 'Markdown' })).toHaveValue('Unsaved\n**Markdown** ')
  expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toHaveLength(0)
  await page.getByRole('button', { name: 'Save note' }).click()
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}/tags`)).toHaveLength(1)
})
