import { test, expect, type Page } from '@playwright/test'

const id = '01990a55-9e12-7ac4-8f5b-31aa4a91d401'
const original = 'Take the early train and leave the afternoon open.\n\n## Before we go\n\n- Book the small guesthouse\n- Pack a notebook\n\n1. Walk along the harbour\n2. Find somewhere for lunch\n\n**Remember:** bring the camera.\n'
async function fixture(page: Page, signedIn = true, markdown = original) {
  let state = signedIn ? 'authenticated' : 'anonymous'
  let note = { id, title: 'A weekend by the sea', markdown, tags: ['Travel', 'Personal'], pinned: true, lifecycle: 'active', aiEnabled: false, createdAt: '2026-09-30T09:00:00Z', updatedAt: '2026-10-02T09:00:00Z' }
  const saves: unknown[] = []
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname, method = request.method()
    if (!path.startsWith('/api/')) { await route.continue(); return }
    let body: unknown, status = 200, headers: Record<string,string> = {}
    if (path === '/api/auth/session') body = { state }
    else if (path === '/api/auth/csrf') body = { csrfToken: 'synthetic-proof' }
    else if (path === '/api/auth/logout') { state = 'anonymous'; status = 204 }
    else if (path === '/api/auth/login/password') { state = 'authenticated'; body = { state } }
    else if (path === '/api/me/note-preferences') body = { defaultAiEnabledForNewNotes: false }
    else if (path === '/api/me/security') body = { email: 'reader@example.test', passwordConfigured: true, mfaState: 'disabled', oidcLinks: [] }
    else if (path === '/api/me/security/sessions') body = { sessions: [{ sessionHandle: 'synthetic-session', current: true, client: 'This browser', createdAt: '2026-10-02T09:00:00Z', lastSeenAt: '2026-10-03T09:00:00Z', expiresAt: '2026-10-04T09:00:00Z' }] }
    else if (path === '/api/notes') body = { items: [note, ...['Small ideas for the kitchen', 'Reading for autumn', 'Friday project review', 'A better morning routine', 'Learning to draw'].map((title, index) => ({ ...note, id: id.slice(0,-1) + (index + 2), title, pinned: false, tags: ['Ideas'], markdown: 'A few things to revisit when there is time. Start with one small, practical change.' }))], nextCursor: null }
    else if (path.endsWith('/versions')) body = { items: [{ id, title: note.title, sourceRevision: 2, checkpointKind: 'policy', createdAt: note.createdAt }], nextCursor: null }
    else if (path.includes('/versions/')) body = { id, title: note.title, markdown: original, sourceRevision: 2, checkpointKind: 'policy', createdAt: note.createdAt }
    else if (path === `/api/notes/${id}`) {
      if (method === 'PUT') { saves.push(request.postDataJSON()); note = { ...note, ...request.postDataJSON() } }
      body = note; headers = { ETag: '"synthetic-revision"' }
    } else { status = 503; body = { type: 'about:blank', status, title: 'Unavailable', code: 'service_unavailable' } }
    await route.fulfill({ status, headers, contentType: status >= 400 ? 'application/problem+json' : 'application/json', ...(status === 204 ? {} : { body: JSON.stringify(body) }) })
  })
  return saves
}

test('formatted writing preserves untouched Markdown and saves only explicitly', async ({ page }) => {
  const saves = await fixture(page)
  await page.goto(`/notes/${id}`)
  const editor = page.getByRole('textbox', { name: 'Note body' })
  await expect(editor.getByRole('heading', { name: 'Before we go' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save note', exact: true })).toBeDisabled()
  await page.getByLabel('Title', { exact: true }).fill('Weekend plans')
  await page.getByRole('button', { name: 'Save note', exact: true }).click()
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  expect(saves).toEqual([{ title: 'Weekend plans', markdown: original }])
  await editor.click()
  await page.keyboard.press('ControlOrMeta+End')
  await page.keyboard.type('Bring a sketchbook.')
  await expect(page.getByText('Unsaved changes')).toBeVisible()
  expect(saves).toHaveLength(1)
  await page.keyboard.press('ControlOrMeta+s')
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  expect(saves).toHaveLength(2)
})

test('formatted toolbar, safe paste, undo and source use the same draft', async ({ page }) => {
  await fixture(page)
  await page.goto(`/notes/${id}`)
  const editor = page.getByRole('textbox', { name: 'Note body' })
  await expect(editor).toBeVisible()
  await expect(page.getByRole('button', { name: 'Bold', exact: true })).toBeEnabled()
  await editor.click(); await page.keyboard.press('ControlOrMeta+End')
  await expect(page.getByRole('button', { name: 'Bold', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Bold', exact: true }).click()
  await page.keyboard.type('Deliberate words')
  await expect(editor.locator('strong').last()).toHaveText('Deliberate words')
  await page.keyboard.press('ControlOrMeta+z')
  await expect(editor).not.toContainText('Deliberate words')
  await editor.evaluate(element => { const data = new DataTransfer(); data.setData('text/html', '<img src="https://untrusted.invalid/paste" onerror="alert(1)">'); data.setData('text/plain', 'Safe pasted text'); element.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true })) })
  await expect(editor).toContainText('Safe pasted text')
  await expect(editor.locator('img')).toHaveCount(0)
  await page.getByRole('button', { name: 'Markdown source', exact: true }).click()
  await expect(page.getByLabel('Markdown', { exact: true })).toContainText('Safe pasted text')
})

test('GFM content survives formatted editing, including reference and footnote content', async ({ page }) => {
  const markdown = '# Heading\n\nবাংলা 日本語 café\n\n- Parent\n  - Child\n\n1. First\n2. Second\n\n- [x] Finished\n- [ ] Pending\n\n| Name | Value |\n| --- | --- |\n| Lake | 42 |\n\n```ts\nconst value = 42\n```\n\nUse `value`, **bold**, *italic*, ~~strike~~ and [site][ref].\n\n[ref]: https://example.invalid\n\nFootnote[^note].\n\n[^note]: Keep this explanation.\n\n<!-- Keep this comment -->\n\nEnd.'
  await fixture(page, true, markdown)
  await page.goto(`/notes/${id}`)
  const editor = page.getByRole('textbox', { name: 'Note body' })
  await expect(editor).toContainText('Keep this explanation.')
  await editor.click(); await page.keyboard.press('ControlOrMeta+End'); await page.keyboard.type(' Added.')
  await page.getByRole('button', { name: 'Markdown source', exact: true }).click()
  const result = await page.getByLabel('Markdown', { exact: true }).inputValue()
  for (const content of ['Heading', 'বাংলা 日本語 café', 'Parent', 'Child', 'First', 'Second', '[x]', '[ ]', 'Lake', '42', 'const value = 42', '`value`', '**bold**', '*italic*', '~~strike~~', 'https://example.invalid', 'Keep this explanation.', '<!-- Keep this comment -->', 'Added.']) expect(result).toContain(content)
})

test('remote image and raw HTML stay inert in formatted writing', async ({ page }) => {
  let external = 0
  await fixture(page)
  await page.route('https://untrusted.invalid/**', route => { external++; return route.abort() })
  await page.goto(`/notes/${id}`)
  await page.getByRole('button', { name: 'Markdown source', exact: true }).click()
  await page.getByLabel('Markdown', { exact: true }).fill('![Lake](https://untrusted.invalid/image)\n\n<script>alert(1)</script>')
  await page.getByRole('button', { name: 'Formatted writing', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'Note body' })).toBeVisible()
  // ProseMirror uses source-less image separators for caret placement around atoms.
  await expect(page.locator('.writing-surface img[src], .writing-surface img[srcset], .writing-surface script, .writing-surface iframe, .writing-surface [onerror]')).toHaveCount(0)
  await expect(page.locator('.writing-surface img:not(.ProseMirror-separator)')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: 'Note body' })).toContainText('<script>alert(1)</script>')
  expect(external).toBe(0)
})

test('checklist controls change only the local Markdown draft', async ({ page }) => {
  const saves = await fixture(page, true, '- [ ] Pack the notebook\n')
  await page.goto(`/notes/${id}`)
  const checkbox = page.getByRole('checkbox', { name: 'Task completed' })
  await expect(checkbox).not.toBeChecked(); await checkbox.click(); await expect(checkbox).toBeChecked()
  await expect(page.getByText('Unsaved changes')).toBeVisible()
  expect(saves).toEqual([])
  await page.getByRole('button', { name: 'Markdown source', exact: true }).click()
  await expect(page.getByLabel('Markdown', { exact: true })).toHaveValue(/\[x\] Pack the notebook/)
})

test('unrepresented reference definitions retain exact source instead of silently disappearing', async ({ page }) => {
  const markdown = 'Keep this thought.\n\n[unused]: https://example.invalid/remember\n'
  const saves = await fixture(page, true, markdown)
  await page.goto(`/notes/${id}`)
  await expect(page.getByRole('alert')).toContainText('Your original text is unchanged')
  await page.getByRole('button', { name: 'Markdown source', exact: true }).click()
  await expect(page.getByLabel('Markdown', { exact: true })).toHaveValue(markdown)
  expect(saves).toEqual([])
})

test('a large valid note opens and accepts a formatted edit without truncation', async ({ page }) => {
  test.setTimeout(60_000)
  const paragraph = 'An observation about the landscape and the changing light. '.repeat(160)
  const markdown = (paragraph + '\n\n').repeat(100)
  expect(markdown.length).toBeLessThan(1_000_000)
  await fixture(page, true, markdown)
  await page.goto(`/notes/${id}`)
  const editor = page.getByRole('textbox', { name: 'Note body' })
  await expect(editor).toBeVisible({ timeout: 30_000 })
  await editor.click(); await page.keyboard.press('ControlOrMeta+End'); await page.keyboard.type('Closing observation.')
  await page.getByRole('button', { name: 'Markdown source', exact: true }).click()
  const result = await page.getByLabel('Markdown', { exact: true }).inputValue()
  expect(result.match(/An observation about the landscape and the changing light\./g)).toHaveLength(16_000)
  expect(result).toContain('Closing observation.')
})

test('in-flight Save retains subsequent formatted edits; 412 retains that exact draft', async ({ page }) => {
  await fixture(page)
  let release!: () => void
  const held = new Promise<void>(resolve => { release = resolve })
  let first = true
  await page.route(`**/api/notes/${id}`, async route => {
    if (route.request().method() !== 'PUT') return route.fallback()
    if (!first) return route.fulfill({ status: 412, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: 'Stale note', status: 412, code: 'stale_write', instance: `/api/notes/${id}`, traceId: `tr_${'a'.repeat(32)}` }) })
    first = false
    const input = route.request().postDataJSON()
    await held
    await route.fulfill({ status: 200, headers: { ETag: '"next-revision"' }, contentType: 'application/json', body: JSON.stringify({ id, ...input, tags: [], pinned: false, lifecycle: 'active', aiEnabled: false, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-02T00:00:00Z' }) })
  })
  await page.goto(`/notes/${id}`)
  const editor = page.getByRole('textbox', { name: 'Note body' })
  await editor.click(); await page.keyboard.press('ControlOrMeta+End'); await page.keyboard.type(' Before save.')
  await page.getByRole('button', { name: 'Save note', exact: true }).click()
  await expect(page.getByText('Saving…', { exact: true })).toBeVisible()
  await editor.click(); await page.keyboard.press('ControlOrMeta+End'); await page.keyboard.type(' During save.')
  release()
  await expect(page.getByText('Unsaved changes')).toBeVisible()
  await page.getByRole('button', { name: 'Markdown source', exact: true }).click()
  const draft = await page.getByLabel('Markdown', { exact: true }).inputValue()
  expect(draft).toContain('During save.')
  await page.getByRole('button', { name: 'Save note', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Save conflict' })).toBeVisible()
  await expect(page.getByLabel('Markdown', { exact: true })).toHaveValue(draft)
})

test('logout destroys the formatted editor and its undo history', async ({ page }) => {
  await fixture(page)
  await page.goto(`/notes/${id}`)
  const editor = page.getByRole('textbox', { name: 'Note body' })
  await editor.click(); await page.keyboard.press('ControlOrMeta+End'); await page.keyboard.type(' Private synthetic draft.')
  await page.getByRole('button', { name: 'Log out', exact: true }).click()
  await expect(page.getByRole('heading', { name: /thoughts, in good order|Welcome back/i })).toBeVisible()
  await page.keyboard.press('ControlOrMeta+z')
  await expect(page.getByText('Private synthetic draft.', { exact: false })).toHaveCount(0)
  await expect(editor).toHaveCount(0)
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
})

test('phone composition and constrained visual viewport keep editing and controls reachable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 460 }); await fixture(page)
  await page.goto(`/notes/${id}`)
  const editor = page.getByRole('textbox', { name: 'Note body' })
  await editor.click(); await page.keyboard.press('ControlOrMeta+End')
  await editor.dispatchEvent('compositionstart', { data: '' })
  await page.keyboard.insertText(' 日本語のメモ')
  await editor.dispatchEvent('compositionend', { data: '日本語のメモ' })
  await expect(editor).toContainText('日本語のメモ')
  await page.getByRole('button', { name: 'Save note', exact: true }).click()
  await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  await page.screenshot({ path: '../review-packages/visual-redesign-evidence/phone-keyboard-constrained.png' })
})

for (const width of [390, 1440]) test(`destructive confirmation, reflow and reduced motion at ${width}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 }); await page.emulateMedia({ reducedMotion: 'reduce' }); await fixture(page)
  await page.goto(`/notes/${id}`)
  await page.getByLabel('Title', { exact: true }).fill('A deliberately long title about small observations and things worth remembering through a busy autumn')
  await page.getByRole('button', { name: 'Details', exact: true }).click()
  await page.getByRole('button', { name: 'Trash', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.screenshot({ path: `../review-packages/visual-redesign-evidence/${width}-destructive-dialog.png` })
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: /Close details/ }).click()
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: `../review-packages/visual-redesign-evidence/${width}-text-reflow.png` })
})

for (const [name, width, height] of [['desktop', 1440, 1000], ['tablet', 768, 1024], ['phone', 390, 844], ['small-phone', 320, 740]] as const) {
  test(`visual review and library continuity at ${name}`, async ({ page }) => {
    await page.setViewportSize({ width, height }); await fixture(page)
    await page.goto('/notes')
    await expect(page.getByRole('link', { name: /A weekend by the sea/ })).toBeVisible()
    await expect(page.getByRole('textbox', { name: 'Note body' })).toHaveCount(0)
    await page.screenshot({ path: `../review-packages/visual-redesign-evidence/${name}-library.png`, fullPage: true })
    await page.getByRole('link', { name: /A weekend by the sea/ }).click()
    await expect(page.getByRole('textbox', { name: 'Note body' })).toBeVisible()
    await page.screenshot({ path: `../review-packages/visual-redesign-evidence/${name}-writing.png`, fullPage: true })
    await page.getByRole('button', { name: 'Details', exact: true }).click()
    await expect(page.getByRole('complementary', { name: 'Note tools' })).toBeVisible()
    await page.screenshot({ path: `../review-packages/visual-redesign-evidence/${name}-details.png`, fullPage: true })
    await page.getByRole('button', { name: /Close details/ }).click()
    await page.getByRole('button', { name: 'History', exact: true }).click()
    await expect(page.getByRole('button', { name: /Inspect A weekend/ })).toBeVisible()
    await page.screenshot({ path: `../review-packages/visual-redesign-evidence/${name}-history.png`, fullPage: true })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}

for (const path of ['/', '/login', '/signup', '/settings/security', '/settings/security/sessions']) {
  test(`visual review ${path}`, async ({ page }) => {
    await fixture(page, path.startsWith('/settings'))
    await page.goto(path)
    const heading = path === '/' ? /thoughts, in good order/i : path === '/login' ? 'Welcome back' : path === '/signup' ? 'Create your account' : path.endsWith('/sessions') ? 'Your sessions' : 'Account security'
    await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible()
    if (path.startsWith('/settings')) await expect(page.getByText(path.endsWith('/sessions') ? 'Current session' : 'Current email: reader@example.test', { exact: true })).toBeVisible()
    const name = path === '/' ? 'landing' : path.replaceAll('/', '-')
    await page.screenshot({ path: `../review-packages/visual-redesign-evidence/desktop-${name}.png`, fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: `../review-packages/visual-redesign-evidence/phone-${name}.png`, fullPage: true })
  })
}

for (const failed of [false, true]) test(`visual library ${failed ? 'error' : 'empty'} state`, async ({ page }) => {
  await fixture(page)
  await page.route('**/api/notes', route => route.fulfill({ status: failed ? 503 : 200,
    contentType: failed ? 'application/problem+json' : 'application/json', body: JSON.stringify(failed
      ? { type: 'about:blank', title: 'Unavailable', status: 503, code: 'service_unavailable', instance: '/api/notes', traceId: `tr_${'a'.repeat(32)}` }
      : { items: [], nextCursor: null }) }))
  await page.goto('/notes')
  if (failed) await expect(page.getByRole('alert')).toContainText('Could not load notes')
  else await expect(page.getByRole('heading', { name: 'No notes yet' })).toBeVisible()
  await page.screenshot({ path: `../review-packages/visual-redesign-evidence/library-${failed ? 'error' : 'empty'}.png` })
})
