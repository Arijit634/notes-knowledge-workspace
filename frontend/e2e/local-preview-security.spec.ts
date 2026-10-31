import { test, expect } from '@playwright/test'
import { flagshipBackend } from './flagship-fixture'

test('staged image previews use generated raster bytes and treat hostile filenames as text', async ({ page }) => {
  const state = await flagshipBackend(page)
  await page.addInitScript(() => {
    const original = URL.createObjectURL
    URL.createObjectURL = blob => {
      ;(window as unknown as Record<string, unknown>).previewOriginalFileUrl = blob instanceof File
      return original(blob)
    }
    ;(window as unknown as Record<string, unknown>).previewAttackRan = false
  })
  await page.goto('/notes/new')
  const name = '<img src=x onerror="window.previewAttackRan=true">.png'
  const bytes = await page.evaluate(() => {
    const canvas = document.createElement('canvas'); canvas.width = 4; canvas.height = 4
    return atob(canvas.toDataURL('image/png').split(',')[1]).split('').map(c => c.charCodeAt(0))
  })
  await page.getByLabel('Choose files for new note').setInputFiles({ name, mimeType: 'image/png', buffer: Buffer.from(bytes) })
  const staged = page.getByRole('list', { name: 'Files ready to upload' })
  await expect(staged).toContainText(name)
  await expect.poll(() => staged.locator('img').evaluateAll(images => images.some(i => i instanceof HTMLImageElement && i.complete && i.naturalWidth > 0))).toBe(true)
  expect(await page.evaluate(() => (window as unknown as Record<string, unknown>).previewOriginalFileUrl)).toBe(false)
  expect(await page.evaluate(() => (window as unknown as Record<string, unknown>).previewAttackRan)).toBe(false)
  expect(state.calls.filter(c => c.method === 'POST' && c.path === '/api/notes')).toHaveLength(0)
})

test('HTML and SVG disguised as PNG never become staged image URLs', async ({ page }) => {
  await flagshipBackend(page)
  await page.addInitScript(() => { (window as unknown as Record<string, unknown>).previewAttackRan = false })
  await page.goto('/notes/new')
  for (const [index, payload] of ['<svg xmlns="http://www.w3.org/2000/svg" onload="window.previewAttackRan=true"/>', '<html><script>window.previewAttackRan=true</script></html>'].entries()) {
    await page.getByLabel('Choose files for new note').setInputFiles({ name: `hostile-${index}.png`, mimeType: 'image/png', buffer: Buffer.from(payload) })
  }
  const staged = page.getByRole('list', { name: 'Files ready to upload' })
  await expect(staged.locator('li')).toHaveCount(2); await expect(staged.locator('img')).toHaveCount(0)
  expect(await page.evaluate(() => (window as unknown as Record<string, unknown>).previewAttackRan)).toBe(false)
})
