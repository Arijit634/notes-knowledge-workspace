import { expect, test } from '@playwright/test'
import { flagshipBackend, answer, noteId, operationId, timestamp, attachmentId } from './flagship-fixture'
import { deflateSync } from 'node:zlib'

// A realistic landscape fixture: a one-pixel image cannot prove preview usability.
function chunk(type: string, body: Buffer) {
  const bytes = Buffer.concat([Buffer.from(type), body]); let crc = 0xffffffff
  for (const byte of bytes) { crc ^= byte; for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0) }
  const size = Buffer.alloc(4), check = Buffer.alloc(4); size.writeUInt32BE(body.length); check.writeUInt32BE((crc ^ 0xffffffff) >>> 0)
  return Buffer.concat([size, bytes, check])
}
const header = Buffer.alloc(13); header.writeUInt32BE(640, 0); header.writeUInt32BE(360, 4); header[8] = 8; header[9] = 6
const pixels = Buffer.alloc((640 * 4 + 1) * 360)
for (let y = 0; y < 360; y++) for (let x = 0; x < 640; x++) { const at = y * 2561 + 1 + x * 4; pixels[at] = 160; pixels[at + 1] = 180; pixels[at + 2] = 220; pixels[at + 3] = 255 }
const png = Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', header), chunk('IDAT', deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))])
for (const width of [1440, 834, 390, 320]) test(`owner review: request continuity, public navigation, search and media composer at ${width}px`, async ({ page }, info) => {
  test.setTimeout(90000)
  await page.setViewportSize({ width, height: width <= 600 ? 844 : 1000 })
  await page.emulateMedia({ reducedMotion: width === 320 ? 'reduce' : 'no-preference' })
  const state = await flagshipBackend(page)
  let completed = false, inspected = 4, submits = 0, polls = 0, uploaded = false
  const attachment = { id: attachmentId, noteId, mediaKind: 'image', mediaType: 'image/png', displayFilename: 'synthetic-media.png', sizeBytes: png.length, width: 640, height: 360, durationSeconds: null, pageCount: null, storageState: 'stored', validationState: 'accepted', cleanupState: 'retained', createdAt: timestamp, updatedAt: timestamp }
  const submittedAt = new Date().toISOString()
  const operation = () => ({ operationId, status: completed ? 'completed' : 'running', submittedAt, result: completed ? answer : null, inspectedSources: inspected })
  await page.route('**/api/knowledge/query', async route => { submits++; await route.fulfill({ status: 202, headers: { Location: `/api/knowledge/operations/${operationId}` }, json: operation() }) })
  await page.route('**/api/knowledge/operations/**', async route => { polls++; await route.fulfill({ json: operation() }) })
  await page.route(/\/api\/public\/explore(?:\?|$)/, async route => route.fulfill({ json: { items: ['A weekend by the coast', 'Learning to make things', 'A recipe I keep coming back to'].map((title, i) => ({ id: `01990a55-9e12-7ac4-8f5b-31aa4a91d40${i + 1}`, title, tags: [['Travel'], ['Projects'], ['Food']][i], publishedAt: timestamp, author: { handle: 'synthetic_writer', displayName: 'Synthetic writer', biography: '', avatarUrl: null }, engagement: { likeCount: i + 2, approximateViewCount: 20 + i } })), nextCursor: null } }))
  await page.route(/\/api\/notes(?:\?|$)/, async route => {
    if (route.request().method() !== 'GET') { await route.fallback(); return }
    await route.fulfill({ json: { items: [{ ...state.note, attachmentSummary: { images: uploaded ? 1 : 0, audio: 0, video: 0, documents: 0 } }], nextCursor: null } })
  })
  await page.route('**/api/notes/search', async route => {
    expect(route.request().method()).toBe('POST'); expect(route.request().headers()['x-csrf-token']).toBe('synthetic-proof')
    expect(route.request().postDataJSON().query).toBe('coastal rental checklist')
    await route.fulfill({ json: { items: [{ id: noteId, title: 'Coastal rental checklist', lifecycle: 'active', pinned: false, tags: [], updatedAt: timestamp, snippet: 'A fictional saved detail.', matchLabels: ['body'] }], nextCursor: null } })
  })
  await page.route(`**/api/notes/${noteId}/attachments`, async route => {
    if (route.request().method() === 'POST') { expect(route.request().headers()['x-csrf-token']).toBe('synthetic-proof'); uploaded = true; await route.fulfill({ status: 201, headers: { ETag: '"a1"', Location: `/api/notes/${noteId}/attachments/${attachmentId}` }, json: attachment }) }
    else await route.fulfill({ json: { items: uploaded ? [attachment] : [], nextCursor: null } })
  })
  await page.route(`**/api/notes/${noteId}/attachments/${attachmentId}/content`, route => route.fulfill({ contentType: 'image/png', body: png }))
  await page.goto('/ask')
  await page.getByLabel('What would you like to find?', { exact: true }).fill('List every fictional saved detail')
  await page.getByRole('button', { name: 'Ask your knowledge', exact: true }).click()
  await expect(page.getByText('4 sources checked', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Explore', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Explore', exact: true })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Workspace navigation' })).toBeVisible()
  await page.getByRole('link', { name: 'Trending', exact: true }).click()
  await expect(page.getByText(/Popular now, based on likes/)).toBeVisible()
  await page.screenshot({ path: info.outputPath(`explore-${width}.png`), fullPage: true })
  await page.getByRole('link', { name: 'AI Search', exact: true }).click()
  await expect(page.getByLabel('What would you like to find?', { exact: true })).toHaveValue('List every fictional saved detail')
  const before = polls; inspected = 7
  await page.getByRole('button', { name: 'Check status', exact: true }).click()
  await expect(page.getByText('7 sources checked', { exact: true })).toBeVisible(); expect(polls).toBeGreaterThan(before)
  completed = true
  await page.getByRole('button', { name: 'Check status', exact: true }).click()
  await expect(page.getByRole('region', { name: 'AI answer', exact: true })).toContainText('A grounded synthetic answer')
  expect(submits).toBe(1)
  expect(await page.locator('.workspace-search').evaluate(e => getComputedStyle(e).flexDirection)).toBe('row')
  expect((await page.locator('.studio-command-bar').boundingBox())!.height).toBeLessThanOrEqual(80)
  const search = page.getByRole('searchbox', { name: 'Search notes', exact: true })
  await search.fill('coastal rental checklist'); await search.press('Enter')
  await expect(page).toHaveURL(/\/notes$/)
  await expect(page.getByRole('heading', { name: 'Search results', exact: true })).toBeVisible()
  expect(JSON.stringify(await page.evaluate(() => history.state))).not.toContain('coastal rental checklist')
  await page.getByRole('button', { name: 'Clear search', exact: true }).click()
  const account = page.locator('.workspace-account > summary'); await account.click()
  await expect(page.locator('.workspace-account-menu').getByRole('link', { name: 'Security settings', exact: true })).toBeVisible()
  if (width > 900) { const box = await account.boundingBox(); expect(box!.x).toBeLessThan(250); expect(box!.y).toBeGreaterThan(700) }
  await account.press('Escape')
  await page.getByRole('link', { name: 'New note', exact: true }).click()
  const composer = page.getByRole('dialog', { name: 'New note', exact: true })
  await expect(composer).toBeVisible()
  await expect(composer.getByRole('button', { name: 'Create note', exact: true })).toBeInViewport()
  await composer.getByLabel('Choose files for new note').setInputFiles({ name: 'synthetic-media.png', mimeType: 'image/png', buffer: png })
  await composer.getByRole('checkbox', { name: 'Use this note with AI', exact: true }).check()
  expect(state.calls.filter(c => c.path === '/api/notes' && c.method === 'POST')).toHaveLength(0)
  await composer.getByRole('button', { name: 'Cancel', exact: true }).click()
  const leave = page.getByRole('dialog', { name: 'Leave with unsaved changes?', exact: true })
  await leave.getByRole('button', { name: 'Keep writing', exact: true }).click()
  await expect(composer.getByRole('checkbox', { name: 'Use this note with AI' })).toBeChecked()
  await expect(composer.getByRole('list', { name: 'Files ready to upload' })).toContainText('synthetic-media.png')
  await page.screenshot({ path: info.outputPath(`new-note-${width}.png`), animations: 'disabled' })
  await composer.getByRole('checkbox', { name: 'Use this note with AI', exact: true }).uncheck()
  await composer.getByRole('button', { name: 'Create note', exact: true }).click()
  await expect(page).toHaveURL(`/notes/${noteId}`)
  await expect(page.getByLabel('Title', { exact: true })).toHaveValue('synthetic-media')
  const preview = page.getByRole('img', { name: 'synthetic-media.png', exact: true })
  await expect(preview).toBeInViewport({ ratio: 0.25 }); expect(uploaded).toBe(true)
  const mediaBox = (await preview.boundingBox())!
  expect(mediaBox.height).toBeGreaterThan(70)
  expect(mediaBox.y + 70).toBeLessThan((width <= 600 ? 844 : 1000) - 80)
  const created = state.calls.find(c => c.path === '/api/notes' && c.method === 'POST')
  expect(created?.body).toMatchObject({ title: 'synthetic-media', markdown: '', aiEnabled: false })
  await page.getByRole('link', { name: 'All notes', exact: true }).click()
  await expect(page.getByLabel('Attached media')).toContainText('1 photo')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})
