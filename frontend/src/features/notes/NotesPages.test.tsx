import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../app/App'
import { AuthRuntime } from '../auth/AuthRuntime'
import type { NoteCore } from './NotesApi'

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
  onRequest?: (method: string, path: string, body: unknown) => Response) {
  window.history.replaceState(null, '', path)
  const calls: Array<{ method: string; path: string; body: unknown }> = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const target = String(input), method = init?.method || 'GET'
    const body: unknown = init?.body ? JSON.parse(String(init.body)) : null
    calls.push({ method, path: target, body })
    if (target === '/api/auth/session') return json({ state })
    if (target === '/api/auth/csrf') return json({ csrfToken: 'synthetic-proof' })
    if (target === '/api/me/note-preferences') return json({ defaultAiEnabledForNewNotes: false })
    if (target === `/api/notes/${id}` && method === 'GET') return json(initial, 200, { ETag: '"e1"' })
    if (target === '/api/notes' && method === 'GET') return json({ items: [], nextCursor: null })
    return onRequest?.(method, target, body) ?? problem(503, 'service_unavailable')
  }))
  render(<App auth={new AuthRuntime()} />)
  return calls
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); window.history.replaceState(null, '', '/') })

describe('private Notes browser journey', () => {
  it('never fetches private Notes for anonymous or pre-MFA visitors', async () => {
    for (const state of ['anonymous', 'mfaRequired'] as const) {
      const calls = mount('/notes', state)
      await screen.findByRole('heading', { name: state === 'anonymous' ? 'Welcome back' : 'Restart sign in' })
      expect(calls.some(call => call.path === '/api/notes')).toBe(false)
      cleanup()
    }
  })

  it('keeps new Note local until explicit Create and uses the chosen AI state', async () => {
    const calls = mount('/notes/new', 'authenticated', (method, path, body) =>
      method === 'POST' && path === '/api/notes'
        ? json({ ...initial, ...body as object }, 201,
          { ETag: '"e1"', Location: `/api/notes/${id}` }) : problem(503, 'service_unavailable'))
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
    const calls = mount(`/notes/${id}`, 'authenticated', (method, path) => {
      if (method === 'PUT' && path === `/api/notes/${id}`) {
        saves++
        return saves === 1 ? problem(503, 'service_unavailable')
          : json({ ...initial, title: 'Local draft' }, 200, { ETag: '"e2"' })
      }
      return problem(503, 'service_unavailable')
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
        : problem(503, 'service_unavailable'))
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
})
