import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { App } from '../../app/App'
import { AuthRuntime } from '../auth/AuthRuntime'
import type { NoteCore } from './NotesApi'
import { noteKeys } from './NotesKeys'

const id = '01990a55-9e12-7ac4-8f5b-31aa4a91d401'
const initial: NoteCore = { id, title: 'Saved title', markdown: 'Saved body', lifecycle: 'active',
  pinned: false, tags: [], aiEnabled: false, createdAt: '2026-09-30T00:00:00Z',
  updatedAt: '2026-09-30T00:00:00Z' }
const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
const problem = (status: number, code: string) => json({ type: 'about:blank', title: 'Safe', status,
  code, instance: `/api/notes/${id}`, traceId: `tr_${'a'.repeat(32)}` }, status,
{ 'Content-Type': 'application/problem+json' })

async function readyButton(name: string): Promise<HTMLButtonElement> {
  return waitFor(() => {
    const button = screen.getByRole('button', { name }) as HTMLButtonElement
    expect(button.disabled).toBe(false)
    return button
  })
}

function mount(path: string, state: 'authenticated' | 'anonymous' | 'mfaRequired' = 'authenticated',
  onRequest?: (method: string, path: string, body: unknown) => Response | undefined) {
  window.history.replaceState(null, '', path)
  const calls: Array<{ method: string; path: string; body: unknown; ifMatch: string | null }> = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const target = String(input), method = init?.method || 'GET'
    const body: unknown = init?.body ? JSON.parse(String(init.body)) : null
    calls.push({ method, path: target, body, ifMatch: new Headers(init?.headers).get('If-Match') })
    if (target === '/api/auth/session') return json({ state })
    if (target === '/api/auth/csrf') return json({ csrfToken: 'synthetic-proof' })
    if (target === '/api/me/note-preferences') return json({ defaultAiEnabledForNewNotes: false })
    const response = onRequest?.(method, target, body)
    if (response) return response
    if (target === `/api/notes/${id}` && method === 'GET') return json(initial, 200, { ETag: '"e1"' })
    if (target === '/api/notes' && method === 'GET') return json({ items: [], nextCursor: null })
    return problem(503, 'service_unavailable')
  }))
  const auth = new AuthRuntime()
  render(<App auth={auth} />)
  return { calls, auth }
}

// Compile the lazy route module before measuring DOM-state transitions. Cold
// Vite transformation is not part of the simulated request/authority lifecycle.
beforeAll(async () => { await import('./NotesPages') })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); window.history.replaceState(null, '', '/') })

describe('private Notes browser journey', () => {
  it.each(['active', 'archived'] as const)('does not offer permanent deletion for %s notes', async lifecycle => {
    mount(`/notes/${id}`, 'authenticated', (method, path) => method === 'GET' && path === `/api/notes/${id}`
      ? json({ ...initial, lifecycle }, 200, { ETag: '"e1"' }) : undefined)
    await screen.findByDisplayValue('Saved title')
    expect(screen.queryByRole('button', { name: 'Permanent delete' })).toBeNull()
  })

  it('explicitly confirms abandoning a dirty draft and evicts all deleted private content on 204', async () => {
    const { calls, auth } = mount(`/notes/${id}`, 'authenticated', (method, path) => {
      if (method === 'GET' && path === `/api/notes/${id}`) return json({ ...initial, lifecycle: 'trashed' }, 200, { ETag: '"e1"' })
      if (method === 'DELETE') return new Response(null, { status: 204 })
      return undefined
    })
    await screen.findByDisplayValue('Saved title')
    const scope = auth.session.viewerScope
    for (const lifecycle of ['active', 'archived', 'trashed']) auth.queries.setQueryData(
      noteKeys.list(scope, { lifecycle, sort: 'updatedAtDesc' }),
      { pages: [{ items: [initial], nextCursor: null }], pageParams: [null] })
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' Exact unsaved title ' } })
    fireEvent.change(screen.getByLabelText('Markdown'), { target: { value: 'Exact\n**draft** ' } })
    fireEvent.click(await readyButton('Permanent delete'))
    expect(screen.getByText(/Unlike Trash, permanent deletion cannot be undone/)).toBeTruthy()
    expect(screen.getByText(/Your unsaved title and Markdown will be discarded, not saved/)).toBeTruthy()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Keep note' }))
    fireEvent.keyDown(document.activeElement!, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Permanently delete and discard draft' }))
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Permanent delete' }))
    expect(calls.some(call => call.method === 'DELETE' || call.method === 'PUT')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Permanent delete' }))
    fireEvent.click(screen.getByRole('button', { name: 'Permanently delete and discard draft' }))
    await screen.findByRole('heading', { name: 'Notes' })
    expect(screen.getByRole('button', { name: 'Trashed notes' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.queryByDisplayValue(' Exact unsaved title ')).toBeNull()
    expect(auth.queries.getQueryData(noteKeys.core(scope, id))).toBeUndefined()
    const lists = auth.queries.getQueriesData({ queryKey: noteKeys.lists(scope) })
    expect(JSON.stringify(lists)).not.toContain('Saved body')
    expect(calls.filter(call => call.method === 'DELETE')).toEqual([
      { method: 'DELETE', path: `/api/notes/${id}`, body: { confirmPermanentDelete: true }, ifMatch: '"e1"' },
    ])
    expect(calls.some(call => call.method === 'PUT')).toBe(false)
  })

  it('requires separate explicit unpublish confirmation for permanent deletion', async () => {
    const { calls } = mount(`/notes/${id}`, 'authenticated', (method, path, body) => {
      if (method === 'GET' && path === `/api/notes/${id}`) return json({ ...initial, lifecycle: 'trashed' }, 200, { ETag: '"e1"' })
      if (method === 'DELETE') return (body as { confirmPublicationUnpublish?: boolean })?.confirmPublicationUnpublish
        ? new Response(null, { status: 204 }) : problem(409, 'publication_consequence_required')
      return undefined
    })
    await screen.findByDisplayValue('Saved title')
    fireEvent.click(await readyButton('Permanent delete'))
    fireEvent.click(screen.getByRole('button', { name: 'Permanently delete' }))
    await screen.findByRole('dialog', { name: 'Unpublish and permanently delete?' })
    expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(1)
    fireEvent.click(await readyButton('Unpublish and permanently delete'))
    await screen.findByRole('heading', { name: 'Notes' })
    expect(calls.filter(call => call.method === 'DELETE').map(call => call.body)).toEqual([
      { confirmPermanentDelete: true }, { confirmPermanentDelete: true, confirmPublicationUnpublish: true },
    ])
  })

  it('keeps a dirty draft until explicit reauth departure and never replays delete after reauthentication', async () => {
    let recent = false
    const { calls, auth } = mount(`/notes/${id}`, 'authenticated', (method, path) => {
      if (method === 'GET' && path === `/api/notes/${id}`) return json({ ...initial, lifecycle: 'trashed' }, 200, { ETag: '"e1"' })
      if (method === 'DELETE') return recent ? new Response(null, { status: 204 }) : problem(403, 'recent_authentication_required')
      if (path === '/api/me/security') return json({ email: 'synthetic@example.test', passwordConfigured: true, mfaState: 'disabled', oidcLinks: [] })
      if (path === '/api/auth/reauth/password') { recent = true; return new Response(null, { status: 204 }) }
      return undefined
    })
    await screen.findByDisplayValue('Saved title')
    fireEvent.change(screen.getByLabelText('Markdown'), { target: { value: 'Exact dirty draft' } })
    fireEvent.click(await readyButton('Permanent delete'))
    fireEvent.click(screen.getByRole('button', { name: 'Permanently delete and discard draft' }))
    await screen.findByRole('dialog', { name: 'Confirm your identity before deleting' })
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe('Exact dirty draft')
    expect(auth.continuation.returnIntent).toBeNull()
    expect(window.location.pathname).toBe(`/notes/${id}`)
    fireEvent.click(await readyButton('Discard draft and confirm identity'))
    await screen.findByRole('heading', { name: 'Confirm your identity' })
    expect(auth.continuation.returnIntent).toBe(`/notes/${id}`)
    fireEvent.change(await screen.findByLabelText('Password'), { target: { value: 'Synthetic-password-123!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm with password' }))
    await screen.findByDisplayValue('Saved title')
    expect(auth.continuation.returnIntent).toBeNull()
    expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(1)
    expect(calls.some(call => call.method === 'PUT')).toBe(false)
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(await readyButton('Permanent delete'))
    expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Permanently delete' }))
    await screen.findByRole('heading', { name: 'Notes' })
    expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(2)
  })

  it.each([412, 503])('preserves the exact draft and private cache on failed permanent delete (%s)', async status => {
    const { calls, auth } = mount(`/notes/${id}`, 'authenticated', (method, path) => {
      if (method === 'GET' && path === `/api/notes/${id}`) return json({ ...initial, lifecycle: 'trashed' }, 200, { ETag: '"e1"' })
      if (method === 'DELETE') return problem(status, status === 412 ? 'stale_write' : 'service_unavailable')
      return undefined
    })
    await screen.findByDisplayValue('Saved title')
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' Exact unsaved title ' } })
    fireEvent.click(await readyButton('Permanent delete'))
    fireEvent.click(screen.getByRole('button', { name: 'Permanently delete and discard draft' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(' Exact unsaved title ')
    expect(window.location.pathname).toBe(`/notes/${id}`)
    expect(auth.queries.getQueryData(noteKeys.core(auth.session.viewerScope, id))).toBeTruthy()
    expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(1)
    expect(calls.some(call => call.method === 'PUT')).toBe(false)
  })

  it.each(['active', 'archived'] as const)('preserves exact dirty drafts through Trash and Restore to %s', async previous => {
    const { calls, auth } = mount(`/notes/${id}`, 'authenticated', (method, path) => {
      if (method === 'GET' && path === `/api/notes/${id}`) return json({ ...initial, lifecycle: previous }, 200, { ETag: '"e1"' })
      if (path.endsWith('/trash')) return json({ ...initial, lifecycle: 'trashed' }, 200, { ETag: '"e2"' })
      if (path.endsWith('/restore')) return json({ ...initial, lifecycle: previous }, 200, { ETag: '"e3"' })
      return undefined
    })
    await screen.findByDisplayValue('Saved title')
    for (const lifecycle of ['active', 'archived', 'trashed']) auth.queries.setQueryData(
      noteKeys.list(auth.session.viewerScope, { lifecycle, sort: 'updatedAtDesc' }),
      { pages: [{ items: [], nextCursor: null }], pageParams: [null] })
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' Exact title ' } })
    fireEvent.change(screen.getByLabelText('Markdown'), { target: { value: 'Exact\n**draft** ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Trash' }))
    const confirmation = screen.getByRole('button', { name: 'Trash and keep draft' })
    expect(document.activeElement).toBe(confirmation)
    fireEvent.keyDown(confirmation, { key: 'Tab' })
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Keep editing' }))
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Trash' }))
    expect(calls.some(call => call.method === 'POST')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Trash' }))
    fireEvent.click(screen.getByRole('button', { name: 'Trash and keep draft' }))
    await readyButton('Restore')
    expect(screen.getByRole('button', { name: 'Save note' }).hasAttribute('disabled')).toBe(true)
    expect(screen.getByRole('button', { name: 'Edit tags' }).hasAttribute('disabled')).toBe(true)
    fireEvent.keyDown(document, { key: 's', ctrlKey: true })
    expect(calls.some(call => call.method === 'PUT')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    await readyButton('Trash')
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(' Exact title ')
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe('Exact\n**draft** ')
    expect(screen.getByText('Unsaved changes')).toBeTruthy()
    expect(calls.filter(call => call.method === 'POST')).toEqual([
      { method: 'POST', path: `/api/notes/${id}/trash`, body: null, ifMatch: '"e1"' },
      { method: 'POST', path: `/api/notes/${id}/restore`, body: null, ifMatch: '"e2"' },
    ])
    expect(screen.getByRole('button', { name: 'Save note' }).hasAttribute('disabled')).toBe(previous === 'archived')
    for (const lifecycle of ['active', 'archived', 'trashed']) expect(auth.queries.getQueryState(
      noteKeys.list(auth.session.viewerScope, { lifecycle, sort: 'updatedAtDesc' }))?.isInvalidated).toBe(true)
  })

  it('requires a separate explicit public-copy confirmation, never inferred from dirty confirmation', async () => {
    let confirmed = false
    const { calls } = mount(`/notes/${id}`, 'authenticated', (_method, path, body) => {
      if (path.endsWith('/trash')) {
        confirmed = !!(body as { confirmPublicationUnpublish?: boolean } | null)?.confirmPublicationUnpublish
        return confirmed ? json({ ...initial, lifecycle: 'trashed' }, 200, { ETag: '"e2"' })
          : problem(409, 'publication_consequence_required')
      }
      return undefined
    })
    await screen.findByDisplayValue('Saved title')
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' Unsaved ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Trash' }))
    fireEvent.click(screen.getByRole('button', { name: 'Trash and keep draft' }))
    const publicConfirmation = await screen.findByRole('button', { name: 'Unpublish and trash' })
    await waitFor(() => expect(publicConfirmation.hasAttribute('disabled')).toBe(false))
    expect(document.activeElement).toBe(publicConfirmation)
    expect(screen.getByText('Continuing will unpublish the current public copy. Restore will not republish it.')).toBeTruthy()
    expect(confirmed).toBe(false)
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }))
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Trash' }))
    fireEvent.click(screen.getByRole('button', { name: 'Trash and keep draft' }))
    fireEvent.click(await readyButton('Unpublish and trash'))
    await readyButton('Restore')
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(' Unsaved ')
    expect(calls.filter(call => call.method === 'POST').map(call => [call.body, call.ifMatch])).toEqual([
      [null, '"e1"'], [null, '"e1"'], [{ confirmPublicationUnpublish: true }, '"e1"'],
    ])
  })

  it.each(['trash', 'restore'] as const)('preserves draft and validator on stale %s', async command => {
    const { calls } = mount(`/notes/${id}`, 'authenticated', (method, path) => {
      if (method === 'GET' && path === `/api/notes/${id}`) return json({ ...initial,
        lifecycle: command === 'restore' ? 'trashed' : 'active' }, 200, { ETag: '"e1"' })
      if (path.endsWith('/' + command)) return problem(412, 'stale_write')
      return undefined
    })
    await screen.findByDisplayValue('Saved title')
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Exact stale draft' } })
    fireEvent.click(screen.getByRole('button', { name: command === 'trash' ? 'Trash' : 'Restore' }))
    if (command === 'trash') fireEvent.click(screen.getByRole('button', { name: 'Trash and keep draft' }))
    await screen.findByRole('heading', { name: 'Another version was saved' })
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe('Exact stale draft')
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(1)
    expect(calls.find(call => call.method === 'POST')?.ifMatch).toBe('"e1"')
  })

  it('loads the Trashed collection through the existing Notes list query family', async () => {
    const { calls } = mount('/notes', 'authenticated', (_method, path) => {
      if (path === '/api/notes?lifecycle=trashed') return json({ items: [{ ...initial, lifecycle: 'trashed' }], nextCursor: null })
      return undefined
    })
    await screen.findByText('No notes yet')
    fireEvent.click(screen.getByRole('button', { name: 'Trashed notes' }))
    await screen.findByRole('link', { name: /Saved title/ })
    expect(calls.some(call => call.path === '/api/notes?lifecycle=trashed')).toBe(true)
    expect(window.location.pathname).toBe('/notes')
  })

  it('pins and unpins with current validators without saving a dirty draft', async () => {
    const { calls, auth } = mount(`/notes/${id}`, 'authenticated', (method, path, body) => {
      if (path === `/api/notes/${id}/pin`) return json({ ...initial, pinned: method === 'PUT' },
        200, { ETag: method === 'PUT' ? '"e2"' : '"e3"' })
      if (method === 'PUT' && path === `/api/notes/${id}`) return json({ ...initial, ...body as object }, 200, { ETag: '"e4"' })
      return undefined
    })
    await screen.findByDisplayValue('Saved title', {}, { timeout: 5000 })
    for (const lifecycle of ['active', 'archived']) auth.queries.setQueryData(
      noteKeys.list(auth.session.viewerScope, { lifecycle, sort: 'updatedAtDesc' }),
      { pages: [{ items: [initial], nextCursor: null }], pageParams: [null] })
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' Exact title ' } })
    fireEvent.change(screen.getByLabelText('Markdown'), { target: { value: 'Exact\n**draft** ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Pin' }))
    fireEvent.click(await readyButton('Unpin'))
    await readyButton('Pin')
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(' Exact title ')
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe('Exact\n**draft** ')
    expect(screen.getByText('Unsaved changes')).toBeTruthy()
    expect(calls.filter(call => call.path.endsWith('/pin'))).toEqual([
      { method: 'PUT', path: `/api/notes/${id}/pin`, body: null, ifMatch: '"e1"' },
      { method: 'DELETE', path: `/api/notes/${id}/pin`, body: null, ifMatch: '"e2"' },
    ])
    for (const lifecycle of ['active', 'archived']) expect(auth.queries.getQueryState(
      noteKeys.list(auth.session.viewerScope, { lifecycle, sort: 'updatedAtDesc' }))?.isInvalidated).toBe(true)
    expect(calls.some(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }))
    await screen.findByText('Saved')
    expect(calls.find(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)?.ifMatch).toBe('"e3"')
  })

  it('confirms dirty Archive, preserves the draft, and requires Return before Save', async () => {
    const { calls } = mount(`/notes/${id}`, 'authenticated', (method, path, body) => {
      if (method === 'POST' && path === `/api/notes/${id}/archive`) return json({ ...initial, lifecycle: 'archived' }, 200, { ETag: '"e2"' })
      if (method === 'POST' && path === `/api/notes/${id}/return-from-archive`) return json(initial, 200, { ETag: '"e3"' })
      if (method === 'PUT' && path === `/api/notes/${id}`) return json({ ...initial, ...body as object }, 200, { ETag: '"e4"' })
      return undefined
    })
    await screen.findByDisplayValue('Saved title', {}, { timeout: 5000 })
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' Unsaved title ' } })
    fireEvent.change(screen.getByLabelText('Markdown'), { target: { value: 'Unsaved\nbody ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Archive' }))
    const confirm = await screen.findByRole('button', { name: 'Archive and keep draft' })
    expect(document.activeElement).toBe(confirm)
    fireEvent.keyDown(confirm, { key: 'Tab' })
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Keep editing' }))
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Archive' }))
    expect(calls.some(call => call.method === 'POST')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Archive' }))
    fireEvent.click(screen.getByRole('button', { name: 'Archive and keep draft' }))
    await readyButton('Return from archive')
    expect(screen.getByRole('button', { name: 'Save note' }).hasAttribute('disabled')).toBe(true)
    fireEvent.keyDown(document, { key: 's', ctrlKey: true })
    expect(calls.some(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toBe(false)
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(' Unsaved title ')
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe('Unsaved\nbody ')
    fireEvent.click(screen.getByRole('button', { name: 'Return from archive' }))
    await readyButton('Archive')
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(' Unsaved title ')
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe('Unsaved\nbody ')
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }))
    await screen.findByText('Saved')
    expect(calls.filter(call => call.method === 'POST').map(call => [call.path, call.ifMatch]))
      .toEqual([[`/api/notes/${id}/archive`, '"e1"'], [`/api/notes/${id}/return-from-archive`, '"e2"']])
    expect(calls.find(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)?.ifMatch).toBe('"e3"')
  })

  it.each(['pin', 'archive', 'return-from-archive'])('keeps the exact draft and requires reconciliation after stale %s', async action => {
    const original = { ...initial, lifecycle: action === 'return-from-archive' ? 'archived' as const : 'active' as const }
    const { calls } = mount(`/notes/${id}`, 'authenticated', (method, path) => {
      if (method === 'GET' && path === `/api/notes/${id}`) return json(original, 200, { ETag: '"e1"' })
      if (path === `/api/notes/${id}/${action}`) return problem(412, 'stale_write')
      return undefined
    })
    await screen.findByDisplayValue('Saved title', {}, { timeout: 5000 })
    fireEvent.change(screen.getByLabelText('Markdown'), { target: { value: ' Exact\nlocal draft ' } })
    fireEvent.click(screen.getByRole('button', { name: action === 'pin' ? 'Pin' : action === 'archive' ? 'Archive' : 'Return from archive' }))
    if (action === 'archive') fireEvent.click(screen.getByRole('button', { name: 'Archive and keep draft' }))
    await screen.findByRole('heading', { name: 'Another version was saved' })
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe(' Exact\nlocal draft ')
    expect(screen.getByRole('button', { name: 'Save note' }).hasAttribute('disabled')).toBe(true)
    expect(calls.filter(call => call.method !== 'GET' && call.path.startsWith(`/api/notes/${id}`))).toHaveLength(1)
  })

  it.each([409, 503])('keeps organization and draft unchanged after rejected command %s', async status => {
    const { calls } = mount(`/notes/${id}`, 'authenticated', (method, path) =>
      method === 'PUT' && path === `/api/notes/${id}/pin` ? problem(status, 'invalid_lifecycle_transition') : undefined)
    await screen.findByDisplayValue('Saved title', {}, { timeout: 5000 })
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' Exact draft ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Pin' }))
    await screen.findByRole('alert')
    expect(screen.getByText('Active · Not pinned')).toBeTruthy()
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(' Exact draft ')
    expect(calls.filter(call => call.method === 'PUT')).toHaveLength(1)
  })

  it('moves clean notes between existing Active and Archived views after commands', async () => {
    let core = initial
    const { calls } = mount('/notes', 'authenticated', (method, path) => {
      if (method === 'GET' && path.startsWith('/api/notes?')) return json({ items: core.lifecycle === 'archived' ? [core] : [], nextCursor: null })
      if (method === 'GET' && path === '/api/notes') return json({ items: core.lifecycle === 'active' ? [core] : [], nextCursor: null })
      if (method === 'GET' && path === `/api/notes/${id}`) return json(core, 200, { ETag: '"current"' })
      if (method === 'POST') {
        core = { ...core, lifecycle: path.endsWith('/archive') ? 'archived' : 'active' }
        return json(core, 200, { ETag: path.endsWith('/archive') ? '"archived"' : '"active"' })
      }
      return undefined
    })
    await screen.findByText('Saved title')
    fireEvent.click(screen.getByRole('link', { name: /Saved title/ }))
    await screen.findByDisplayValue('Saved title', {}, { timeout: 5000 })
    fireEvent.click(screen.getByRole('button', { name: 'Archive' }))
    await readyButton('Return from archive')
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(screen.getByRole('link', { name: 'All notes' }))
    await screen.findByText('No notes yet')
    fireEvent.click(screen.getByRole('button', { name: 'Archived notes' }))
    await screen.findByText('Saved title')
    fireEvent.click(screen.getByRole('link', { name: /Saved title/ }))
    fireEvent.click(await readyButton('Return from archive'))
    await readyButton('Archive')
    fireEvent.click(screen.getByRole('link', { name: 'All notes' }))
    await screen.findByText('Saved title')
    fireEvent.click(screen.getByRole('button', { name: 'Archived notes' }))
    await screen.findByText('No archived notes')
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(2)
  })

  it('applies tags independently, preserves the dirty editor and uses the returned ETag for Save', async () => {
    const tagged = { ...initial, tags: ['Films', 'Watch-later'] }
    const { calls, auth } = mount(`/notes/${id}`, 'authenticated', (method, path, body) => {
      if (method === 'PUT' && path === `/api/notes/${id}/tags`) return json(tagged, 200, { ETag: '"e2"' })
      if (method === 'PUT' && path === `/api/notes/${id}`) return json({ ...tagged, ...body as object }, 200, { ETag: '"e3"' })
      return undefined
    })
    await screen.findByDisplayValue('Saved title', {}, { timeout: 5000 })
    auth.queries.setQueryData(noteKeys.list(auth.session.viewerScope,
      { lifecycle: 'active', sort: 'updatedAtDesc' }), { pages: [{ items: [initial], nextCursor: null }], pageParams: [null] })
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' Exact title ' } })
    fireEvent.change(screen.getByLabelText('Markdown'), { target: { value: 'Exact\n**draft** ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Edit tags' }))
    fireEvent.change(screen.getByLabelText('Tags, one per line'), { target: { value: 'Films\nWatch-later' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply tags' }))
    await screen.findByText('Films')
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(' Exact title ')
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe('Exact\n**draft** ')
    expect(screen.getByText('Unsaved changes')).toBeTruthy()
    expect(screen.queryByText(/changed on the server/)).toBeNull()
    expect(calls.filter(call => call.method === 'PUT')).toEqual([
      { method: 'PUT', path: `/api/notes/${id}/tags`, body: { tags: ['Films', 'Watch-later'] }, ifMatch: '"e1"' },
    ])
    expect(auth.queries.getQueryData(noteKeys.core(auth.session.viewerScope, id)))
      .toEqual({ value: tagged, etag: '"e2"' })
    expect(auth.queries.getQueryState(noteKeys.list(auth.session.viewerScope,
      { lifecycle: 'active', sort: 'updatedAtDesc' }))?.isInvalidated).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }))
    await screen.findByText('Saved')
    expect(calls.find(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)?.ifMatch).toBe('"e2"')
  })

  it.each([422, 503, 412])('preserves exact dirty draft after rejected tag command %s', async status => {
    const { calls } = mount(`/notes/${id}`, 'authenticated', (method, path) =>
      method === 'PUT' && path === `/api/notes/${id}/tags` ? problem(status, status === 412 ? 'stale_write' : 'invalid_input') : undefined)
    await screen.findByDisplayValue('Saved title', {}, { timeout: 5000 })
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' My title ' } })
    fireEvent.change(screen.getByLabelText('Markdown'), { target: { value: 'My\ndraft ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Edit tags' }))
    fireEvent.change(screen.getByLabelText('Tags, one per line'), { target: { value: 'Films' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply tags' }))
    if (status === 412) await screen.findByRole('heading', { name: 'Another version was saved' })
    else await screen.findByRole('alert')
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(' My title ')
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe('My\ndraft ')
    expect(screen.getByText('No tags')).toBeTruthy()
    expect(calls.filter(call => call.method === 'PUT')).toHaveLength(1)
  })

  it('never fetches private Notes for anonymous or pre-MFA visitors', async () => {
    for (const state of ['anonymous', 'mfaRequired'] as const) {
      const { calls } = mount('/notes', state)
      await screen.findByRole('heading', { name: state === 'anonymous' ? 'Welcome back' : 'Restart sign in' })
      expect(calls.some(call => call.path === '/api/notes')).toBe(false)
      cleanup()
    }
  })

  it('keeps new Note local until explicit Create and uses the chosen AI state', async () => {
    const { calls } = mount('/notes/new', 'authenticated', (method, path, body) =>
      method === 'POST' && path === '/api/notes'
        ? json({ ...initial, ...body as object }, 201,
          { ETag: '"e1"', Location: `/api/notes/${id}` }) : undefined)
    await screen.findByRole('heading', { name: 'Create a note' }, { timeout: 5000 })
    expect(screen.queryByRole('button', { name: 'Edit tags' })).toBeNull()
    expect(calls.filter(call => call.path === '/api/notes' && call.method === 'POST')).toHaveLength(0)
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Created' } })
    fireEvent.change(screen.getByLabelText('Markdown'), { target: { value: '**Content**' } })
    fireEvent.click(screen.getByLabelText('Use this note with AI'))
    expect(calls.filter(call => call.path === '/api/notes' && call.method === 'POST')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Create note' }))
    await waitFor(() => expect(window.location.pathname).toBe(`/notes/${id}`))
    expect(calls.find(call => call.path === '/api/notes' && call.method === 'POST')?.body)
      .toEqual({ title: 'Created', markdown: '**Content**', aiEnabled: true })
  })

  it('Ctrl+S saves explicitly and failure preserves the exact draft for retry', async () => {
    let saves = 0
    const { calls } = mount(`/notes/${id}`, 'authenticated', (method, path) => {
      if (method === 'PUT' && path === `/api/notes/${id}`) {
        saves++
        return saves === 1 ? problem(503, 'service_unavailable')
          : json({ ...initial, title: 'Local draft' }, 200, { ETag: '"e2"' })
      }
      return undefined
    })
    await screen.findByDisplayValue('Saved title', {}, { timeout: 5000 })
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Local draft' } })
    expect(screen.getByRole('status', { name: '' }).textContent).not.toBe('Saved')
    fireEvent.keyDown(document, { key: 's', ctrlKey: true })
    await screen.findByRole('button', { name: 'Retry save' })
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe('Local draft')
    fireEvent.click(screen.getByRole('button', { name: 'Retry save' }))
    await waitFor(() => expect(screen.getByText('Saved')).toBeTruthy())
    expect(calls.filter(call => call.path === `/api/notes/${id}` && call.method === 'PUT')).toHaveLength(2)
  })

  it('guards in-app navigation and preserves draft on a stale-write conflict', async () => {
    mount(`/notes/${id}`, 'authenticated', (method, path) =>
      method === 'PUT' && path === `/api/notes/${id}` ? problem(412, 'stale_write')
        : undefined)
    await screen.findByDisplayValue('Saved title', {}, { timeout: 5000 })
    fireEvent.change(screen.getByLabelText('Markdown'), { target: { value: 'Unsaved local text' } })
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    fireEvent.click(screen.getByRole('link', { name: 'All notes' }))
    await screen.findByRole('dialog', { name: 'Leave with unsaved changes?' })
    expect(window.location.pathname).toBe(`/notes/${id}`)
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }))
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe('Unsaved local text')
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }))
    await screen.findByRole('heading', { name: 'Another version was saved' })
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe('Unsaved local text')
  })

  it('keeps accumulated cursor pages in the one Query-owned Notes collection', async () => {
    const later = { ...initial, id: '01990a55-9e12-7ac4-8f5b-31aa4a91d402', title: 'Later note' }
    const { calls, auth } = mount('/notes', 'authenticated', (method, path) => {
      if (method !== 'GET') return undefined
      if (path === '/api/notes') return json({ items: [initial], nextCursor: 'next' })
      if (path === '/api/notes?cursor=next') return json({ items: [initial, later], nextCursor: null })
      return undefined
    })
    await screen.findByText('Saved title')
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))
    await screen.findByText('Later note')
    expect(screen.getAllByText('Saved title')).toHaveLength(1)
    expect(calls.filter(call => call.path.startsWith('/api/notes') && call.method === 'GET'))
      .toHaveLength(2)
    const collection = auth.queries.getQueryData<{ pages: unknown[] }>(noteKeys.list(
      auth.session.viewerScope, { lifecycle: 'active', sort: 'updatedAtDesc' }))
    expect(collection?.pages).toHaveLength(2)
  })

  it('invalidates the Notes collection after Save and refetches its changed core', async () => {
    let listReads = 0
    const updated = { ...initial, title: 'Updated title' }
    const { auth } = mount('/notes', 'authenticated', (method, path) => {
      if (method === 'GET' && path === '/api/notes') {
        listReads++
        return json({ items: [listReads === 1 ? initial : updated], nextCursor: null })
      }
      if (method === 'PUT' && path === `/api/notes/${id}`) {
        return json(updated, 200, { ETag: '"e2"' })
      }
      return undefined
    })
    await screen.findByText('Saved title')
    fireEvent.click(screen.getByRole('link', { name: /Saved title/ }))
    await screen.findByDisplayValue('Saved title', {}, { timeout: 5000 })
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Updated title' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }))
    await screen.findByText('Saved')
    await waitFor(() => expect(auth.queries.getQueryState(noteKeys.list(
      auth.session.viewerScope, { lifecycle: 'active', sort: 'updatedAtDesc' }))?.isInvalidated)
      .toBe(true))
    fireEvent.click(screen.getByRole('link', { name: 'All notes' }))
    await screen.findByText('Updated title')
    expect(listReads).toBe(2)
  })

  it('retries failed current-version GET after 412 without losing the draft or stale base', async () => {
    let reads = 0
    const { calls } = mount(`/notes/${id}`, 'authenticated', (method, path) => {
      if (method === 'GET' && path === `/api/notes/${id}`) {
        reads++
        if (reads === 2) return problem(503, 'service_unavailable')
        return reads === 1 ? json(initial, 200, { ETag: '"e1"' })
          : json({ ...initial, title: 'Server title' }, 200, { ETag: '"e2"' })
      }
      if (method === 'PUT' && path === `/api/notes/${id}`) return problem(412, 'stale_write')
      return undefined
    })
    await screen.findByDisplayValue('Saved title', {}, { timeout: 5000 })
    fireEvent.change(screen.getByLabelText('Markdown'), { target: { value: 'Exact local draft' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }))
    await screen.findByText('The current saved version could not be loaded. Your draft is unchanged.')
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe('Exact local draft')
    expect(screen.getByRole('button', { name: 'Save note' }).hasAttribute('disabled')).toBe(true)
    expect(screen.queryByRole('button', { name: /use current version as save base/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading saved version' }))
    await screen.findByText('Server title')
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe('Exact local draft')
    expect(calls.filter(call => call.method === 'PUT' && call.path === `/api/notes/${id}`)).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Keep my draft and use current version as save base' }))
    expect(screen.getByRole('button', { name: 'Save note' }).hasAttribute('disabled')).toBe(false)
  })
})
