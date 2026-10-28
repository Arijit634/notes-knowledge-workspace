import { test, expect } from '@playwright/test'
import { flagshipBackend, noteId, timestamp } from './flagship-fixture'

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jN1sAAAAASUVORK5CYII=', 'base64')
for (const width of [1440, 390]) test(`file drops stage before Create and preserve saved-Note drafts at ${width}px`, async ({ page }, info) => {
  await page.setViewportSize({ width, height: 900 }); const state = await flagshipBackend(page)
  let uploads = 0
  const files: object[] = []
  await page.route(`**/api/notes/${noteId}/attachments`, async route => {
    if (route.request().method() === 'GET') { await route.fulfill({ json: { items: files, nextCursor: null } }); return }
    expect(route.request().headers()['x-csrf-token']).toBe('synthetic-proof'); uploads++
    const id = `01990a55-9e12-7ac4-8f5b-31aa4a91d40${uploads + 1}`
    const file = { id, noteId, mediaKind: 'image', mediaType: 'image/png', displayFilename: uploads === 1 ? 'first.png' : 'second.png', sizeBytes: png.length, width: 1, height: 1, durationSeconds: null, pageCount: null, storageState: 'stored', validationState: 'accepted', cleanupState: 'retained', createdAt: timestamp, updatedAt: timestamp }
    files.push(file); await route.fulfill({ status: 201, json: file, headers: { ETag: '"a1"', Location: `/api/notes/${noteId}/attachments/${id}` } })
  })
  await page.route('**/api/notes/*/attachments/*/content', route => route.fulfill({ body: png, contentType: 'image/png' }))
  async function drop(label: string, name: string) {
    const transfer = await page.evaluateHandle(({ name, bytes }) => { const data = new DataTransfer(); data.items.add(new File([new Uint8Array(bytes)], name, { type: 'image/png' })); return data }, { name, bytes: [...png] })
    const area = page.getByRole('region', { name: label, exact: true }); await area.dispatchEvent('dragenter', { dataTransfer: transfer }); await expect(page.getByText('Drop files to add them', { exact: true })).toBeVisible()
    await area.dispatchEvent('drop', { dataTransfer: transfer }); await transfer.dispose()
  }
  await page.goto('/notes/new'); await expect(page.getByRole('dialog', { name: 'New note', exact: true })).toBeVisible()
  await drop('New note file drop area', 'first.png'); await expect(page.getByRole('list', { name: 'Files ready to upload' })).toContainText('first.png')
  expect(uploads).toBe(0); expect(state.calls.filter(c => c.method === 'POST' && c.path === '/api/notes')).toHaveLength(0)
  await page.getByRole('button', { name: 'Cancel', exact: true }).click(); await page.getByRole('button', { name: 'Keep writing', exact: true }).click(); await expect(page.getByRole('list', { name: 'Files ready to upload' })).toContainText('first.png')
  await page.getByRole('button', { name: 'Create note', exact: true }).click(); await expect(page).toHaveURL('/notes/' + noteId); await expect(page.locator('.notes-attachment-list strong').filter({ hasText: /^first\.png$/ })).toBeVisible(); expect(uploads).toBe(1)
  await page.getByLabel('Title', { exact: true }).fill('Unsaved writing stays here')
  await drop('Note file drop area', 'second.png'); await expect(page.getByText('File added.', { exact: true })).toBeVisible(); expect(uploads).toBe(2)
  await expect(page.getByLabel('Title', { exact: true })).toHaveValue('Unsaved writing stays here'); expect(state.calls.filter(c => c.method === 'PUT' && c.path === '/api/notes/' + noteId)).toHaveLength(0)
  await expect(page.getByLabel('Save status')).toContainText('Unsaved changes'); await expect(page.getByText('Drop files to add them', { exact: true })).not.toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: info.outputPath(`dropped-files-${width}.png`), fullPage: true })
})
