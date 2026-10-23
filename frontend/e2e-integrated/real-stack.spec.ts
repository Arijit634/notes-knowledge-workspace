import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { openTools, sourceEditor } from '../e2e/editor-support'
import { control, csrf, logout, mutate, noOverflow, observation, register, runtime } from './stack-support'

// No page.route/route.fulfill: every application request reaches Spring and PostgreSQL.
test.beforeEach(async () => { await control('isolate-transient-rate-state') })
test('real browser persists explicit Notes, isolates owners and uses deterministic search', async ({ page, browser }, info) => {
  const started = performance.now()
  await register(page)
  await observation(info, 'browser-and-startup', { browser: info.project.name, version: browser.version(),
    registrationVerificationLoginAndLibraryMs: Math.round(performance.now() - started), samples: 1, percentileClaim: false })
  await page.goto('/notes/new')
  await page.getByLabel('Title', { exact: true }).fill('Fictional Aster observatory')
  await (await sourceEditor(page)).fill('Original private observation. https://example.test/aster')
  await page.getByRole('button', { name: 'Create note', exact: true }).click()
  await expect(page).toHaveURL(/\/notes\/[0-9a-f-]{36}$/)
  const noteId = new URL(page.url()).pathname.split('/').at(-1)!
  await page.reload()
  await expect(page.getByLabel('Title', { exact: true })).toHaveValue('Fictional Aster observatory')
  await (await sourceEditor(page)).fill('Persisted violet observatory. https://example.test/aster')
  const beforeSave = await page.request.get('/api/notes/' + noteId)
  expect((await beforeSave.json()).markdown).toContain('Original private observation')
  await page.getByRole('button', { name: 'Save note', exact: true }).click()
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  await page.reload()
  await expect(await sourceEditor(page)).toHaveValue('Persisted violet observatory. https://example.test/aster')
  const persisted = await page.request.get('/api/notes/' + noteId)
  expect((await persisted.json()).aiEnabled).toBe(false)
  expect(persisted.headers()['cache-control']).toBe('no-store')
  expect(persisted.headers().etag).toMatch(/^".+"$/)
  const withoutCsrf = await page.request.post('/api/notes', { data: { title: 'Must not exist', markdown: '' } })
  expect(withoutCsrf.status()).toBe(403)
  await page.getByRole('button', { name: 'Edit tags', exact: true }).click()
  await page.getByLabel('Tags, one per line').fill('astronomy\nsynthetic')
  await page.getByRole('button', { name: 'Apply tags', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Edit tags', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Pin', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Unpin', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Archive', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Return from archive', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Return from archive', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Archive', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Trash', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Restore', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Restore', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Archive', exact: true })).toBeVisible()
  await page.goto('/settings/privacy')
  await expect(page.getByLabel('Current approved processing disclosure')).toContainText('SYNTHETIC TEST FIXTURE ONLY')
  await page.getByRole('checkbox', { name: 'Default AI access for new notes', exact: true }).click()
  await expect(page.getByText('Future-note preference saved. Existing notes were not changed.')).toBeVisible()
  await expect(page.getByRole('checkbox', { name: 'Default AI access for new notes', exact: true })).toBeChecked()
  expect((await (await page.request.get('/api/notes/' + noteId)).json()).aiEnabled).toBe(false)
  expect((await (await page.request.get('/api/ai/processing-policy')).json()).acknowledged).toBe(false)
  await page.getByRole('button', { name: 'I acknowledge this processing disclosure' }).click()
  await expect(page.getByText('Disclosure acknowledged. No Note AI choice was changed.')).toBeVisible()
  expect((await (await page.request.get('/api/ai/processing-policy')).json()).acknowledged).toBe(true)
  expect((await (await page.request.get('/api/notes/' + noteId)).json()).aiEnabled).toBe(false)
  await page.goto('/settings/profile')
  await page.getByLabel('Choose avatar').setInputFiles(resolve(runtime(), 'fixtures', 'scene.png'))
  await expect(page.getByRole('img', { name: 'Your private profile avatar' })).toHaveAttribute('src', /^blob:/)
  await page.reload()
  await expect(page.getByRole('img', { name: 'Your private profile avatar' })).toHaveAttribute('src', /^blob:/)
  const privateAvatar = await page.request.get('/api/me/profile/avatar/content')
  expect(privateAvatar.status()).toBe(200); expect(privateAvatar.headers()['cache-control']).toBe('no-store')
  expect(privateAvatar.headers()['content-type']).toBe('image/png')
  const unauthenticated = await browser.newContext({ baseURL: 'https://localhost:4175' })
  try { expect((await unauthenticated.request.get('/api/me/profile/avatar/content')).status()).toBe(401) }
  finally { await unauthenticated.close() }
  await page.getByRole('button', { name: 'Remove avatar' }).click()
  await expect(page.getByRole('img', { name: 'Your private profile avatar' })).toHaveCount(0)
  expect((await page.request.get('/api/me/profile/avatar/content')).status()).toBe(404)
  await page.goto('/ask')
  await page.getByRole('button', { name: 'Ordinary search', exact: true }).click()
  await page.getByLabel('Search saved notes', { exact: true }).fill('observatory')
  await page.getByRole('button', { name: 'Search notes', exact: true }).click()
  await expect(page.getByRole('link', { name: /Fictional Aster observatory/ })).toBeVisible()
  await page.getByRole('button', { name: 'Ask your knowledge', exact: true }).first().click()
  await page.getByLabel('What would you like to find?').fill('List every URL')
  await page.getByRole('button', { name: 'Ask your knowledge', exact: true }).last().click()
  await expect(page.getByText('Request completed.', { exact: true })).toBeVisible({ timeout: 30_000 })
  await expect(page.getByRole('region', { name: 'Deterministic results' })).toContainText('https://example.test/aster')
  await page.screenshot({ path: info.outputPath('real-deterministic-ask.png'), animations: 'disabled' })
  const other = await browser.newContext({ baseURL: 'https://localhost:4175' })
  try {
    const second = await other.newPage(); await register(second)
    expect((await second.request.get('/api/notes/' + noteId)).status()).toBe(404)
    await expect(second.getByText('Fictional Aster observatory')).toHaveCount(0)
    expect((await second.request.get('/api/moderation/reports')).status()).toBe(403)
  } finally { await other.close() }
  await logout(page)
  expect((await page.request.get('/api/notes/' + noteId)).status()).toBe(401)
  expect(await page.evaluate(() => Object.keys(localStorage).length + Object.keys(sessionStorage).length)).toBe(0)
})

test('real storage validates four media kinds, serves bytes/ranges and denies removed media', async ({ page }, info) => {
  await register(page); await page.goto('/notes/new')
  await page.getByLabel('Title', { exact: true }).fill('Synthetic media custody')
  await page.getByRole('button', { name: 'Create note', exact: true }).click()
  await expect(page).toHaveURL(/\/notes\/[0-9a-f-]{36}$/)
  const noteId = new URL(page.url()).pathname.split('/').at(-1)!
  await openTools(page)
  const fixtureNames = ['scene.png', 'fictional.pdf', 'aster.wav', 'scene.mp4']
  for (const name of fixtureNames) {
    await page.getByLabel('Add file', { exact: true }).setInputFiles(resolve(runtime(), 'fixtures', name))
    await expect(page.getByRole('button', { name: 'Open ' + name, exact: true })).toBeVisible({ timeout: 45_000 })
  }
  const metadata = await page.request.get(`/api/notes/${noteId}/attachments`)
  expect(metadata.status()).toBe(200)
  const files = (await metadata.json()).items as Array<{ id: string; mediaKind: string }>
  expect(files.map(f => f.mediaKind).sort()).toEqual(['audio', 'image', 'pdf', 'video'])
  for (const file of files) {
    const path = `/api/notes/${noteId}/attachments/${file.id}/content`
    const bytes = await page.request.get(path)
    expect(bytes.status()).toBe(200)
    expect(bytes.headers()['x-content-type-options']).toBe('nosniff')
    expect(bytes.headers()['cache-control']).toBe('no-store')
    expect((await bytes.body()).length).toBeGreaterThan(40)
    const range = await page.request.get(path, { headers: { Range: 'bytes=0-31' } })
    expect(range.status()).toBe(206); expect((await range.body()).length).toBe(32)
  }
  await page.getByRole('button', { name: 'Open scene.png', exact: true }).click()
  const viewer = page.getByRole('dialog', { name: 'scene.png', exact: true })
  await expect(viewer.getByRole('img')).toBeVisible()
  expect(await viewer.getByRole('img').evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true)
  await page.screenshot({ path: info.outputPath('real-image-viewer.png'), animations: 'disabled' })
  await page.keyboard.press('Escape'); await expect(viewer).not.toBeVisible()
  for (const [name, element] of [['aster.wav', 'audio'], ['scene.mp4', 'video']] as const) {
    await nativePlayback(page, info, name, element)
  }
  const image = files.find(f => f.mediaKind === 'image')!
  await page.getByRole('button', { name: 'Remove scene.png', exact: true }).click()
  await page.getByRole('button', { name: 'Remove file', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Open scene.png', exact: true })).toHaveCount(0)
  expect((await page.request.get(`/api/notes/${noteId}/attachments/${image.id}/content`)).status()).toBe(404)
  await page.reload(); await openTools(page)
  await expect(page.getByRole('button', { name: 'Open fictional.pdf', exact: true })).toBeVisible()
  const broken = await page.request.post(`/api/notes/${noteId}/attachments`, { headers: { 'X-CSRF-TOKEN': await csrf(page.request) },
    multipart: { file: { name: 'broken.png', mimeType: 'image/png', buffer: Buffer.from('not a valid image') } } })
  expect([415, 422]).toContain(broken.status())
  await noOverflow(page)
})

// Keep the original four-kind journey, including its failing WAV assertion.
// A separate video journey prevents a WAV decoder failure from masking MP4.
for (const fixture of ['scene.mp4', 'multiframe.mp4']) test(`real native ${fixture} playback qualifies independently from WAV`, async ({ page }, info) => {
  await register(page); await page.goto('/notes/new')
  await page.getByLabel('Title', { exact: true }).fill('Independent synthetic video')
  await page.getByRole('button', { name: 'Create note', exact: true }).click()
  await expect(page).toHaveURL(/\/notes\/[0-9a-f-]{36}$/)
  const noteId = new URL(page.url()).pathname.split('/').at(-1)!
  await openTools(page)
  await page.getByLabel('Add file', { exact: true }).setInputFiles(resolve(runtime(), 'fixtures', fixture))
  await expect(page.getByRole('button', { name: `Open ${fixture}`, exact: true })).toBeVisible({ timeout: 45_000 })
  const metadata = await page.request.get(`/api/notes/${noteId}/attachments`)
  expect(metadata.status()).toBe(200)
  const file = (await metadata.json()).items[0] as { id: string; mediaKind: string }
  expect(file.mediaKind).toBe('video')
  const path = `/api/notes/${noteId}/attachments/${file.id}/content`
  const range = await page.request.get(path, { headers: { Range: 'bytes=0-31' } })
  expect(range.status()).toBe(206); expect((await range.body()).length).toBe(32)
  await nativePlayback(page, info, fixture, 'video')
  await page.getByRole('button', { name: `Remove ${fixture}`, exact: true }).click()
  await page.getByRole('button', { name: 'Remove file', exact: true }).click()
  await expect(page.getByRole('button', { name: `Open ${fixture}`, exact: true })).toHaveCount(0)
  expect((await page.request.get(path)).status()).toBe(404)
})

async function nativePlayback(page: Page, info: TestInfo, name: string, element: 'audio' | 'video') {
  // Capture native error codes before React removes the failed player. Only a
  // bounded enum/numeric record, never URLs, bytes, cookies or exception text.
  await page.evaluate(() => {
    const state = window as typeof window & { nativeObserverInstalled?: boolean; nativeMediaFailure?: Record<string, string | number | null> }
    state.nativeMediaFailure = undefined
    if (state.nativeObserverInstalled) return
    state.nativeObserverInstalled = true
    document.addEventListener('error', event => {
      const media = event.target
      if (media instanceof HTMLMediaElement) state.nativeMediaFailure = {
        element: media.tagName, errorCode: media.error?.code ?? null,
        readyState: media.readyState, networkState: media.networkState,
      }
    }, true)
  })
  await page.getByRole('button', { name: `Open ${name}`, exact: true }).click()
  const dialog = page.getByRole('dialog', { name, exact: true }), player = dialog.locator(element)
  try {
    await expect(player).toBeVisible()
    await expect.poll(() => player.evaluate((media: HTMLMediaElement) =>
      media.readyState >= 1 && Number.isFinite(media.duration) && media.duration > 0)).toBe(true)
    // Loop short frozen clips; still require real, advancing native playback.
    await player.evaluate(async (media: HTMLMediaElement) => { media.muted = true; media.loop = true; await media.play() })
    await expect.poll(() => player.evaluate((media: HTMLMediaElement) => media.currentTime)).toBeGreaterThan(0)
  } catch (failure) {
    const state = await page.evaluate(() => {
      const dialog = document.querySelector('[role="dialog"]'), media = dialog?.querySelector<HTMLMediaElement>('audio, video')
      return { mediaRemoved: !media, safeFailureVisible: dialog?.textContent?.includes('This attachment could not be loaded.') ?? false,
        nativeFailure: (window as typeof window & { nativeMediaFailure?: Record<string, string | number | null> }).nativeMediaFailure ?? null,
        ...(media ? { element: media.tagName, readyState: media.readyState, networkState: media.networkState,
          errorCode: media.error?.code ?? null, finitePositiveDuration: Number.isFinite(media.duration) && media.duration > 0,
          currentTime: media.currentTime, paused: media.paused, ended: media.ended, loop: media.loop,
          bufferedRanges: media.buffered.length } : {}) }
    }).catch(() => ({ observerUnavailable: true }))
    await observation(info, 'native-media-state', { name, ...state })
    throw failure
  }
  await page.keyboard.press('Escape'); await expect(dialog).not.toBeVisible()
}

test('real publication snapshots, public engagement and protected moderation stay separate', async ({ page, browser }, info) => {
  const owner = await register(page)
  await page.goto('/settings/public-profile')
  await page.getByLabel('Display name', { exact: true }).fill('Synthetic observer')
  await page.getByLabel('Public handle', { exact: true }).fill('observer_' + owner.slice(8, 20))
  await page.getByLabel('Biography', { exact: true }).fill('Fictional public observations only.')
  await page.getByRole('button', { name: 'Save profile fields', exact: true }).click()
  await page.getByRole('button', { name: 'Activate public profile', exact: true }).click()
  await page.getByRole('button', { name: 'Confirm public profile', exact: true }).click()
  await expect(page.getByText('Public profile is active and refreshed. No Note was published.')).toBeVisible()
  await page.goto('/notes/new')
  await page.getByLabel('Title', { exact: true }).fill('Public fictional observatory')
  await (await sourceEditor(page)).fill('Approved public snapshot.')
  await page.getByRole('button', { name: 'Create note', exact: true }).click()
  await expect(page).toHaveURL(/\/notes\/[0-9a-f-]{36}$/)
  const noteId = new URL(page.url()).pathname.split('/').at(-1)!
  await openTools(page)
  await page.getByLabel('Add file', { exact: true }).setInputFiles(resolve(runtime(), 'fixtures', 'scene.png'))
  await expect(page.getByRole('button', { name: 'Open scene.png', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Publish', exact: true }).click()
  await page.getByRole('checkbox', { name: /scene\.png/ }).check()
  await page.getByRole('button', { name: 'Preview public copy', exact: true }).click()
  await page.getByRole('button', { name: 'Confirm publication', exact: true }).click()
  await expect(page.getByRole('link', { name: 'View published copy', exact: true })).toBeVisible()
  const link = await page.getByRole('link', { name: 'View published copy', exact: true }).getAttribute('href')
  const publicationId = link!.split('/').at(-1)!
  await page.goto(`/publications/${publicationId}`); await page.reload()
  await expect(page.getByRole('link', { name: 'Open source Note' })).toHaveAttribute('href', `/notes/${noteId}`)
  const ownerCopy = await page.request.get(`/api/me/publications/${publicationId}`)
  expect((await ownerCopy.json()).sourceNoteId).toBe(noteId)
  await page.getByRole('link', { name: 'Open source Note' }).click()
  await expect(page).toHaveURL(`/notes/${noteId}`)
  const anonymous = await browser.newContext({ baseURL: 'https://localhost:4175' })
  try {
    const reader = await anonymous.newPage()
    await reader.goto(link!); await expect(reader.getByText('Approved public snapshot.', { exact: true })).toBeVisible()
    const article = await reader.request.get(`/api/public/publications/${publicationId}`)
    const publicSnapshot = await article.json()
    expect(publicSnapshot).not.toHaveProperty('sourceNoteId')
    expect(publicSnapshot.media).toHaveLength(1)
    const publicMediaUrl = publicSnapshot.media[0].contentUrl as string
    expect((await reader.request.get(publicMediaUrl)).status()).toBe(200)
    // Later private byte deletion cannot mutate the separately copied public snapshot.
    const privateFiles = await page.request.get(`/api/notes/${noteId}/attachments`)
    const privateImage = (await privateFiles.json()).items[0].id as string
    const privateDetail = await page.request.get(`/api/notes/${noteId}/attachments/${privateImage}`)
    expect((await mutate(page.request, 'DELETE', `/api/notes/${noteId}/attachments/${privateImage}`, undefined, privateDetail.headers().etag)).status()).toBe(204)
    expect((await reader.request.get(publicMediaUrl)).status()).toBe(200)
    expect((await reader.request.get('/api/notes/' + noteId)).status()).toBe(401)
    await (await sourceEditor(page)).fill('Private revision is not yet public.')
    await page.getByRole('button', { name: 'Save note', exact: true }).click()
    await expect(page.getByText('Saved', { exact: true })).toBeVisible()
    await reader.reload(); await expect(reader.getByText('Approved public snapshot.', { exact: true })).toBeVisible()
    await expect(reader.getByText('Private revision is not yet public.', { exact: true })).toHaveCount(0)
    // Publication created a guaranteed checkpoint; no wall-clock sleep/cadence assumption.
    await page.getByRole('button', { name: 'Show version history', exact: true }).click()
    await page.getByRole('button', { name: 'Inspect Public fictional observatory', exact: true }).click()
    await page.getByRole('button', { name: 'Restore this checkpoint', exact: true }).click()
    await page.getByRole('button', { name: 'Confirm restore', exact: true }).click()
    await expect(await sourceEditor(page)).toHaveValue('Approved public snapshot.')
    await (await sourceEditor(page)).fill('Private revision is not yet public.')
    await page.getByRole('button', { name: 'Save note', exact: true }).click()
    await expect(page.getByText('Saved', { exact: true })).toBeVisible()
    // A reopened Note editor has no prior editor-instance publication state.
    // The owner Publication page resolves the current source and update authority.
    await page.goto(`/publications/${publicationId}`)
    await page.getByRole('button', { name: 'Update public copy', exact: true }).click()
    await page.getByRole('button', { name: 'Preview public copy', exact: true }).click()
    await page.getByRole('button', { name: 'Confirm public update', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await reader.reload(); await expect(reader.getByText('Private revision is not yet public.', { exact: true })).toBeVisible()
    expect((await reader.request.get(publicMediaUrl)).status()).toBe(404)
    await reader.screenshot({ path: info.outputPath('real-public-snapshot.png'), animations: 'disabled' })
    // A separate real Account reports the deliberately published snapshot.
    const reporter = await register(reader)
    await reader.goto(link!)
    expect((await mutate(reader.request, 'PUT', `/api/public/publications/${publicationId}/like`)).status()).toBe(204)
    expect((await mutate(reader.request, 'PUT', `/api/public/publications/${publicationId}/like`)).status()).toBe(204)
    expect((await mutate(reader.request, 'DELETE', `/api/public/publications/${publicationId}/like`)).status()).toBe(204)
    const report = await mutate(reader.request, 'POST', `/api/public/publications/${publicationId}/reports`, { category: 'other', description: 'Fictional review-only report.' })
    expect(report.status()).toBe(201)
    const reportId = (await report.json()).id
    expect((await reader.request.get('/api/moderation/reports')).status()).toBe(403)
    await control('grant-moderation', reporter)
    await reader.goto(`/moderation/reports/${reportId}`)
    await expect(reader.getByRole('heading', { name: 'Public report review', exact: true })).toBeVisible()
    expect((await reader.request.get('/api/notes/' + noteId)).status()).toBe(404)
    await reader.getByRole('button', { name: 'Begin review', exact: true }).click()
    await reader.getByLabel('Consequence', { exact: true }).selectOption('removePublication')
    await reader.getByRole('button', { name: 'Review decision', exact: true }).click()
    await reader.getByRole('button', { name: 'Confirm decision', exact: true }).click()
    await expect(reader.getByText(/Decision recorded/)).toBeVisible()
    expect((await page.request.get(`/api/public/publications/${publicationId}`)).status()).toBe(404)
    const publicSearch = await reader.request.get('/api/public/search?q=observatory')
    expect(publicSearch.status()).toBe(200)
    expect((await publicSearch.json()).items).toHaveLength(0)
    await reader.screenshot({ path: info.outputPath('real-moderation-result.png'), animations: 'disabled' })
  } finally { await anonymous.close() }
})

test('real private library and explicit editor remain usable at five widths with reduced motion', async ({ page }, info) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await register(page)
  await page.goto('/notes/new')
  await page.getByLabel('Title', { exact: true }).fill('Fictional long observatory title for a narrow writing surface')
  await (await sourceEditor(page)).fill('A fictional paragraph preserves a comfortable writing surface across narrow layouts.\n\nNo private data or live provider is involved.')
  await page.getByRole('button', { name: 'Create note', exact: true }).click()
  await expect(page).toHaveURL(/\/notes\/[0-9a-f-]{36}$/)
  const editor = new URL(page.url()).pathname
  for (const width of [1440, 1024, 834, 390, 320]) {
    await page.setViewportSize({ width, height: width === 320 ? 568 : 900 })
    await page.goto('/notes')
    await expect(page.getByText('Fictional long observatory title for a narrow writing surface', { exact: true }).first()).toBeVisible()
    await noOverflow(page)
    await page.screenshot({ path: info.outputPath(`real-private-library-${width}.png`), animations: 'disabled' })
    await page.goto(editor)
    await expect(page.getByLabel('Title', { exact: true })).toHaveValue('Fictional long observatory title for a narrow writing surface')
    await noOverflow(page)
    await page.screenshot({ path: info.outputPath(`real-private-editor-${width}.png`), animations: 'disabled' })
  }
})

for (const width of [1440, 1024, 834, 390, 320]) test(`real anonymous shell has bounded layout at ${width}px`, async ({ page }, info) => {
  const started = performance.now()
  await page.setViewportSize({ width, height: width === 320 ? 568 : 900 })
  const document = await page.goto('/explore')
  if (process.env.NKW_INTEGRATION_PACKAGED === '1') {
    expect(document?.headers()['content-security-policy']).toContain("object-src 'none'")
    expect(document?.headers()['cache-control']).toContain('no-cache')
    expect(!(await document!.text()).includes('/@vite/client')).toBe(true)
    const entry = await page.locator('script[type="module"][src^="/assets/"]').getAttribute('src')
    expect(entry).toMatch(/^\/assets\/[A-Za-z0-9_-]+\.js$/)
    const asset = await page.request.get(entry!)
    expect(asset.status()).toBe(200); expect(asset.headers()['cache-control']).toContain('immutable')
  }
  await expect(page.getByRole('heading', { name: 'A little perspective.' })).toBeVisible()
  await expect(page.getByText('Opening the library…')).toHaveCount(0)
  await noOverflow(page)
  const labels = await page.locator('button:visible, input:visible, select:visible, textarea:visible').evaluateAll(elements => elements.every(element => {
    if (element instanceof HTMLInputElement || element instanceof HTMLSelectElement || element instanceof HTMLTextAreaElement)
      return element.labels?.length || element.getAttribute('aria-label') || element.getAttribute('aria-labelledby')
    return element.textContent?.trim() || element.getAttribute('aria-label') || element.getAttribute('aria-labelledby')
  }))
  expect(labels).toBe(true)
  await observation(info, 'layout-observation', { browser: info.project.name, width,
    firstUsefulPublicLibraryMs: Math.round(performance.now() - started), samples: 1, automatedLabelSmoke: true,
    wcagCertification: false, physicalDevice: false })
  await page.screenshot({ path: info.outputPath(`real-explore-${width}.png`), animations: 'disabled' })
  const receipt = JSON.parse(await readFile(resolve(runtime(), 'ready.json'), 'utf8'))
  expect(receipt.relations).toBe(38); expect(receipt.runtimeRoleRestricted).toBe(true)
})
