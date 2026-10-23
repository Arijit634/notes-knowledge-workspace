import { expect, test, type Page } from '@playwright/test'
import { publicBackend, f } from './public-experience-fixture'
import { openTools, sourceEditor } from './editor-support'

async function ownerPreview(page: Page, republish = false, beforePreview?: () => Promise<void>) {
  await page.goto(`/publications/${f.pubId}`)
  await page.getByRole('button', { name: republish ? 'Republish' : 'Update public copy', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  if (beforePreview) { await page.getByRole('dialog').getByRole('checkbox', { name: /Synthetic landscape/ }).check(); await beforePreview() }
  await page.getByRole('dialog').getByRole('button', { name: 'Preview public copy' }).click()
  await expect(page.getByRole('region', { name: 'Authoritative publication preview' })).toBeVisible()
}
async function noOverflow(page: Page) { expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true) }

test('saved Note → selected media → authoritative preview → publish → public read', async ({ page }) => {
  const s = await publicBackend(page, true); s.exists = false
  await page.goto(`/notes/${f.noteId}`); await openTools(page)
  const source = await sourceEditor(page); await source.fill('A deliberately saved new public thought.')
  await expect(page.getByRole('button', { name: 'Publish', exact: true })).toBeDisabled()
  expect(s.calls.some(c => c.path.endsWith('/publication-preview'))).toBe(false)
  await page.getByRole('button', { name: 'Save note', exact: true }).click(); await expect(page.getByRole('status', { name: 'Save status' })).toHaveText('Saved')
  await page.getByRole('button', { name: 'Publish', exact: true }).click()
  const dialog = page.getByRole('dialog'); await dialog.getByRole('checkbox', { name: /Synthetic landscape/ }).check()
  await dialog.getByRole('button', { name: 'Preview public copy' }).click(); await expect(dialog).toContainText('A deliberately saved new public thought.')
  expect(s.calls.filter(c => c.path.endsWith('/publication-preview')).at(-1)?.ifMatch).toBe('"n2"')
  expect(s.calls.filter(c => c.path.endsWith('/publication-preview')).at(-1)?.body).toEqual({ selectedAttachmentIds: [f.mediaId] })
  await dialog.getByRole('button', { name: 'Confirm publication' }).click(); await expect(dialog).toHaveCount(0)
  await page.getByRole('link', { name: 'View published copy' }).click(); await expect(page.getByRole('heading', { name: f.owner.title })).toBeVisible()
  await expect(page.getByText('A deliberately saved new public thought.', { exact: true })).toBeVisible(); await expect(page.locator('.public-media img')).toHaveAttribute('src', f.media.contentUrl)
  expect(s.calls.filter(c => c.path.endsWith('/publication'))).toHaveLength(1)
})
test('private Save leaves snapshot unchanged; fresh preview explicitly updates it', async ({ page }) => {
  const s = await publicBackend(page, true)
  await page.goto(`/notes/${f.noteId}`); const source = await sourceEditor(page); await source.fill('A private revision, not public yet.')
  await page.getByRole('button', { name: 'Save note', exact: true }).click(); await expect(page.getByRole('status', { name: 'Save status' })).toHaveText('Saved')
  expect(s.publication.markdown).toBe(f.owner.markdown)
  await page.getByRole('link', { name: 'Publications', exact: true }).click(); await page.getByRole('link', { name: 'Inspect & manage' }).click()
  await expect(page.getByText('Private changes are waiting. Readers still see the existing public copy.')).toBeVisible()
  await ownerPreview(page); await page.getByRole('button', { name: 'Confirm public update' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0); expect(s.publication.markdown).toBe('A private revision, not public yet.')
  expect(s.calls.filter(c => c.method === 'PUT' && c.path === `/api/me/publications/${f.pubId}`).at(-1)?.ifMatch).toBe('"p1"')
})
test('unpublish denies public article/media and eligible republish retains stable identity', async ({ page }) => {
  const s = await publicBackend(page, true); await page.goto(`/publications/${f.pubId}`)
  await page.getByRole('button', { name: 'Unpublish', exact: true }).click(); await page.getByRole('dialog').getByRole('button', { name: 'Confirm unpublish' }).click(); await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(s.active).toBe(false); const previousMediaReads = s.calls.filter(c => c.path.includes('/media/')).length
  await page.getByRole('link', { name: 'Explore', exact: true }).click(); await expect(page.getByText('Active published notes will appear here.')).toBeVisible()
  await page.goto(`/publication/${f.pubId}`); await expect(page.getByRole('heading', { name: 'Not available' })).toBeVisible(); expect(s.calls.filter(c => c.path.includes('/media/')).length).toBe(previousMediaReads)
  await ownerPreview(page, true); await page.getByRole('button', { name: 'Confirm republish' }).click(); await expect(page.getByRole('dialog')).toHaveCount(0); expect(s.active).toBe(true); expect(s.publication.id).toBe(f.pubId)
})
test('unusable source blocks update and republish instead of treating missing source as safe', async ({ page }) => {
  const s = await publicBackend(page, true); s.sourceUsable = false
  await page.goto(`/publications/${f.pubId}`); await expect(page.getByRole('button', { name: 'Update public copy', exact: true })).toBeDisabled()
  await expect(page.getByText('The source is not currently usable for publishing.')).toBeVisible()
})
test('anonymous Latest and Trending are backend ordered, cursor paginated without per-card reads', async ({ page }) => {
  const s = await publicBackend(page); s.pages = true
  await page.goto('/explore'); await expect(page.locator('.public-card')).toHaveCount(1)
  await page.getByRole('button', { name: 'More public notes' }).click(); await expect(page.locator('.public-card')).toHaveCount(2)
  await page.getByRole('link', { name: 'Trending', exact: true }).click(); await expect(page.getByRole('link', { name: 'Trending' })).toHaveAttribute('aria-current', 'page')
  await expect.poll(() => s.calls.some(c => c.query.includes('sort=trending'))).toBe(true)
  expect(s.calls.some(c => /\/api\/public\/publications\//.test(c.path))).toBe(false); expect(s.calls.some(c => c.path.startsWith('/api/notes'))).toBe(false)
})
test('public keyword/tag search survives browser back and only uses public contract', async ({ page }) => {
  const s = await publicBackend(page); await page.goto('/explore/search')
  await page.getByLabel('Keywords').fill('a thought'); await page.getByLabel('Tag', { exact: true }).fill('Ideas'); await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page).toHaveURL(/q=a\+thought&tag=Ideas/); await page.getByRole('heading', { name: f.owner.title }).getByRole('link').click(); await expect(page.locator('.public-reader')).toBeVisible()
  await page.goBack(); await expect(page.getByLabel('Keywords')).toHaveValue('a thought'); await expect(page.getByLabel('Tag', { exact: true })).toHaveValue('Ideas')
  expect(s.calls.some(c => c.path === '/api/notes/search')).toBe(false)
})
test('public author navigation uses allowlisted profile and active publications only', async ({ page }) => {
  const s = await publicBackend(page); await page.goto(`/profile/${f.author.handle}`)
  await expect(page.getByRole('heading', { name: f.author.displayName })).toBeVisible(); await page.getByRole('heading', { name: f.owner.title }).getByRole('link').click(); await expect(page.locator('.public-reader')).toBeVisible()
  expect(s.calls.some(c => c.path.startsWith('/api/me/'))).toBe(false)
})
test('public media uses strict snapshot-owned URLs and native presentation', async ({ page }) => {
  const s = await publicBackend(page)
  s.publicMedia = ['image', 'audio', 'video', 'pdf'].map((kind, i) => ({ ...f.media, id: f.mediaId.slice(0, -1) + (i + 3), mediaKind: kind, mediaType: ['image/png', 'audio/wav', 'video/mp4', 'application/pdf'][i], displayName: `Synthetic ${kind}`, contentUrl: `/api/public/publications/${f.pubId}/media/${f.mediaId.slice(0, -1) + (i + 3)}/content` })) as typeof s.publicMedia
  // Deterministically inspect the native player before releasing an unsupported
  // synthetic container. This is presenter/failure evidence, not codec qualification.
  let releaseVideo!: () => void
  const pendingVideo = new Promise<void>(resolve => { releaseVideo = resolve })
  await page.route(`**${s.publicMedia[2].contentUrl}`, async route => { await pendingVideo; await route.fulfill({ status: 200, contentType: 'video/mp4', body: 'synthetic-unsupported-container' }) })
  await page.goto(`/publication/${f.pubId}`, { waitUntil: 'domcontentloaded' }); await expect(page.locator('.public-media')).toHaveCount(4)
  await expect(page.locator('audio')).toHaveAttribute('preload', 'none'); await expect(page.locator('video')).toHaveAttribute('controls', '')
  await expect(page.getByRole('link', { name: 'Open Synthetic pdf' })).toHaveAttribute('rel', 'noopener noreferrer')
  releaseVideo(); await expect(page.getByText('This media is unavailable or this browser cannot decode it. It may have been removed.')).toBeVisible()
  expect(s.calls.some(c => c.path.startsWith('/api/notes/'))).toBe(false)
})
test('anonymous Like offers safe password sign-in continuation to the public article', async ({ page }) => {
  const s = await publicBackend(page); await page.goto(`/publication/${f.pubId}`)
  await page.getByRole('button', { name: 'Like', exact: true }).click(); await page.getByLabel('Email', { exact: true }).fill('synthetic@example.test'); await page.getByLabel('Password', { exact: true }).fill('fictional-password-only')
  await page.getByRole('button', { name: 'Log in', exact: true }).click(); await expect(page).toHaveURL(`/publication/${f.pubId}`)
  expect(s.calls.some(c => c.path.endsWith('/like'))).toBe(false)
})
test('acknowledged Like and Unlike reconcile current viewer counts without optimistic success', async ({ page }) => {
  const s = await publicBackend(page, true); await page.goto(`/publication/${f.pubId}`)
  await page.getByRole('button', { name: 'Like', exact: true }).click(); await expect(page.getByRole('button', { name: 'Unlike', exact: true })).toHaveAttribute('aria-pressed', 'true'); expect(s.liked).toBe(true)
  await page.getByRole('button', { name: 'Unlike', exact: true }).click(); await expect(page.getByRole('button', { name: 'Like', exact: true })).toHaveAttribute('aria-pressed', 'false'); expect(s.liked).toBe(false)
})
test('a pending old-viewer Like cannot repopulate viewer-specific state after logout', async ({ page }) => {
  const s = await publicBackend(page, true); s.holdLike = true
  await page.goto(`/publication/${f.pubId}`); await page.getByRole('button', { name: 'Like', exact: true }).click(); await expect.poll(() => s.likeStarted).toBe(true)
  await page.getByRole('link', { name: 'Workspace', exact: true }).click(); await page.getByRole('button', { name: 'Log out', exact: true }).click(); await page.getByRole('dialog').getByRole('button', { name: 'Log out', exact: true }).click(); await expect(page).toHaveURL('/login')
  s.releaseLike(); await page.goto(`/publication/${f.pubId}`); await expect(page.getByRole('button', { name: 'Like', exact: true })).toHaveAttribute('aria-pressed', 'false')
  expect(s.authenticated).toBe(false)
})
test('report receipt never opens privileged Location and form has keyboard focus containment', async ({ page }) => {
  const s = await publicBackend(page, true); await page.goto(`/publication/${f.pubId}`)
  const opener = page.getByRole('button', { name: 'Report', exact: true }); await opener.click()
  const dialog = page.getByRole('dialog'); await expect(dialog.getByLabel('Reason')).toBeFocused(); await dialog.getByRole('button', { name: 'Close', exact: true }).focus(); await page.keyboard.press('Tab'); await expect(dialog.getByLabel('Reason')).toBeFocused()
  await page.keyboard.press('Escape'); await expect(dialog).toHaveCount(0); await expect(opener).toBeFocused(); await opener.click()
  await dialog.getByLabel('Reason').selectOption('spam'); await dialog.getByLabel('Description').fill('Synthetic public spam report.'); await dialog.getByRole('button', { name: 'Submit report' }).click()
  await expect(page.getByText(/Report received/)).toBeVisible(); expect(s.calls.some(c => c.path.startsWith('/api/moderation/'))).toBe(false)
})
test('report rate limit is truthful and does not fabricate a receipt', async ({ page }) => {
  const s = await publicBackend(page, true); s.reportError = 429
  await page.goto(`/publication/${f.pubId}`); await page.getByRole('button', { name: 'Report', exact: true }).click(); await page.getByLabel('Description').fill('Synthetic report.'); await page.getByRole('button', { name: 'Submit report' }).click()
  await expect(page.getByRole('alert')).toContainText('Too many requests'); await expect(page.getByText(/Report received/)).toHaveCount(0); await expect(page.getByRole('button', { name: 'Submit report' })).toBeDisabled(); await expect(page.getByText(/Try again in/)).toBeVisible()
})
test('ordinary user cannot review reports, and typed recent-auth remains distinct', async ({ page }) => {
  const s = await publicBackend(page, true); await page.goto('/moderation/reports'); await expect(page.getByRole('heading', { name: 'Moderation permission unavailable' })).toBeVisible()
  s.review = true; s.recent = false; await page.reload(); await expect(page.getByRole('heading', { name: 'Confirm your identity' })).toBeVisible()
  expect(s.calls.some(c => c.path.startsWith('/api/notes/'))).toBe(false)
})
for (const consequence of ['none', 'removePublication', 'removePublicationAndSuspendResponsibleAccount']) {
  test(`authorized Open → UnderReview → ${consequence} acknowledges only committed consequences`, async ({ page }) => {
    const s = await publicBackend(page, true); s.review = s.enforce = true
    await page.goto('/moderation/reports'); await page.getByRole('link', { name: 'Review public report →' }).click(); await page.getByRole('button', { name: 'Begin review' }).click()
    await page.getByLabel('Consequence', { exact: true }).selectOption(consequence); await page.getByRole('button', { name: 'Review decision' }).click(); await page.getByRole('dialog').getByRole('button', { name: 'Confirm decision' }).click()
    await expect(page.getByText('The server confirmed the decision and its required logical consequences.')).toBeVisible()
    expect(s.report.status).toBe(consequence === 'none' ? 'dismissed' : 'actioned'); expect(s.active).toBe(consequence === 'none')
    expect(s.calls.some(c => c.path.startsWith('/api/notes/'))).toBe(false)
    await expect(page.getByRole('button', { name: 'Begin review' })).toHaveCount(0)
  })
}
test('reduced motion, long content and rapid modal hand-offs preserve focus and fit', async ({ page }) => {
  const s = await publicBackend(page, true); s.publication.title = 'A deliberately long title '.repeat(12); s.publication.markdown = 'A long readable paragraph. '.repeat(100)
  await page.emulateMedia({ reducedMotion: 'reduce' }); await page.setViewportSize({ width: 320, height: 844 })
  await page.goto(`/publication/${f.pubId}`); await expect(page.locator('.public-reader h1')).toBeVisible(); await noOverflow(page)
  for (let i = 0; i < 4; i++) { await page.getByRole('button', { name: 'Report', exact: true }).click(); await expect(page.getByLabel('Reason')).toBeFocused(); await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0); await expect(page.getByRole('button', { name: 'Report', exact: true })).toBeFocused() }
  await ownerPreview(page); await expect(page.getByRole('region', { name: 'Authoritative publication preview' })).toBeFocused(); await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(await page.locator('.owner-publication-detail').evaluate(el => getComputedStyle(el).animationName)).toBe('none')
})
test('public not-found, permission, conflict and rate-limit state plates', async ({ page }, testInfo) => {
  test.setTimeout(90_000)
  const s = await publicBackend(page, true)
  for (const width of [1440, 320]) {
    await page.setViewportSize({ width, height: 1000 })
    s.active = false; await page.goto(`/publication/${f.pubId}`); await expect(page.getByRole('heading', { name: 'Not available' })).toBeVisible(); await page.screenshot({ path: testInfo.outputPath(`not-found-${width}.png`), fullPage: (await page.getByRole('dialog').count()) === 0, animations: 'disabled' })
    await page.goto('/moderation/reports'); await expect(page.getByRole('heading', { name: 'Moderation permission unavailable' })).toBeVisible(); await page.screenshot({ path: testInfo.outputPath(`permission-403-${width}.png`), fullPage: (await page.getByRole('dialog').count()) === 0, animations: 'disabled' })
    s.active = true
    for (const code of [409, 412]) { s.commitError = 0; await ownerPreview(page); s.commitError = code; await page.getByRole('button', { name: 'Confirm public update' }).click(); await expect(page.getByRole('alert')).toBeVisible(); await noOverflow(page); await page.screenshot({ path: testInfo.outputPath(`conflict-${code}-${width}.png`), fullPage: (await page.getByRole('dialog').count()) === 0, animations: 'disabled' }); await page.getByRole('button', { name: 'Close', exact: true }).click() }
    s.reportError = 429; await page.goto(`/publication/${f.pubId}`); await page.getByRole('button', { name: 'Report', exact: true }).click(); await page.getByLabel('Description').fill('Synthetic rate-limit state.'); await page.getByRole('button', { name: 'Submit report' }).click(); await expect(page.getByRole('alert')).toContainText('Too many requests'); await page.screenshot({ path: testInfo.outputPath(`rate-limit-429-${width}.png`), fullPage: (await page.getByRole('dialog').count()) === 0, animations: 'disabled' }); await page.keyboard.press('Escape')
  }
})
test('mandatory enforcement failure never renders terminal success', async ({ page }) => {
  const s = await publicBackend(page, true); s.review = s.enforce = true; s.report.status = 'under_review'; s.terminalError = 503
  await page.goto(`/moderation/reports/${f.reportId}`); await page.getByLabel('Consequence', { exact: true }).selectOption('removePublication'); await page.getByRole('button', { name: 'Review decision' }).click(); await page.getByRole('button', { name: 'Confirm decision' }).click()
  await expect(page.getByRole('alert')).toContainText('No successful enforcement'); expect(s.active).toBe(true); expect(s.report.status).toBe('under_review')
})
test('capability loss during review clears restricted evidence without logging out ordinary session', async ({ page }) => {
  const s = await publicBackend(page, true); s.review = s.enforce = true; s.report.status = 'under_review'
  await page.goto(`/moderation/reports/${f.reportId}`); await page.getByRole('button', { name: 'Review decision' }).click(); s.enforce = false; await page.getByRole('button', { name: 'Confirm decision' }).click()
  await expect(page.getByRole('heading', { name: 'Moderation permission unavailable' })).toBeVisible(); await expect(page.getByText(f.report.description)).toHaveCount(0); expect(s.authenticated).toBe(true)
})
test('known moderation removal evicts reader and all public list families on same SPA', async ({ page }) => {
  const s = await publicBackend(page, true); s.review = s.enforce = true; s.report.status = 'under_review'
  await page.goto('/explore'); await page.getByRole('heading', { name: f.owner.title }).getByRole('link').click(); await page.getByRole('link', { name: /The Synthetic Writer/ }).click(); await expect(page.getByRole('heading', { name: f.author.displayName })).toBeVisible()
  // Follow a first-party route without replacing the SPA/query client.
  await page.getByRole('link', { name: 'Workspace', exact: true }).click()
  await page.evaluate((id) => { history.pushState({}, '', `/moderation/reports/${id}`); dispatchEvent(new PopStateEvent('popstate')) }, f.reportId)
  await page.getByLabel('Consequence', { exact: true }).selectOption('removePublication'); await page.getByRole('button', { name: 'Review decision' }).click(); await page.getByRole('button', { name: 'Confirm decision' }).click(); await expect(page.getByText('The server confirmed the decision and its required logical consequences.')).toBeVisible()
  await page.getByRole('link', { name: 'Explore', exact: true }).click(); await expect(page.locator('.public-card')).toHaveCount(0)
})
test('session expiry during publish is not success and disposes owner workflow', async ({ page }) => {
  const s = await publicBackend(page, true); await ownerPreview(page); s.commitError = 401
  await page.getByRole('button', { name: 'Confirm public update' }).click(); await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible(); await expect(page.getByRole('dialog')).toHaveCount(0)
})
for (const status of [409, 412]) {
  test(`publication ${status} invalidates the preview and requires explicit refresh`, async ({ page }) => {
    const s = await publicBackend(page, true); await ownerPreview(page); s.commitError = status
    await page.getByRole('button', { name: 'Confirm public update' }).click(); await expect(page.getByRole('alert')).toBeVisible(); await expect(page.getByRole('button', { name: 'Confirm public update' })).toHaveCount(0)
    expect(s.calls.filter(c => c.method === 'PUT' && c.path === `/api/me/publications/${f.pubId}`)).toHaveLength(1)
    if (status === 412) {
      s.commitError = 0; s.publicationRevision++
      await page.getByRole('button', { name: 'Refresh saved state' }).click()
      await expect(page.getByText(/Saved state refreshed/)).toBeVisible(); await page.getByRole('button', { name: 'Preview public copy' }).click()
      await page.getByRole('button', { name: 'Confirm public update' }).click(); await expect(page.getByRole('dialog')).toHaveCount(0)
      expect(s.calls.filter(c => c.method === 'PUT' && c.path === `/api/me/publications/${f.pubId}`).at(-1)?.ifMatch).toBe('"p2"')
    }
  })
}
test('source changed between preview and commit cannot publish the stale checkpoint', async ({ page }) => {
  const s = await publicBackend(page, true); await ownerPreview(page); s.noteRevision++
  await page.getByRole('button', { name: 'Confirm public update' }).click(); await expect(page.getByRole('alert')).toBeVisible(); expect(s.publicationRevision).toBe(1)
})
test('public activation is explicit and private edits remain private until refresh', async ({ page }) => {
  const s = await publicBackend(page, true); s.profileActive = false
  await page.goto('/settings/public-profile'); await page.getByLabel('Display name', { exact: true }).fill('A new public pen name'); await page.getByRole('button', { name: 'Save profile fields' }).click(); await expect(page.getByText(/Profile saved privately/)).toBeVisible()
  expect(s.author.displayName).toBe(f.author.displayName); await page.getByRole('button', { name: 'Activate public profile' }).click(); await page.getByRole('button', { name: 'Confirm public profile' }).click(); await expect(page.getByText('Public profile is active and refreshed. No Note was published.')).toBeVisible()
  expect(s.author.displayName).toBe('A new public pen name')
})
test('unsupported or revoked media fails visibly without a private fallback', async ({ page }) => {
  const s = await publicBackend(page); await page.route('**/api/public/publications/*/media/*/content', r => r.fulfill({ status: 404 }))
  await page.goto(`/publication/${f.pubId}`); await expect(page.getByText('This media is unavailable or this browser cannot decode it. It may have been removed.')).toBeVisible()
  expect(s.calls.some(c => c.path.startsWith('/api/notes/'))).toBe(false)
})
for (const width of [1440, 1024, 834, 390, 320]) {
  test(`public experience and protected tools remain usable at ${width}px`, async ({ page }, testInfo) => {
    test.setTimeout(90_000)
    const s = await publicBackend(page, true); s.review = s.enforce = true
    await page.setViewportSize({ width, height: 1000 })
    for (const [name, path, heading] of [
      ['publications', '/publications', 'Publications'], ['publication-detail', `/publications/${f.pubId}`, f.owner.title],
      ['public-profile-settings', '/settings/public-profile', 'Public author profile'], ['author', `/profile/${f.author.handle}`, f.author.displayName],
      ['latest', '/explore', 'A little perspective.'], ['trending', '/explore?sort=trending', 'A little perspective.'], ['search', '/explore/search?q=idea', 'Find your next idea'],
      ['reader', `/publication/${f.pubId}`, f.owner.title], ['moderation-queue', '/moderation/reports', 'Report review'], ['moderation-detail', `/moderation/reports/${f.reportId}`, 'Public report review'],
    ]) { await page.goto(path); await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible(); await noOverflow(page); await page.screenshot({ path: testInfo.outputPath(`${name}-${width}.png`), fullPage: (await page.getByRole('dialog').count()) === 0, animations: 'disabled' }) }
    await ownerPreview(page, false, async () => { await noOverflow(page); await page.screenshot({ path: testInfo.outputPath(`media-selection-${width}.png`), fullPage: (await page.getByRole('dialog').count()) === 0, animations: 'disabled' }) }); await noOverflow(page); await page.screenshot({ path: testInfo.outputPath(`preview-${width}.png`), fullPage: (await page.getByRole('dialog').count()) === 0, animations: 'disabled' }); await page.getByRole('button', { name: 'Close', exact: true }).click()
    await page.goto(`/publication/${f.pubId}`); await page.getByRole('button', { name: 'Like', exact: true }).click(); await expect(page.getByRole('button', { name: 'Unlike', exact: true })).toBeVisible(); await page.screenshot({ path: testInfo.outputPath(`liked-reader-${width}.png`), fullPage: (await page.getByRole('dialog').count()) === 0, animations: 'disabled' }); await page.getByRole('button', { name: 'Report', exact: true }).click(); await noOverflow(page); await page.screenshot({ path: testInfo.outputPath(`report-dialog-${width}.png`), fullPage: (await page.getByRole('dialog').count()) === 0, animations: 'disabled' }); await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0)
    s.report.status = 'under_review'; await page.goto(`/moderation/reports/${f.reportId}`); await page.getByLabel('Consequence', { exact: true }).selectOption('removePublication'); await page.getByRole('button', { name: 'Review decision' }).click(); await noOverflow(page); await page.screenshot({ path: testInfo.outputPath(`enforcement-confirmation-${width}.png`), fullPage: (await page.getByRole('dialog').count()) === 0, animations: 'disabled' }); await page.keyboard.press('Escape')
    await page.goto(`/notes/${f.noteId}`); await openTools(page); await noOverflow(page); await page.screenshot({ path: testInfo.outputPath(`note-publishing-controls-${width}.png`), fullPage: (await page.getByRole('dialog').count()) === 0, animations: 'disabled' })
  })
}
