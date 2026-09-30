import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
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

function mount(path: string, state: 'authenticated' | 'anonymous' | 'mfaRequired' = 'authenticated',
  onRequest?: (method: string, path: string, body: unknown) => Response | undefined) {
  window.history.replaceState(null, '', path)
  const calls: Array<{ method: string; path: string; body: unknown }> = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const target = String(input), method = init?.method || 'GET'
    const body: unknown = init?.body ? JSON.parse(String(init.body)) : null
    calls.push({ method, path: target, body })
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

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); window.history.replaceState(null, '', '/') })

describe('private Notes browser journey', () => {
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
