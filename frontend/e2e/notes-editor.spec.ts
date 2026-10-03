import { expect, test, type Page } from '@playwright/test'

const id = '01990a55-9e12-7ac4-8f5b-31aa4a91d401'

async function sourceField(page: Page) {
  const toggle = page.getByRole('button', { name: 'Markdown source', exact: true })
  if (!(await page.getByLabel('Markdown', { exact: true }).count())) await toggle.click()
  return page.getByLabel('Markdown', { exact: true })
}
async function detailAction(page: Page, name: string) {
  const action = page.getByRole('button', { name, exact: true })
  if (await action.isVisible()) return action
  const trigger = page.getByRole('button', { name: 'Details', exact: true })
  if (await trigger.getAttribute('aria-expanded') !== 'true') await trigger.click()
  return page.getByRole('button', { name, exact: true })
}

async function noPageOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
}

async function fakeNotesBackend(page: Page, requireRecent = false) {
  let note: { id: string; title: string; markdown: string; lifecycle: string; pinned: boolean;
    tags: string[]; aiEnabled: boolean; createdAt: string; updatedAt: string } | null = null
  let revision = 0
  let preTrashState: string | null = null
  let recent = !requireRecent
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
    else if (path === '/api/me/security') response = { email: 'synthetic@example.test', passwordConfigured: true, mfaState: 'disabled', oidcLinks: [] }
    else if (path === '/api/auth/reauth/password') { recent = true; status = 204 }
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
    } else if (path === `/api/notes/${id}` && method === 'DELETE' && note) {
      expect(note.lifecycle).toBe('trashed')
      expect(request.headers()['if-match']).toBe(`"n${revision}"`)
      expect(body).toEqual({ confirmPermanentDelete: true })
      if (!recent) {
        status = 403; headers['Content-Type'] = 'application/problem+json'
        response = { type: 'about:blank', title: 'Recent authentication required', status,
          code: 'recent_authentication_required', instance: path, traceId: `tr_${'a'.repeat(32)}` }
      } else { note = null; status = 204 }
    } else if (path === `/api/notes/${id}` && method === 'PUT' && note) {
      expect(request.headers()['if-match']).toBe(`"n${revision}"`)
      const input = body as { title: string; markdown: string }
      note = { ...note, ...input, updatedAt: '2026-09-30T00:00:01Z' }
      revision++; response = note; headers = { ETag: `"n${revision}"` }
    } else if (path === `/api/notes/${id}/ai-access` && method === 'PUT' && note) {
      expect(request.headers()['if-match']).toBe(`"n${revision}"`)
      const enabled = (body as { aiEnabled: boolean }).aiEnabled
      expect(body).toEqual({ aiEnabled: enabled })
      if (note.aiEnabled !== enabled) revision++
      note = { ...note, aiEnabled: enabled }
      response = note; headers = { ETag: `"n${revision}"` }
    } else if (path === `/api/notes/${id}/tags` && method === 'PUT' && note) {
      expect(request.headers()['if-match']).toBe(`"n${revision}"`)
      note = { ...note, tags: (body as { tags: string[] }).tags }
      revision++; response = note; headers = { ETag: `"n${revision}"` }
    } else if (note && [ `/api/notes/${id}/trash`, `/api/notes/${id}/restore` ].includes(path)) {
      expect(method).toBe('POST')
      expect(request.headers()['if-match']).toBe(`"n${revision}"`)
      expect(body).toBeNull()
      if (path.endsWith('/trash')) {
        expect(['active', 'archived']).toContain(note.lifecycle)
        preTrashState = note.lifecycle
        note = { ...note, lifecycle: 'trashed' }
      } else {
        expect(note.lifecycle).toBe('trashed')
        expect(preTrashState).not.toBeNull()
        note = { ...note, lifecycle: preTrashState! }
        preTrashState = null
      }
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
    await route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', headers,
      ...(status === 204 ? {} : { body: JSON.stringify(response) }) })
  })
  return calls
}

test('AI access changes preserve drafts and explicit Save adopts the current ETag', async ({ page }) => {
  const calls = await fakeNotesBackend(page)
  await page.goto('/notes/new')
  await page.getByLabel('Title').fill('Original synthetic title')
  await page.getByRole('button', { name: 'Create note' }).click()
  await expect(page).toHaveURL(new RegExp(`/notes/${id}$`))
  await page.getByLabel('Title').fill(' Exact draft title ')
  await (await sourceField(page)).fill('Exact\n**draft** ')
  await (await detailAction(page, 'Enable AI')).click()
  await expect((await detailAction(page, 'Disable AI'))).toBeEnabled()
  await expect(page.getByText('AI access is ON.')).toBeVisible()
  await expect(page.getByLabel('Title')).toHaveValue(' Exact draft title ')
  await expect((await sourceField(page))).toHaveValue('Exact\n**draft** ')
  await expect(page.getByText('Unsaved changes')).toBeVisible()
  expect(calls.filter(call => call.method === 'PUT')).toHaveLength(1)
  await page.getByRole('button', { name: 'Save note', exact: true }).click()
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  await (await detailAction(page, 'Disable AI')).click()
  await expect(page.getByText('AI access is OFF.')).toBeVisible()
  await expect((await detailAction(page, 'Enable AI'))).toBeEnabled()
  expect(calls.filter(call => call.method === 'PUT').map(call => call.path)).toEqual([
    `/api/notes/${id}/ai-access`, `/api/notes/${id}`, `/api/notes/${id}/ai-access`,
  ])
  expect(calls.some(call => /knowledge|processing|acknowledgement|ai-access-bulk/.test(call.path))).toBe(false)
})

test('permanent deletion requires renewed deliberate action after recent auth and clears Trash', async ({ page }) => {
  const calls = await fakeNotesBackend(page, true)
  await page.goto('/notes/new')
  await page.getByLabel('Title').fill('Saved synthetic note')
  await page.getByRole('button', { name: 'Create note' }).click()
  await expect(page).toHaveURL(new RegExp(`/notes/${id}$`))
  await expect(page.getByRole('button', { name: 'Permanent delete', exact: true })).toHaveCount(0)
  await (await detailAction(page, 'Trash')).click()
  await expect((await detailAction(page, 'Restore'))).toBeEnabled()
  await (await sourceField(page)).fill('Exact synthetic unsaved draft')
  await page.getByRole('button', { name: 'Permanent delete', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Keep note', exact: true })).toBeFocused()
  await page.getByRole('button', { name: 'Permanently delete and discard draft' }).click()
  await expect(page.getByRole('dialog', { name: 'Confirm your identity before deleting' })).toBeVisible()
  await expect((await sourceField(page))).toHaveValue('Exact synthetic unsaved draft')
  await page.getByRole('button', { name: 'Discard draft and confirm identity' }).click()
  await page.getByLabel('Password', { exact: true }).fill('Synthetic-password-123!')
  await page.getByRole('button', { name: 'Confirm with password' }).click()
  await expect(page).toHaveURL(new RegExp(`/notes/${id}$`))
  await expect(await detailAction(page, 'Permanent delete')).toBeEnabled()
  expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(1)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('button', { name: 'Permanent delete', exact: true }).click()
  await page.getByRole('button', { name: 'Permanently delete', exact: true }).click()
  await expect(page).toHaveURL(/\/notes$/)
  await expect(page.getByRole('button', { name: 'Trashed notes', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByText('No trashed notes', { exact: true })).toBeVisible()
  expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(2)
  expect(calls.some(call => call.method === 'PUT')).toBe(false)
})

for (const originalLifecycle of ['active', 'archived']) {
  test(`Trash and Restore preserve dirty drafts and ${originalLifecycle} list membership`, async ({ page }) => {
    const calls = await fakeNotesBackend(page)
    await page.goto('/notes/new')
    await page.getByLabel('Title').fill('Saved original')
    await page.getByRole('button', { name: 'Create note' }).click()
    await expect(page).toHaveURL(new RegExp(`/notes/${id}$`))
    if (originalLifecycle === 'archived') {
      await (await detailAction(page, 'Archive')).click()
      await expect((await detailAction(page, 'Return from archive'))).toBeVisible()
    }
    await page.getByLabel('Title').fill(' Exact draft title ')
    await (await sourceField(page)).fill('Exact\n**draft** ')
    await (await detailAction(page, 'Trash')).click()
    await expect(page.getByRole('dialog', { name: 'Trash with unsaved changes?' })).toBeVisible()
    await page.getByRole('button', { name: 'Trash and keep draft' }).click()
    await expect((await detailAction(page, 'Restore'))).toBeEnabled()
    await expect(page.getByRole('button', { name: 'Save note' })).toBeDisabled()
    await page.getByLabel('Title').press('ControlOrMeta+s')
    expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toHaveLength(0)
    await expect(page.getByLabel('Title')).toHaveValue(' Exact draft title ')
    await expect((await sourceField(page))).toHaveValue('Exact\n**draft** ')
    await (await detailAction(page, 'Restore')).click()
    await expect((await detailAction(page, 'Trash'))).toBeEnabled()
    await expect(page.getByLabel('Title')).toHaveValue(' Exact draft title ')
    await expect((await sourceField(page))).toHaveValue('Exact\n**draft** ')
    if (originalLifecycle === 'archived') {
      await expect(page.getByRole('button', { name: 'Save note' })).toBeDisabled()
      await (await detailAction(page, 'Return from archive')).click()
    }
    await expect(page.getByRole('button', { name: 'Save note' })).toBeEnabled()
    await page.getByRole('button', { name: 'Save note' }).click()
    await expect(page.getByText('Saved', { exact: true })).toBeVisible()
    if (originalLifecycle === 'archived') await (await detailAction(page, 'Archive')).click()
    await (await detailAction(page, 'Trash')).click()
    await expect((await detailAction(page, 'Restore'))).toBeEnabled()
    await page.getByRole('link', { name: 'All notes' }).click()
    await expect(page.getByText('No notes yet', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Archived notes' }).click()
    await expect(page.getByText('No archived notes', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Trashed notes' }).click()
    await page.getByRole('link', { name: /Exact draft title/ }).click()
    await (await detailAction(page, 'Restore')).click()
    await expect((await detailAction(page, 'Trash'))).toBeEnabled()
    await page.getByRole('link', { name: 'All notes' }).click()
    await expect(page.getByRole('button', { name: 'Trashed notes' })).toHaveAttribute('aria-pressed', 'true')
    await page.getByRole('button', { name: 'Active notes', exact: true }).click()
    if (originalLifecycle === 'archived') {
      await expect(page.getByText('No notes yet', { exact: true })).toBeVisible()
      await page.getByRole('button', { name: 'Archived notes' }).click()
    }
    await expect(page.getByRole('link', { name: /Exact draft title/ })).toBeVisible()
    await page.getByRole('button', { name: 'Trashed notes' }).click()
    await expect(page.getByText('No trashed notes', { exact: true })).toBeVisible()
    expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toHaveLength(1)
  })
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
  await (await sourceField(page)).fill('**Movie**\n\n![remote](https://untrusted.invalid/remote-image)')
  await page.getByText('Preview', { exact: true }).click()
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
  await expect(page.getByRole('dialog', { name: 'Leave with unsaved changes?' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'All notes', exact: true })).toBeFocused()
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
  await (await sourceField(page)).fill('Unsaved\n**Markdown** ')
  await (await detailAction(page, 'Pin')).click()
  await (await detailAction(page, 'Unpin')).click()
  await (await detailAction(page, 'Archive')).click()
  await expect(page.getByRole('dialog', { name: 'Archive with unsaved changes?' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Archive and keep draft' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect((await detailAction(page, 'Archive'))).toBeFocused()
  await (await detailAction(page, 'Archive')).click()
  await page.getByRole('button', { name: 'Archive and keep draft' }).click()
  await expect((await detailAction(page, 'Return from archive'))).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save note' })).toBeDisabled()
  await (await sourceField(page)).press('ControlOrMeta+s')
  expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toHaveLength(0)
  await expect(page.getByLabel('Title')).toHaveValue(' Unsaved title ')
  await expect((await sourceField(page))).toHaveValue('Unsaved\n**Markdown** ')
  await (await detailAction(page, 'Return from archive')).click()
  await expect(page.getByLabel('Title')).toHaveValue(' Unsaved title ')
  await expect((await sourceField(page))).toHaveValue('Unsaved\n**Markdown** ')
  await page.getByRole('button', { name: 'Save note' }).click()
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  await (await detailAction(page, 'Archive')).click()
  await expect((await detailAction(page, 'Return from archive'))).toBeVisible()
  await page.getByRole('link', { name: 'All notes' }).click()
  await expect(page.getByText('No notes yet', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Archived notes' }).click()
  await page.getByRole('link', { name: /Unsaved title/ }).click()
  await (await detailAction(page, 'Return from archive')).click()
  await expect((await detailAction(page, 'Archive'))).toBeVisible()
  await page.getByRole('link', { name: 'All notes' }).click()
  await expect(page.getByRole('button', { name: 'Archived notes' })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: 'Active notes', exact: true }).click()
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
  await (await sourceField(page)).fill('Unsaved\n**Markdown** ')
  await (await detailAction(page, 'Edit tags')).click()
  await page.getByText('Edit tags as lines', { exact: true }).click()
    await page.getByLabel('Tags, one per line').fill('Films\nWatch-later')
  await page.getByRole('button', { name: 'Apply tags' }).click()
  await expect(page.getByRole('listitem').filter({ hasText: 'Films' })).toBeVisible()
  await expect(page.getByLabel('Title')).toHaveValue(' Unsaved title ')
  await expect((await sourceField(page))).toHaveValue('Unsaved\n**Markdown** ')
  expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toHaveLength(0)
  await page.getByRole('button', { name: 'Save note' }).click()
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}/tags`)).toHaveLength(1)
})

test('library loading and failure are distinct from empty results and retry recovers', async ({ page }) => {
  await fakeNotesBackend(page)
  let release!: () => void
  const pending = new Promise<void>(resolve => { release = resolve })
  let first = true
  await page.route('**/api/notes', async route => {
    if (first) {
      first = false
      await pending
      await route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({
        type: 'about:blank', title: 'Unavailable', status: 503, code: 'service_unavailable',
        instance: '/api/notes', traceId: `tr_${'a'.repeat(32)}`,
      }) })
    } else await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [], nextCursor: null }) })
  })
  await page.goto('/notes')
  await expect(page.getByRole('status')).toHaveText('Loading notes…')
  await expect(page.getByRole('heading', { name: 'No notes yet' })).toHaveCount(0)
  release()
  await expect(page.getByRole('alert')).toContainText('Could not load notes')
  await page.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'No notes yet' })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

for (const width of [360, 390, 768, 1280, 1440]) {
  test(`Notes library and writing workspace stay usable at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    const calls = await fakeNotesBackend(page)
    await page.goto('/notes')
    await expect(page.getByRole('heading', { name: 'No notes yet' })).toBeVisible()
    await page.keyboard.press('Tab')
    await expect(page.getByRole('link', { name: 'Skip to Notes content' })).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('main')).toBeFocused()
    await noPageOverflow(page)
    const navigation = page.getByRole('navigation', { name: width < 768 ? 'Application' : 'Workspace navigation' })
    await expect(navigation).toBeVisible()
    await expect(navigation.getByRole('link')).toHaveCount(width < 768 ? 3 : 2)
    await expect(page.getByRole('searchbox')).toHaveCount(0)
    await page.getByRole('link', { name: /New note/ }).filter({ visible: true }).click()
    await page.getByLabel('Title', { exact: true }).fill('Small things worth keeping')
    await (await sourceField(page)).fill('# A quiet afternoon\n\nA few **ideas** for later.\n\n- Read a chapter\n- Take a walk\n\n```text\n' + 'synthetic-long-line-'.repeat(30) + '\n```')
    await page.getByText('Preview', { exact: true }).click()
    await expect(page.locator('.source-preview')).toBeVisible()
    await noPageOverflow(page)
    await page.getByRole('button', { name: 'Create note' }).click()
    await expect(page).toHaveURL(new RegExp(`/notes/${id}$`))
    await page.getByRole('button', { name: 'Details', exact: true }).click()
    const tools = page.getByRole('complementary', { name: 'Note tools' })
    for (const name of ['Pin', 'Archive', 'Trash', 'Edit tags', 'Enable AI']) {
      const button = tools.getByRole('button', { name, exact: true })
      await expect(button).toBeVisible()
      expect((await button.boundingBox())!.height).toBeGreaterThanOrEqual(44)
    }
    if (width >= 1280) await expect(page.locator('.notes-authoring')).toBeVisible()
    else await expect(page.locator('.note-document')).toBeHidden()
    await (await detailAction(page, 'Pin')).click()
    await (await detailAction(page, 'Edit tags')).click()
    await page.getByText('Edit tags as lines', { exact: true }).click()
    await page.getByLabel('Tags, one per line').fill('Ideas\n' + 't'.repeat(100))
    await page.getByRole('button', { name: 'Apply tags' }).click()
    await expect(page.getByRole('listitem').filter({ hasText: 'Ideas' })).toBeVisible()
    await noPageOverflow(page)
    await page.screenshot({ path: testInfo.outputPath(`editor-${width}.png`), fullPage: true })
    await page.getByRole('button', { name: /Close details/ }).click()
    await page.getByLabel('Title', { exact: true }).fill('A deliberately unsaved thought')
    await expect(page.getByText('Unsaved changes', { exact: true })).toBeVisible()
    const allNotes = width < 768 ? page.getByRole('link', { name: 'Back to notes', exact: true }) : page.getByRole('link', { name: 'All notes', exact: true })
    await allNotes.click()
    const leaveDialog = page.getByRole('dialog', { name: 'Leave with unsaved changes?' })
    await expect(leaveDialog).toBeVisible()
    await expect(page.getByRole('button', { name: 'Keep editing', exact: true })).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await expect(page.getByRole('button', { name: 'Discard changes and leave' })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(page.getByRole('button', { name: 'Keep editing', exact: true })).toBeFocused()
    await page.keyboard.press('ControlOrMeta+s')
    expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toHaveLength(0)
    await noPageOverflow(page)
    await page.screenshot({ path: testInfo.outputPath(`dialog-${width}.png`) })
    await page.keyboard.press('Escape')
    await expect(leaveDialog).toHaveCount(0)
    await expect(allNotes).toBeFocused()
    await expect(page.getByLabel('Title', { exact: true })).toHaveValue('A deliberately unsaved thought')
    await (await sourceField(page)).press('ControlOrMeta+s')
    await expect(page.getByText('Saved', { exact: true })).toBeVisible()
    await allNotes.click()
    await expect(page.getByRole('link', { name: /A deliberately unsaved thought/ })).toBeVisible()
    await expect(page.getByText('Pinned', { exact: true })).toBeVisible()
    await noPageOverflow(page)
    await page.screenshot({ path: testInfo.outputPath(`library-${width}.png`), fullPage: true })
    await page.getByRole('button', { name: 'Archived notes' }).click()
    await expect(page.getByRole('heading', { name: 'No archived notes' })).toBeVisible()
    await page.getByRole('button', { name: 'Trashed notes' }).click()
    await expect(page.getByRole('heading', { name: 'No trashed notes' })).toBeVisible()
    await noPageOverflow(page)
  })
}
