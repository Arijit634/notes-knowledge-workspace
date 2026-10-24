import { expect, test } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { openTools, sourceEditor } from '../e2e/editor-support'
import { csrf, control, logout, mutate, observation, register, runtime } from './stack-support'

test.beforeEach(async ({ page }) => {
  await control('isolate-transient-rate-state')
  await page.addInitScript(() => document.addEventListener('error', event => {
    const media = event.target
    if (media instanceof HTMLMediaElement) {
      (window as typeof window & { qualificationMediaError?: unknown }).qualificationMediaError = {
        element: media.tagName, errorCode: media.error?.code ?? null,
        readyState: media.readyState, networkState: media.networkState,
      }
    }
  }, true))
})
test.afterEach(async ({ page }, info) => {
  if (info.status !== info.expectedStatus) {
    await observation(info, 'workflow-native-error', await page.evaluate(() =>
      (window as typeof window & { qualificationMediaError?: unknown }).qualificationMediaError ?? null))
  }
})

test('real image and PDF custody, page links and owner denial are independent from codecs', async ({ page, browser }, info) => {
  await register(page); await page.goto('/notes/new')
  await page.getByLabel('Title', { exact: true }).fill('Synthetic independent media checks')
  await page.getByRole('button', { name: 'Create note', exact: true }).click()
  await expect(page).toHaveURL(/\/notes\/[0-9a-f-]{36}$/)
  const noteId = new URL(page.url()).pathname.split('/').at(-1)!
  await (await sourceEditor(page)).fill('Unsaved synthetic body must remain local.')
  const evidence: unknown[] = []
  for (const name of ['scene.png', 'fictional.pdf']) {
    await page.getByLabel('Add file', { exact: true }).setInputFiles(resolve(runtime(), 'fixtures', name))
    await expect(page.getByRole('button', { name: `Open ${name}`, exact: true })).toBeVisible({ timeout: 45_000 })
  }
  const files = (await (await page.request.get(`/api/notes/${noteId}/attachments`)).json()).items as
    Array<{ id: string; mediaKind: string; displayFilename: string; sizeBytes: number }>
  expect(files.map(file => file.mediaKind).sort()).toEqual(['image', 'pdf'])
  for (const file of files) {
    const path = `/api/notes/${noteId}/attachments/${file.id}/content`
    const full = await page.request.get(path), body = await full.body()
    expect(full.status()).toBe(200); expect(body.length).toBe(file.sizeBytes)
    expect(full.headers()['content-type']).toBe(file.mediaKind === 'image' ? 'image/png' : 'application/pdf')
    expect(full.headers()['x-content-type-options']).toBe('nosniff')
    expect(full.headers()['cache-control']).toBe('no-store')
    expect(full.headers()['accept-ranges']).toBe('bytes')
    const range = await page.request.get(path, { headers: { Range: 'bytes=0-31' } })
    expect(range.status()).toBe(206)
    expect(range.headers()['content-range']).toBe(`bytes 0-31/${body.length}`)
    expect((await range.body()).equals(body.subarray(0, 32))).toBe(true)
    expect((await page.request.get(path, { headers: { Range: 'bytes=999999999-' } })).status()).toBe(416)
    expect((await (await page.request.get(path)).body()).equals(body)).toBe(true)
    const input = await readFile(resolve(runtime(), 'fixtures', file.displayFilename))
    evidence.push({ mediaKind: file.mediaKind, inputSha256: createHash('sha256').update(input).digest('hex'),
      acceptedSha256: createHash('sha256').update(body).digest('hex'), acceptedBytes: body.length,
      status: full.status(), rangeStatus: range.status(), contentType: full.headers()['content-type'],
      nosniff: true, noStore: true, exactRangeBytes: true, unsatisfiableRangeStatus: 416 })
  }
  await page.getByRole('button', { name: 'Open fictional.pdf', exact: true }).click()
  const pdf = page.getByRole('dialog', { name: 'fictional.pdf', exact: true })
  await expect(pdf.getByLabel('PDF page', { exact: true })).toHaveValue('1')
  await pdf.getByLabel('PDF page', { exact: true }).fill('2')
  await expect(pdf.getByRole('link', { name: 'Open PDF', exact: true })).toHaveAttribute('href', /\/content#page=2$/)
  await expect(pdf.locator('iframe,object,embed')).toHaveCount(0)
  await page.keyboard.press('Escape'); await expect(pdf).not.toBeVisible()
  await expect(page.getByRole('button', { name: 'Open fictional.pdf', exact: true })).toBeFocused()
  await expect(await sourceEditor(page)).toHaveValue('Unsaved synthetic body must remain local.')
  expect((await (await page.request.get(`/api/notes/${noteId}`)).json()).markdown).toBe('')
  const broken = await page.request.post(`/api/notes/${noteId}/attachments`, {
    headers: { 'X-CSRF-TOKEN': await csrf(page.request) }, multipart: {
      file: { name: 'broken.png', mimeType: 'image/png', buffer: Buffer.from('not an image') },
    },
  })
  expect([415, 422]).toContain(broken.status())
  const other = await browser.newContext({ baseURL: 'https://localhost:4175' })
  try {
    const stranger = await other.newPage(); await register(stranger)
    for (const file of files) {
      const path = `/api/notes/${noteId}/attachments/${file.id}`
      expect((await stranger.request.get(path)).status()).toBe(404)
      // Owner denial precedes even invalid range validation.
      expect((await stranger.request.get(path + '/content', { headers: { Range: 'bytes=999999999-' } })).status()).toBe(404)
    }
  } finally { await other.close() }
  const image = files.find(file => file.mediaKind === 'image')!
  const path = `/api/notes/${noteId}/attachments/${image.id}`
  const detail = await page.request.get(path)
  expect((await mutate(page.request, 'DELETE', path, undefined, '"stale-synthetic-validator"')).status()).toBe(412)
  expect((await mutate(page.request, 'DELETE', path, undefined, detail.headers().etag)).status()).toBe(204)
  expect((await page.request.get(path + '/content')).status()).toBe(404)
  await page.getByRole('button', { name: 'Open scene.png', exact: true }).click()
  const stale = page.getByRole('dialog', { name: 'Attachment', exact: true })
  await expect(stale.getByRole('alert')).toBeVisible()
  await expect(stale.locator('img,audio,video,iframe,object,embed')).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(await sourceEditor(page)).toHaveValue('Unsaved synthetic body must remain local.')
  await observation(info, 'media-custody-and-pdf', { files: evidence, crossOwnerDenied: true,
    staleValidatorDenied: true, privateDeletionDenied: true, staleViewerDenied: true,
    draftPreserved: true, selectedPageLink: 2, physicalPdfReaderQualified: false, invalidUploadStatus: broken.status() })
})

test('real audio close stops playback and logout denies subsequent media access', async ({ page }, info) => {
  await register(page); await page.goto('/notes/new')
  await page.getByLabel('Title', { exact: true }).fill('Synthetic native cleanup')
  await page.getByRole('button', { name: 'Create note', exact: true }).click()
  await expect(page).toHaveURL(/\/notes\/[0-9a-f-]{36}$/)
  const noteId = new URL(page.url()).pathname.split('/').at(-1)!
  await openTools(page)
  await page.getByLabel('Add file', { exact: true }).setInputFiles(resolve(runtime(), 'fixtures', 'aster.wav'))
  await expect(page.getByRole('button', { name: 'Open aster.wav', exact: true })).toBeVisible({ timeout: 45_000 })
  const file = (await (await page.request.get(`/api/notes/${noteId}/attachments`)).json()).items[0]
  await page.getByRole('button', { name: 'Open aster.wav', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'aster.wav', exact: true }), audio = dialog.locator('audio')
  await expect(audio).toHaveAttribute('controls', '')
  await expect.poll(() => audio.evaluate((media: HTMLAudioElement) => Number.isFinite(media.duration) && media.duration > 0)).toBe(true)
  await audio.evaluate(async (media: HTMLAudioElement) => { media.muted = true; await media.play() })
  await expect.poll(() => audio.evaluate((media: HTMLAudioElement) => media.currentTime)).toBeGreaterThan(0)
  const retainedElement = await audio.elementHandle()
  expect(retainedElement).not.toBeNull()
  await dialog.getByRole('button', { name: 'Close attachment', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  // Retaining the native element makes cleanup explicit rather than relying on GC.
  expect(await retainedElement!.evaluate((media: HTMLAudioElement) => !media.isConnected && media.paused)).toBe(true)
  await retainedElement!.dispose()
  await logout(page)
  expect((await page.request.get(`/api/notes/${noteId}/attachments/${file.id}/content`)).status()).toBe(401)
  await expect(page.locator('audio,video')).toHaveCount(0)
  await observation(info, 'media-cleanup', { nativeClockAdvanced: true, detachedPlayerPaused: true,
    logoutByteAccessStatus: 401, physicalDevice: false })
})
