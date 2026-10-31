import { expect, test } from '@playwright/test'
import { publicBackend, f } from './public-experience-fixture'
import { writeFile } from 'node:fs/promises'

async function workspaceReadOnlyFixtures(page: import('@playwright/test').Page) {
  await page.route('**/api/knowledge/history', route => route.fulfill({ json: { items: [], nextCursor: null } }))
  await page.route('**/api/me/security/sessions', route => route.fulfill({ json: { sessions: [] } }))
  await page.route('**/api/ai/processing-policy', route => route.fulfill({ json: {
    processingPolicyId: f.noteId, policyCode: 'synthetic', policyVersion: 1, policyFingerprint: 'a'.repeat(64),
    disclosureRevision: 'synthetic-v1', effectiveAt: f.timestamp, acknowledged: false, acknowledgementRequired: true,
    disclosureContent: 'SYNTHETIC DISCLOSURE FIXTURE ONLY. No network processing.',
    providerProcessingCategories: ['groundedGeneration', 'mediaUnderstanding', 'queryEmbedding', 'sourceEmbedding', 'structuredExtraction'],
  } }))
}


for (const width of [1440, 834, 390, 320]) test(`whole app light/dark, navigation and publication controls at ${width}px`, async ({ page }, info) => {
  test.setTimeout(90000)
  await page.setViewportSize({ width, height: 960 })
  await page.emulateMedia({ reducedMotion: width === 320 ? 'reduce' : 'no-preference', colorScheme: 'light' })
  const state = await publicBackend(page, true)
  await workspaceReadOnlyFixtures(page)
  state.publication.title = 'A long title about a weekend trip and everything we want to remember before leaving home'
  state.publication.markdown = 'A saved public thought. '.repeat(45)
  await page.goto('/notes')
  const nav = page.getByRole('navigation', { name: 'Workspace navigation' })
  await expect(nav).toBeVisible()
  if (width <= 600) {
    expect(await page.locator('.notes-sidebar').evaluate(e => getComputedStyle(e).flexDirection)).toBe('row')
    expect((await page.locator('.notes-sidebar').boundingBox())!.height).toBeLessThan(85)
  }
  await page.evaluate(() => { (window as any).__initialNav = document.querySelector('nav[aria-label="Workspace navigation"]') })
  const firstBox = await nav.getByRole('link', { name: 'Publications', exact: true }).boundingBox()
  // An early-chunk transition must keep the same navigation DOM node visible.
  await page.getByRole('link', { name: 'Explore', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Explore', exact: true })).toBeVisible()
  expect(await page.evaluate(() => (window as any).__initialNav === document.querySelector('nav[aria-label="Workspace navigation"]'))).toBe(true)
  await nav.getByRole('link', { name: 'Publications', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Publications', exact: true })).toBeVisible()
  const secondBox = await nav.getByRole('link', { name: 'Publications', exact: true }).boundingBox()
  expect(secondBox!.x).toBeCloseTo(firstBox!.x, 1)
  await expect(page.getByText('Public safety tools')).toHaveCount(0)
  await page.getByRole('link', { name: 'Inspect & manage' }).click()
  await expect(page.getByRole('heading', { name: state.publication.title, exact: true })).toBeVisible()
  for (const theme of ['light', 'dark']) {
    await page.getByRole('combobox', { name: 'Appearance' }).selectOption(theme)
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    for (const name of ['Refresh source status', 'Update public copy', 'Unpublish']) await expect(page.getByRole('button', { name, exact: true })).toBeEnabled()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    if (width === 390 && theme === 'dark') {
      const colors = await page.locator('.public-sync button').evaluateAll(buttons => buttons.map(e => { const s = getComputedStyle(e); return { disabled: (e as HTMLButtonElement).disabled, color: s.color, background: s.backgroundColor, opacity: s.opacity } }))
      await writeFile(info.outputPath('publication-control-colors.json'), JSON.stringify(colors, null, 2))
      const luminance = (value: string) => {
        const rgb = value.match(/[\d.]+/g)!.slice(0, 3).map(Number).map(n => n / 255).map(n => n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4)
        return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722
      }
      for (const row of colors) {
        const foreground = luminance(row.color), background = luminance(row.background)
        expect((Math.max(foreground, background) + .05) / (Math.min(foreground, background) + .05)).toBeGreaterThanOrEqual(4.5)
      }
    }
    await page.screenshot({ path: info.outputPath(`publication-${theme}-${width}.png`), fullPage: true })
  }
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  if (width === 390) {
    state.publication.title = f.owner.title; state.publication.markdown = f.owner.markdown
    await page.reload(); await expect(page.getByRole('heading', { name: f.owner.title, exact: true })).toBeVisible()
    await page.screenshot({ path: info.outputPath('publication-after-dark.png'), fullPage: true })
    await page.getByRole('combobox', { name: 'Appearance' }).selectOption('light')
    await page.screenshot({ path: info.outputPath('publication-after-light.png'), fullPage: true })
  }
  await page.getByRole('button', { name: 'Unpublish', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  expect(await nav.evaluate(e => e.closest('[inert]') !== null)).toBe(true)
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Unpublish', exact: true })).toBeFocused()
  // Read-only audit across all authenticated screen families, including errors.
  for (const path of ['/ask', '/settings/profile', '/settings/public-profile', '/settings/privacy', '/settings/security', '/settings/security/mfa', '/settings/security/sessions', '/moderation/reports', `/moderation/reports/${f.reportId}`, `/profile/${f.author.handle}`, `/publication/${f.pubId}`, '/explore/search']) {
    await page.goto(path)
    await expect(page.locator('main')).toBeVisible()
    await expect(page.locator('main h1')).toHaveCount(1)
    if (path === '/ask') await expect(page.getByText('No saved searches yet.')).toBeVisible()
    if (path === '/settings/privacy') await expect(page.getByLabel('Current approved processing disclosure')).toContainText('SYNTHETIC DISCLOSURE')
    if (path === '/settings/profile') await expect(page.getByLabel('Display name', { exact: true })).toBeVisible()
    if (path === '/settings/security') await expect(page.getByRole('heading', { name: 'Password', exact: true })).toBeVisible()
    if (path === '/settings/security/mfa') await expect(page.getByText('MFA is disabled.')).toBeVisible()
    if (path.startsWith('/moderation/')) await expect(page.getByRole('heading', { name: 'Moderation permission unavailable' })).toBeVisible()
    await expect(page.getByRole('combobox', { name: 'Appearance' })).toBeVisible()
    await page.getByRole('combobox', { name: 'Appearance' }).selectOption('dark')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    if (width === 390) await page.screenshot({ path: info.outputPath(`audit-${path.replaceAll('/', '_')}.png`), fullPage: true })
  }
  await page.goto('/notes/new')
  await expect(page.getByRole('dialog', { name: 'New note', exact: true })).toBeVisible()
  await page.getByRole('dialog').getByText('Supported files & sizes', { exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('up to 20 MB')
  await expect(page.getByRole('dialog').getByRole('button', { name: 'Add files', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})

for (const width of [1440, 390, 320]) test(`anonymous auth and public screens use the same dark theme at ${width}px`, async ({ page }) => {
  await publicBackend(page)
  await page.setViewportSize({ width, height: 960 })
  await page.emulateMedia({ colorScheme: 'dark' })
  for (const path of ['/login', '/signup', '/verify-email', '/forgot-password', '/reset-password', '/auth/complete', '/reauth', '/explore', '/explore/search', `/profile/${f.author.handle}`, `/publication/${f.pubId}`]) {
    await page.goto(path)
    await expect(page.getByRole('combobox', { name: 'Appearance' })).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  }
})
