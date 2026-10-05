import { expect, test, type Page } from '@playwright/test'

const noteId = '01990a55-9e12-7ac4-8f5b-31aa4a91d401', first = '01990a55-9e12-7ac4-8f5b-31aa4a91d402', second = '01990a55-9e12-7ac4-8f5b-31aa4a91d403'
// Reserved synthetic pixel; no personal or provider media is used by the browser double.
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jR1sAAAAASUVORK5CYII=', 'base64')
async function backend(page: Page) {
  let markdown = 'Saved body', uploaded = false, removed = false
  const mutations: string[] = []
  const attachment = (id: string) => ({ id, noteId, mediaKind: 'image', mediaType: 'image/png', displayFilename: id === first ? 'first.png' : 'second.png',
    sizeBytes: png.length, width: 1, height: 1, durationSeconds: null, pageCount: null, storageState: 'stored',
    validationState: 'accepted', cleanupState: 'retained', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' })
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname, method = request.method()
    // Vite module imports may also contain /api/ in their source path; they are not API requests.
    if (!path.startsWith('/api/')) { await route.continue(); return }
    if (method !== 'GET') { mutations.push(`${method} ${path}`); expect(request.headers()['x-csrf-token']).toBe('synthetic-proof') }
    if (path.endsWith('/content')) { await route.fulfill({ contentType: 'image/png', body: png }); return }
    let body: unknown, status = 200
    const headers: Record<string, string> = { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }
    if (path === '/api/auth/session') body = { state: 'authenticated' }
    else if (path === '/api/auth/csrf') body = { csrfToken: 'synthetic-proof' }
    else if (path === `/api/notes/${noteId}`) {
      if (method === 'PUT') { expect(request.headers()['if-match']).toBe('"n1"'); markdown = request.postDataJSON().markdown }
      body = { id: noteId, title: 'Saved title', markdown, lifecycle: 'active', pinned: false, tags: [], aiEnabled: false,
        createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' }; headers.ETag = method === 'PUT' ? '"n2"' : '"n1"'
    } else if (path === `/api/notes/${noteId}/attachments`) {
      if (method === 'POST') {
        expect(request.headers()['content-type']).toContain('multipart/form-data; boundary=')
        expect(request.postData()).toContain('name="file"'); uploaded = true; status = 201; body = attachment(second)
        headers.ETag = '"a1"'; headers.Location = `${path}/${second}`
      } else body = { items: [attachment(first), ...(uploaded && !removed ? [attachment(second)] : [])], nextCursor: null }
    } else if (path === `/api/notes/${noteId}/attachments/${first}` || path === `/api/notes/${noteId}/attachments/${second}`) {
      if (method === 'DELETE') { expect(request.headers()['if-match']).toBe('"a1"'); removed = true; status = 204 }
      else { body = attachment(path.endsWith(first) ? first : second); headers.ETag = '"a1"' }
    } else if (path === `/api/notes/${noteId}/versions` || path === '/api/notes') body = { items: [], nextCursor: null }
    else throw new Error('Unexpected synthetic Attachment request')
    await route.fulfill({ status, headers, ...(status === 204 ? {} : { body: JSON.stringify(body) }) })
  })
  return mutations
}
for (const phone of [false, true]) test(`Attachment workspace preserves dirty drafts and uses private viewer (${phone ? 'phone' : 'desktop'})`, async ({ page }) => {
  if (phone) await page.setViewportSize({ width: 390, height: 844 })
  const mutations = await backend(page)
  await page.goto(`/notes/${noteId}`)
  await expect(page.getByRole('link', { name: 'Open first.png' })).toBeVisible()
  await page.getByLabel('Markdown', { exact: true }).fill(' Exact unsaved draft\n ')
  await page.getByLabel('Add file').setInputFiles({ name: 'second.png', mimeType: 'image/png', buffer: png })
  await expect(page.getByText('File added.', { exact: true })).toBeVisible()
  await expect(page.getByLabel('Markdown', { exact: true })).toHaveValue(' Exact unsaved draft\n ')
  expect(mutations).toEqual([`POST /api/notes/${noteId}/attachments`])
  await page.getByRole('link', { name: 'Open second.png' }).click()
  const leave = page.getByRole('dialog', { name: 'Leave with unsaved changes?' })
  await expect(leave).toBeVisible(); await page.getByRole('button', { name: 'Keep editing' }).click()
  await expect(leave).not.toBeVisible()
  await expect(page.getByRole('link', { name: 'Open second.png' })).toBeFocused()
  await expect(page.getByLabel('Markdown', { exact: true })).toHaveValue(' Exact unsaved draft\n ')
  // Explicit Save, never an Attachment-side effect, permits normal viewer navigation.
  await page.getByRole('button', { name: 'Save note', exact: true }).click()
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Open second.png' }).click()
  const image = page.getByRole('img', { name: 'second.png' })
  await expect(image).toHaveAttribute('src', `/api/notes/${noteId}/attachments/${second}/content`)
  await expect(image).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  await page.getByRole('link', { name: 'Back to note' }).click()
  await expect(page.getByLabel('Markdown', { exact: true })).toHaveValue(' Exact unsaved draft\n ')
  await page.getByRole('button', { name: 'Remove second.png' }).click()
  const dialog = page.getByRole('dialog', { name: 'Remove attachment?' })
  await expect(dialog).toBeVisible(); await expect(page.getByRole('button', { name: 'Keep file' })).toBeFocused()
  await page.keyboard.press('Shift+Tab'); await expect(page.getByRole('button', { name: 'Remove file', exact: true })).toBeFocused()
  await page.keyboard.press('Escape'); await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('button', { name: 'Remove second.png' })).toBeFocused()
  await page.getByRole('button', { name: 'Remove second.png' }).click()
  await page.getByRole('button', { name: 'Remove file', exact: true }).click()
  await expect(page.getByText('File removed.', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Open second.png' })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  expect(mutations).toEqual([`POST /api/notes/${noteId}/attachments`, `PUT /api/notes/${noteId}`, `DELETE /api/notes/${noteId}/attachments/${second}`])
})

test('PDF viewer offers private top-level access without framed content', async ({ page }) => {
  await backend(page)
  await page.route(`**/api/notes/${noteId}/attachments/${first}`, route => route.fulfill({
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ETag: '"a1"' },
    body: JSON.stringify({ id: first, noteId, mediaKind: 'pdf', mediaType: 'application/pdf', displayFilename: 'synthetic.pdf',
      sizeBytes: 1024, width: null, height: null, durationSeconds: null, pageCount: 3, storageState: 'stored',
      validationState: 'accepted', cleanupState: 'retained', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' }),
  }))
  const byteRequests: string[] = []
  page.on('request', request => { if (new URL(request.url()).pathname.endsWith('/content')) byteRequests.push(request.url()) })
  await page.goto(`/notes/${noteId}/attachments/${first}`)
  await expect(page.getByRole('heading', { name: 'synthetic.pdf' })).toBeVisible()
  await expect(page.getByText('PDF · 1.0 KB · 3 pages')).toBeVisible()
  await expect(page.locator('iframe,object,embed')).toHaveCount(0)
  const open = page.getByRole('link', { name: 'Open PDF' })
  await expect(open).toHaveAttribute('href', `/api/notes/${noteId}/attachments/${first}/content`)
  await expect(open).toHaveAttribute('target', '_blank')
  await expect(open).toHaveAttribute('rel', 'noopener noreferrer')
  expect(byteRequests).toEqual([])
  await expect(page.getByRole('link', { name: 'Back to note' })).toHaveAttribute('href', `/notes/${noteId}`)
})
