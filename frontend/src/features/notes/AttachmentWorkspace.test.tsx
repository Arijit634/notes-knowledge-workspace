import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { App } from '../../app/App'
import { AuthRuntime } from '../auth/AuthRuntime'
import { AttachmentPanel } from './AttachmentPanel'
import { AttachmentMediaViewer } from './AttachmentMediaViewer'
import { AttachmentXhrDouble } from './AttachmentUpload.test-support'
import { noteKeys } from './NotesKeys'
import type { AttachmentCore } from './AttachmentsApi'

const noteId = '01990a55-9e12-7ac4-8f5b-31aa4a91d401', id = '01990a55-9e12-7ac4-8f5b-31aa4a91d402'
const file: AttachmentCore = { id, noteId, mediaKind: 'image', mediaType: 'image/png', displayFilename: 'synthetic.png',
  sizeBytes: 1024, width: 32, height: 24, pageCount: null, durationSeconds: null,
  storageState: 'stored', validationState: 'accepted', cleanupState: 'retained',
  createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' }
const json = (body: unknown, status = 200, headers: Record<string, string> = {}) => new Response(JSON.stringify(body),
  { status, headers: { 'Content-Type': 'application/json', ...headers } })
const problem = (status: number) => json({ status, type: 'about:blank', title: 'Safe', instance: '/api/notes',
  code: 'request_failed', traceId: `tr_${'a'.repeat(32)}` }, status, { 'Content-Type': 'application/problem+json' })
type Call = { method: string; path: string; init?: RequestInit }
function runtime(handler?: (call: Call) => Response | Promise<Response> | undefined, authenticated = true) {
  const calls: Call[] = [], uploads: AttachmentXhrDouble[] = []
  vi.stubGlobal('XMLHttpRequest', class extends AttachmentXhrDouble {
    constructor() { super(xhr => {
      uploads.push(xhr)
      const call: Call = { method: xhr.method, path: xhr.path, init: { body: xhr.body, headers: xhr.headers } }; calls.push(call)
      void Promise.resolve(handler?.(call) ?? problem(503)).then(response => xhr.respond(response))
    }) }
  })
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call = { method: init?.method ?? 'GET', path: String(input), init }; calls.push(call)
    const result = handler?.(call); if (result) return await result
    if (call.path === '/api/auth/session') return json({ state: 'authenticated' })
    if (call.path === '/api/auth/csrf') return json({ csrfToken: 'synthetic-proof' })
    if (call.path === `/api/notes/${noteId}/attachments` && call.method === 'GET') return json({ items: [file], nextCursor: null })
    if (call.path === `/api/notes/${noteId}/attachments/${id}` && call.method === 'GET') return json(file, 200, { ETag: '"a1"' })
    return problem(503)
  }))
  const auth = new AuthRuntime(); if (authenticated) auth.session.transition('authenticated'); auth.csrf.set('synthetic-proof')
  return { auth, calls, uploads }
}
function panel(handler?: Parameters<typeof runtime>[0], lifecycle = 'active') {
  const state = runtime(handler), modal = vi.fn()
  const view = render(<MemoryRouter><AttachmentPanel auth={state.auth} noteId={noteId} lifecycle={lifecycle} onModalChange={modal} /></MemoryRouter>)
  return { ...state, modal, view }
}
function pick(size = 1) {
  const selected = new File(['x'], 'synthetic.png', { type: 'image/png' })
  Object.defineProperty(selected, 'size', { value: size })
  fireEvent.change(screen.getByLabelText('Add file'), { target: { files: [selected] } })
  return selected
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); window.history.replaceState(null, '', '/') })
beforeAll(async () => { await import('./NotesPages') })

describe('Attachment panel', () => {
  it('shows loading, an empty state and safe labelled file input', async () => {
    let resolve!: (response: Response) => void
    panel(() => new Promise<Response>(done => { resolve = done }))
    expect(screen.getByText('Loading attachments…')).toBeTruthy()
    resolve(json({ items: [], nextCursor: null }))
    expect(await screen.findByText('No attachments yet.')).toBeTruthy()
    expect(screen.getByLabelText('Add file').getAttribute('accept')).toBe('.png,.jpg,.jpeg,.wav,.mp4,.pdf')
    expect(screen.getByLabelText('Add file').hasAttribute('multiple')).toBe(false)
  })
  it.each([404, 503])('contains list %s failure without claiming empty', async status => {
    panel(() => problem(status))
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.queryByText('No attachments yet.')).toBeNull()
    expect(screen.getByRole('button', { name: 'Retry files' })).toBeTruthy()
  })
  it('pages and deduplicates by ID using viewer-scoped keys without cursors', async () => {
    const state = panel(call => call.path.endsWith('?cursor=opaque') ? json({ items: [file, { ...file, id: 'other', displayFilename: 'second.png' }], nextCursor: null })
      : call.path.endsWith('/attachments') ? json({ items: [file], nextCursor: 'opaque' }) : undefined)
    await screen.findByText('synthetic.png', { selector: 'strong' }); fireEvent.click(screen.getByRole('button', { name: 'Load more files' }))
    await screen.findByText('second.png', { selector: 'strong' }); expect(screen.getAllByText('synthetic.png', { selector: 'strong' })).toHaveLength(1)
    const keys = state.auth.queries.getQueryCache().getAll().map(query => query.queryKey)
    expect(keys).toEqual([noteKeys.attachments(state.auth.session.viewerScope, noteId)])
  })
  it.each(['active', 'archived'])('uploads one file for %s and refreshes only Attachment state', async lifecycle => {
    let uploaded = false
    const state = panel(call => {
      if (call.method === 'POST') { uploaded = true; return json(file, 201, { ETag: '"a1"', Location: `/api/notes/${noteId}/attachments/${id}` }) }
      if (call.path.endsWith('/attachments')) return json({ items: uploaded ? [file] : [], nextCursor: null })
    }, lifecycle)
    await screen.findByText('No attachments yet.'); const selected = pick()
    await screen.findByText('File added.'); await screen.findByText('synthetic.png', { selector: 'strong' })
    const upload = state.calls.find(call => call.method === 'POST')!
    expect(Array.from((upload.init!.body as FormData).keys())).toEqual(['file'])
    expect((upload.init!.body as FormData).get('file')).toBe(selected)
    expect(new Headers(upload.init?.headers).has('Content-Type')).toBe(false)
    expect((screen.getByLabelText('Add file') as HTMLInputElement).value).toBe('')
    expect(state.calls.some(call => call.method === 'PUT')).toBe(false)
  })
  it.each([201, 503])('keeps files visible and upload disabled until the pending request resolves with %s', async status => {
    let resolve!: (response: Response) => void
    let uploaded = false
    const added = { ...file, id: 'second', displayFilename: 'second.png' }
    const state = panel(call => {
      if (call.method === 'POST') return new Promise<Response>(done => { resolve = done })
      if (call.path.endsWith('/attachments')) return json({ items: [file, ...(uploaded ? [added] : [])], nextCursor: null })
    })
    await screen.findByText('synthetic.png', { selector: 'strong' }); pick()
    expect(screen.getByText('synthetic.png', { selector: 'strong' })).toBeTruthy(); expect(screen.getByLabelText('Add file').hasAttribute('disabled')).toBe(true)
    await waitFor(() => expect(resolve).toBeTypeOf('function'))
    expect(screen.getByText('Uploading…')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /cancel upload/i })).toBeNull()
    expect(screen.queryByText('Upload cancelled.')).toBeNull()
    expect(state.uploads[0].abort).not.toHaveBeenCalled()
    uploaded = status === 201
    resolve(status === 201 ? json(added, 201, { ETag: '"a2"', Location: `/api/notes/${noteId}/attachments/second` }) : problem(status))
    if (status === 201) {
      await screen.findByText('File added.'); await screen.findByText('second.png', { selector: 'strong' })
    } else {
      expect((await screen.findByRole('alert')).textContent).toBe('File storage is temporarily unavailable. Try again later.')
      expect(screen.queryByText('File added.')).toBeNull()
    }
    expect(screen.getByText('synthetic.png', { selector: 'strong' })).toBeTruthy()
    await waitFor(() => expect(screen.getByLabelText('Add file').hasAttribute('disabled')).toBe(false))
    expect(state.calls.filter(call => call.method === 'POST')).toHaveLength(1)
    expect(screen.queryByText('Uploading…')).toBeNull()
  })
  it('aborts the client task on unmount and ignores a late successful response', async () => {
    let resolve!: (response: Response) => void
    const state = panel(call => call.method === 'POST' ? new Promise<Response>(done => { resolve = done }) : undefined)
    await screen.findByText('synthetic.png', { selector: 'strong' }); pick()
    await waitFor(() => expect(resolve).toBeTypeOf('function'))
    const xhr = state.uploads[0]
    const scope = state.auth.session.viewerScope
    state.view.unmount()
    expect(xhr.abort).toHaveBeenCalledTimes(1)
    await act(async () => { resolve(json(file, 201, { ETag: '"a1"', Location: `/api/notes/${noteId}/attachments/${id}` })) })
    expect(state.auth.queries.getQueryData(noteKeys.attachment(scope, noteId, id))).toBeUndefined()
    expect(state.calls.filter(call => call.method === 'GET' && call.path.endsWith('/attachments'))).toHaveLength(1)
    expect(screen.queryByText('File added.')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })
  it('shows real upload progress then validation without inventing percentages', async () => {
    let resolve!: (response: Response) => void
    const state = panel(call => call.method === 'POST' ? new Promise<Response>(done => { resolve = done }) : undefined)
    await screen.findByText('synthetic.png', { selector: 'strong' }); pick()
    await waitFor(() => expect(state.uploads).toHaveLength(1))
    expect(screen.getByRole('progressbar').hasAttribute('value')).toBe(false)
    act(() => state.uploads[0].progress(42, 100))
    expect(screen.getByText('Uploading… 42%')).toBeTruthy()
    expect(screen.getByRole('progressbar').getAttribute('value')).toBe('42')
    act(() => state.uploads[0].uploaded())
    expect(screen.getByText('Validating file…')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Stop upload' })).toBeNull()
    resolve(json(file, 201, { ETag: '"a1"', Location: `/api/notes/${noteId}/attachments/${id}` })); await screen.findByText('File added.')
  })
  it('stops only the client upload and refetches files that may have committed on the server', async () => {
    let resolve!: (response: Response) => void, committed = false
    const state = panel(call => {
      if (call.method === 'POST') return new Promise<Response>(done => { resolve = done })
      if (call.path.endsWith('/attachments')) return json({ items: committed ? [file, { ...file, id: 'late', displayFilename: 'late.png' }] : [file], nextCursor: null })
    })
    await screen.findByText('synthetic.png', { selector: 'strong' }); pick()
    await waitFor(() => expect(state.uploads).toHaveLength(1)); committed = true
    fireEvent.click(screen.getByRole('button', { name: 'Stop upload' }))
    await screen.findByText('Upload stopped in this tab. Refresh files if it had already finished on the server.')
    await screen.findByText('late.png', { selector: 'strong' })
    expect(state.uploads[0].abort).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('Upload cancelled.')).toBeNull()
    expect(screen.queryByText('File added.')).toBeNull()
    await act(async () => { resolve(json(file, 201)) })
    expect(screen.queryByText('File added.')).toBeNull()
    await waitFor(() => expect(screen.getByLabelText('Add file').hasAttribute('disabled')).toBe(false))
    expect(state.calls.some(call => call.method === 'PUT')).toBe(false)
  })
  it('disables upload in Trash but preserves Open and Remove', async () => {
    panel(undefined, 'trashed'); await screen.findByText('synthetic.png', { selector: 'strong' })
    expect(screen.getByLabelText('Add file').hasAttribute('disabled')).toBe(true)
    expect(screen.getByRole('button', { name: 'Open synthetic.png' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Remove synthetic.png' }).hasAttribute('disabled')).toBe(false)
  })
  it('rejects files above 25 MiB without dispatch', async () => {
    const state = panel(); await screen.findByText('synthetic.png', { selector: 'strong' }); pick(25 * 1024 * 1024 + 1)
    expect(screen.getByRole('alert').textContent).toContain('too large')
    expect(state.calls.some(call => call.method === 'POST')).toBe(false)
  })
  it.each([413, 415, 422, 429, 503])('contains upload %s and resets the input without retry', async status => {
    const state = panel(call => call.method === 'POST' ? problem(status) : undefined)
    await screen.findByText('synthetic.png', { selector: 'strong' }); pick(); await screen.findByRole('alert')
    expect(state.calls.filter(call => call.method === 'POST')).toHaveLength(1)
    expect((screen.getByLabelText('Add file') as HTMLInputElement).value).toBe('')
  })
  it('fetches current detail before confirmation, traps focus and cancels without DELETE', async () => {
    const state = panel(); const invoker = await screen.findByRole('button', { name: 'Remove synthetic.png' })
    fireEvent.click(invoker); const dialog = await screen.findByRole('dialog', { name: 'Remove attachment?' })
    expect(state.calls.filter(call => call.path.endsWith(`/${id}`))).toHaveLength(1)
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Keep file' }))
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Remove file' }))
    fireEvent.keyDown(dialog, { key: 'Escape' }); await waitFor(() => expect(document.activeElement).toBe(invoker))
    expect(state.calls.some(call => call.method === 'DELETE')).toBe(false)
  })
  it.each([204, 404])('converges removal %s using Attachment ETag, including Trash', async status => {
    let removed = false
    const state = panel(call => {
      if (call.method === 'DELETE') { removed = true; return status === 204 ? new Response(null, { status }) : problem(status) }
      if (removed && call.path.endsWith('/attachments')) return json({ items: [], nextCursor: null })
    }, 'trashed')
    fireEvent.click(await screen.findByRole('button', { name: 'Remove synthetic.png' }))
    fireEvent.click(await screen.findByRole('button', { name: /^Remove file$/ }))
    await screen.findByText('No attachments yet.')
    expect(new Headers(state.calls.find(call => call.method === 'DELETE')!.init?.headers).get('If-Match')).toBe('"a1"')
    expect(screen.queryByRole('dialog')).toBeNull()
  })
  it('keeps focus inside the busy confirmation and does not dismiss a pending DELETE', async () => {
    let resolve!: (response: Response) => void, removed = false
    panel(call => {
      if (call.method === 'DELETE') { removed = true; return new Promise<Response>(done => { resolve = done }) }
      if (removed && call.path.endsWith('/attachments')) return json({ items: [], nextCursor: null })
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Remove synthetic.png' }))
    fireEvent.click(await screen.findByRole('button', { name: /^Remove file$/ }))
    const dialog = screen.getByRole('dialog')
    await waitFor(() => expect(resolve).toBeTypeOf('function'))
    expect(document.activeElement).toBe(dialog)
    fireEvent.keyDown(dialog, { key: 'Tab' }); expect(document.activeElement).toBe(dialog)
    fireEvent.keyDown(dialog, { key: 'Escape' }); expect(screen.getByRole('dialog')).toBe(dialog)
    resolve(new Response(null, { status: 204 })); await screen.findByText('File removed.')
  })
  it.each([false, true])('does not replay 412, and converges if refreshed detail is unavailable=%s', async unavailable => {
    let rejected = false
    const state = panel(call => {
      if (call.method === 'DELETE') { rejected = true; return problem(412) }
      if (rejected && call.path.endsWith(`/${id}`)) return unavailable ? problem(404) : json(file, 200, { ETag: '"a2"' })
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Remove synthetic.png' }))
    fireEvent.click(await screen.findByRole('button', { name: /^Remove file$/ }))
    await screen.findByText(unavailable ? 'That attachment is no longer available.' : 'This attachment changed. Review it before removing.')
    expect(state.calls.filter(call => call.method === 'DELETE')).toHaveLength(1)
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('Attachment viewer and editor integration', () => {
  it('leaves Attachment commands independent of a text Save conflict, including failed conflict refetch', async () => {
    window.history.replaceState(null, '', `/notes/${noteId}`)
    let conflict = false
    const note = { id: noteId, title: 'Saved', markdown: 'Saved body', lifecycle: 'active', pinned: false,
      tags: [], aiEnabled: false, createdAt: file.createdAt, updatedAt: file.updatedAt }
    const { auth, calls } = runtime(call => {
      if (call.path === `/api/notes/${noteId}`) {
        if (call.method === 'PUT') { conflict = true; return problem(412) }
        return conflict ? problem(503) : json(note, 200, { ETag: '"n1"' })
      }
      if (call.method === 'POST') return json(file, 201, { ETag: '"a1"', Location: `/api/notes/${noteId}/attachments/${id}` })
    })
    render(<App auth={auth} />)
    const body = await screen.findByLabelText('Markdown', { exact: true })
    fireEvent.change(body, { target: { value: 'Exact conflicted draft' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }))
    await screen.findByText('The current saved version could not be loaded. Your draft is unchanged.')
    expect(screen.getByLabelText('Add file').hasAttribute('disabled')).toBe(false)
    pick(); await screen.findByText('File added.')
    expect((body as HTMLTextAreaElement).value).toBe('Exact conflicted draft')
    expect(screen.getByRole('region', { name: 'Save conflict' })).toBeTruthy()
    expect(calls.filter(call => call.method === 'PUT')).toHaveLength(1)
  })
  it.each([404, 503])('keeps viewer %s contained without loading media bytes', async status => {
    const { auth } = runtime(() => problem(status))
    const view = render(<MemoryRouter><AttachmentMediaViewer auth={auth} noteId={noteId} attachmentId={id} /></MemoryRouter>)
    expect(screen.getByText('Loading attachment…')).toBeTruthy()
    await screen.findByRole('alert'); expect(view.container.querySelector('img,audio,video,iframe,object,embed')).toBeNull()
  })
  it('does not expose an upload tool before a new Note exists', async () => {
    window.history.replaceState(null, '', '/notes/new')
    const { auth, calls } = runtime(call => call.path === '/api/me/note-preferences' ? json({ defaultAiEnabledForNewNotes: false }) : undefined)
    render(<App auth={auth} />); await screen.findByLabelText('Title', { exact: true })
    expect(screen.queryByLabelText('Add file')).toBeNull()
    expect(calls.some(call => call.path.includes('/attachments'))).toBe(false)
  })
  it('evicts Attachment metadata on full authority replacement and ignores a late upload response', async () => {
    let resolve!: (response: Response) => void
    const state = panel(call => call.method === 'POST' ? new Promise<Response>(done => { resolve = done }) : undefined)
    await screen.findByRole('button', { name: 'Open synthetic.png' }); pick()
    await waitFor(() => expect(resolve).toBeTypeOf('function'))
    const oldScope = state.auth.session.viewerScope
    state.auth.session.transition('anonymous')
    resolve(json(file, 201, { ETag: '"a1"', Location: `/api/notes/${noteId}/attachments/${id}` }))
    await waitFor(() => expect(state.auth.queries.getQueryData(noteKeys.attachment(oldScope, noteId, id))).toBeUndefined())
    expect(screen.queryByText('File added.')).toBeNull()
  })
  it.each([['image', 'image/png', 'img'], ['audio', 'audio/wav', 'audio'], ['video', 'video/mp4', 'video']])(
    'renders %s only from authenticated backend content', async (mediaKind, mediaType, tag) => {
      const { auth } = runtime(call => call.path.endsWith(`/${id}`) ? json({ ...file, mediaKind, mediaType }, 200, { ETag: '"a1"' }) : undefined)
      const view = render(<MemoryRouter><AttachmentMediaViewer auth={auth} noteId={noteId} attachmentId={id} /></MemoryRouter>)
      await screen.findByRole('heading', { name: 'synthetic.png' })
      const media = view.container.querySelector(tag)!
      expect(media.getAttribute('src')).toBe(`/api/notes/${noteId}/attachments/${id}/content`)
      if (tag === 'audio' || tag === 'video') { expect(media.hasAttribute('controls')).toBe(true); expect(media.getAttribute('preload')).toBe('metadata') }
      fireEvent.error(media); expect((await screen.findByRole('alert')).textContent).toBe('This attachment could not be loaded.')
    })
  it('offers PDF metadata and a safe top-level private content link without embedding or fetching bytes', async () => {
    const { auth, calls } = runtime(call => call.path.endsWith(`/${id}`)
      ? json({ ...file, mediaKind: 'pdf', mediaType: 'application/pdf', displayFilename: 'synthetic.pdf', pageCount: 3, width: null, height: null }, 200, { ETag: '"a1"' }) : undefined)
    const view = render(<MemoryRouter><AttachmentMediaViewer auth={auth} noteId={noteId} attachmentId={id} /></MemoryRouter>)
    await screen.findByRole('heading', { name: 'synthetic.pdf' })
    expect(screen.getByText('PDF · 1.0 KB · 3 pages')).toBeTruthy()
    expect(view.container.querySelector('iframe,object,embed')).toBeNull()
    const open = screen.getByRole('link', { name: 'Open PDF' })
    expect(open.getAttribute('href')).toBe(`/api/notes/${encodeURIComponent(noteId)}/attachments/${encodeURIComponent(id)}/content`)
    expect(open.getAttribute('target')).toBe('_blank')
    expect(open.getAttribute('rel')).toBe('noopener noreferrer')
    expect(calls.some(call => call.path.endsWith('/content'))).toBe(false)
  })
  it.each(['anonymous', 'mfaRequired'] as const)('gates viewer for %s before any Attachment fetch', async state => {
    window.history.replaceState(null, '', `/notes/${noteId}`)
    const { auth, calls } = runtime(call => call.path === '/api/auth/session' ? json({ state }) : undefined, false)
    render(<App auth={auth} />)
    await waitFor(() => expect(auth.state).toBe(state))
    expect(calls.some(call => call.path.includes('/attachments'))).toBe(false)
  })
  it('preserves exact dirty title/body and Note ETag through upload, modal viewing and removal', async () => {
    window.history.replaceState(null, '', `/notes/${noteId}`)
    const note = { id: noteId, title: 'Saved', markdown: 'Saved body', lifecycle: 'active', pinned: false,
      tags: [], aiEnabled: false, createdAt: file.createdAt, updatedAt: file.updatedAt }
    let removed = false
    const { auth, calls } = runtime(call => {
      if (call.path === `/api/notes/${noteId}`) return json(note, 200, { ETag: '"n1"' })
      if (call.method === 'POST') return json(file, 201, { ETag: '"a1"', Location: `/api/notes/${noteId}/attachments/${id}` })
      if (call.method === 'DELETE') { removed = true; return new Response(null, { status: 204 }) }
      if (removed && call.path.endsWith('/attachments')) return json({ items: [], nextCursor: null })
    })
    render(<App auth={auth} />)
    const title = await screen.findByLabelText('Title', { exact: true }), body = screen.getByLabelText('Markdown', { exact: true })
    await waitFor(() => expect((title as HTMLInputElement).value).toBe('Saved'))
    fireEvent.change(title, { target: { value: ' Exact dirty title ' } }); fireEvent.change(body, { target: { value: ' Exact draft\n ' } })
    pick(); await screen.findByText('File added.')
    fireEvent.click(screen.getByRole('button', { name: 'Open synthetic.png' }))
    const viewer = await screen.findByRole('dialog', { name: 'synthetic.png' })
    expect(window.location.pathname).toBe(`/notes/${noteId}`)
    expect(screen.queryByRole('dialog', { name: 'Leave with unsaved changes?' })).toBeNull()
    expect((title as HTMLInputElement).value).toBe(' Exact dirty title ')
    expect((body as HTMLTextAreaElement).value).toBe(' Exact draft\n ')
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close attachment' }))
    fireEvent.keyDown(document, { ctrlKey: true, key: 's' })
    expect(calls.some(call => call.method === 'PUT')).toBe(false)
    expect(title.closest('[inert]')).toBeTruthy()
    fireEvent.keyDown(viewer, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'Remove synthetic.png' }))
    await screen.findByRole('dialog', { name: 'Remove attachment?' })
    fireEvent.keyDown(document, { ctrlKey: true, key: 's' })
    fireEvent.click(screen.getByRole('button', { name: /^Remove file$/ }))
    await screen.findByText('File removed.')
    expect((title as HTMLInputElement).value).toBe(' Exact dirty title '); expect((body as HTMLTextAreaElement).value).toBe(' Exact draft\n ')
    expect(auth.queries.getQueryData(noteKeys.core(auth.session.viewerScope, noteId))).toEqual({ value: note, etag: '"n1"' })
    expect(calls.some(call => call.method === 'PUT')).toBe(false)
    expect(screen.getByText('Unsaved changes')).toBeTruthy()
  })
})
