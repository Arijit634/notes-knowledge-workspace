import { expect, test } from '@playwright/test'
import { publicBackend, f } from './public-experience-fixture'

test('complete disclosure is escaped, deliberate, and requires a fresh review after policy change', async ({ page }) => {
  const state = await publicBackend(page, true)
  const policy = { processingPolicyId: f.noteId, policyCode: 'synthetic-test', policyVersion: 1,
    policyFingerprint: 'a'.repeat(64), disclosureRevision: 'synthetic-1', effectiveAt: f.timestamp,
    acknowledged: false, acknowledgementRequired: true,
    disclosureContent: 'SYNTHETIC ONLY. <script>not executable</script>\nNo real provider approval.',
    providerProcessingCategories: ['queryEmbedding', 'sourceEmbedding'] }
  let acknowledgements = 0
  await page.route('**/api/ai/processing-policy', route => route.fulfill({ json: policy }))
  await page.route('**/api/ai/processing-policy/acknowledgements', async route => {
    expect(route.request().headers()['x-csrf-token']).toBe('synthetic-proof')
    expect(route.request().postDataJSON()).toMatchObject({ policyVersion: policy.policyVersion, confirmAcknowledgement: true })
    acknowledgements++
    if (acknowledgements === 1) {
      policy.policyVersion = 2; policy.disclosureRevision = 'synthetic-2'; policy.policyFingerprint = 'b'.repeat(64)
      policy.disclosureContent = 'SYNTHETIC REPLACEMENT ONLY. Review this new content.'
      await route.fulfill({ status: 409, contentType: 'application/problem+json', json: {
        type: 'about:blank', title: 'Policy changed', status: 409, code: 'processing_policy_changed', instance: '/api/ai/processing-policy/acknowledgements', traceId: `tr_${'a'.repeat(32)}` } })
    } else { policy.acknowledged = true; policy.acknowledgementRequired = false; await route.fulfill({ status: 204 }) }
  })
  await page.goto('/settings/privacy')
  await expect(page.getByLabel('Current approved processing disclosure')).toContainText('<script>not executable</script>')
  expect(acknowledgements).toBe(0)
  await page.getByRole('button', { name: 'I acknowledge this processing disclosure' }).click()
  await expect(page.getByLabel('Current approved processing disclosure')).toContainText('SYNTHETIC REPLACEMENT')
  await expect(page.getByRole('alert')).toContainText('Review the current disclosure')
  expect(acknowledgements).toBe(1)
  await page.getByRole('button', { name: 'I acknowledge this processing disclosure' }).click()
  await expect(page.getByText('Disclosure acknowledged. No Note AI choice was changed.')).toBeVisible()
  expect(acknowledgements).toBe(2)
  expect(state.calls.some(c => c.method === 'PUT' || c.path.includes('ai-access'))).toBe(false)
})

test('fresh owner Publication resolves authoritative source and retirement removes navigation', async ({ page }) => {
  const state = await publicBackend(page, true)
  await page.goto(`/publications/${f.pubId}`)
  await expect(page.getByRole('link', { name: 'Open source Note' })).toHaveAttribute('href', `/notes/${f.noteId}`)
  state.publication.sourceNoteId = null
  state.sourceUsable = false
  await page.reload()
  await expect(page.getByRole('link', { name: 'Open source Note' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Update public copy', exact: true })).toBeDisabled()
  expect(state.calls.some(c => c.path === `/api/notes/${f.noteId}`)).toBe(false)
})

test('OIDC-origin MFA uses the server-bound destination after elevation, not a URL continuation', async ({ page }) => {
  await publicBackend(page, false)
  let elevated = false
  const challenge = 'A'.repeat(43)
  await page.route('**/api/auth/session', route => route.fulfill({ json: elevated ? { state: 'authenticated' } : { state: 'mfaRequired', challengeId: challenge } }))
  await page.route(`**/api/auth/mfa/challenges/${challenge}/totp`, async route => {
    expect(route.request().postDataJSON()).toEqual({ code: '123456' })
    elevated = true
    await route.fulfill({ json: { state: 'authenticated', intendedDestination: '/settings/security' } })
  })
  await page.goto('/auth/complete')
  await expect(page).toHaveURL('/mfa')
  await page.getByLabel('Authenticator code').fill('123456')
  await page.getByRole('button', { name: 'Verify and continue' }).click()
  await expect(page).toHaveURL('/settings/security')
  expect(page.url()).not.toContain(challenge)
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0])
})

test('private avatar loads after refresh through the authenticated byte endpoint and disappears after removal', async ({ page }) => {
  await publicBackend(page, true)
  let configured = true, reads = 0
  await page.route('**/api/me/profile', route => route.fulfill({ json: { ...f.profile, avatar: configured
    ? { mediaType: 'image/png', width: 1, height: 1, byteSize: 68, createdAt: f.timestamp } : null } }))
  await page.route('**/api/me/profile/avatar/content', async route => {
    expect(route.request().method()).toBe('GET'); reads++
    await route.fulfill({ contentType: 'image/png', headers: { 'Cache-Control': 'no-store' },
      body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64') })
  })
  await page.route('**/api/me/profile/avatar', async route => {
    expect(route.request().method()).toBe('DELETE')
    expect(route.request().headers()['x-csrf-token']).toBe('synthetic-proof')
    configured = false; await route.fulfill({ status: 204 })
  })
  await page.goto('/settings/profile')
  const image = page.getByRole('img', { name: 'Your private profile avatar' })
  await expect(image).toHaveAttribute('src', /^blob:/)
  const beforeRefresh = reads
  await page.reload(); await expect(image).toHaveAttribute('src', /^blob:/)
  // Development StrictMode deliberately mounts effects twice; each actual page
  // lifetime must still fetch afresh and issue at most two bounded GETs.
  expect(reads).toBeGreaterThan(beforeRefresh); expect(reads).toBeLessThanOrEqual(4)
  await page.getByLabel('Display name').fill('Uncommitted profile draft')
  await page.getByRole('button', { name: 'Remove avatar' }).click()
  await expect(image).toHaveCount(0)
  await expect(page.getByLabel('Display name')).toHaveValue('Uncommitted profile draft')
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0])
})
