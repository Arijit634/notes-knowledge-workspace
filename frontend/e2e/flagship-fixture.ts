import { expect, type Page } from '@playwright/test'

export const noteId = '01990a55-9e12-7ac4-8f5b-31aa4a91d401'
export const attachmentId = '01990a55-9e12-7ac4-8f5b-31aa4a91d402'
export const operationId = `v1.${'A'.repeat(94)}`
export const timestamp = '2026-10-20T00:00:00Z'
const sourceLocation = { kind: 'note_text', start: 0, end: 12, page: null, timeStart: null, timeEnd: null, x: null, y: null, width: null, height: null }
export const answer = { deterministicResults: [{ kind: 'saved_detail', value: 'A deterministic detail from an AI-OFF note.', aiEnabled: false, occurrences: [{ noteId, attachmentId: null, location: sourceLocation }] }],
  aiAnswer: { claims: ['A grounded synthetic answer from eligible evidence.'], conflicting: false }, citations: [{ noteId, attachmentId: null, location: sourceLocation }],
  coverage: { boundary: 'acceptedAiSources', inspectedSources: 4, completed: false, corpusChanged: false, truncated: true, retryRequired: false, classificationUncertain: true }, degraded: null, insufficientEvidence: false }
export async function flagshipBackend(page: Page) {
  const submittedAt = new Date().toISOString()
  const state = {
    note: { id: noteId, title: 'A quieter morning', markdown: '## Before the day begins\n\nMake room for a thought worth keeping.\n\n- [ ] Read a chapter\n- [x] Take a walk\n\n> Small things count.\n', lifecycle: 'active', pinned: true, tags: ['Personal', 'Routines'], aiEnabled: false, createdAt: timestamp, updatedAt: timestamp },
    revision: 1, failSave: 0, holdSave: false, releaseSave: () => {}, saveStarted: false, asyncAsk: false, operationDone: false,
    profile: { displayName: 'Synthetic writer', biography: 'A fictional profile for browser qualification.', handle: null as string | null, updatedAt: null as string | null, avatar: null as null | { mediaType: string; width: number; height: number; byteSize: number; createdAt: string } },
    calls: [] as { method: string; path: string; body: unknown; ifMatch?: string }[],
  }
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method()
    if (!path.startsWith('/api/')) { await route.continue(); return }
    const multipart = request.headers()['content-type']?.startsWith('multipart/form-data')
    const input = request.postData() && !multipart ? request.postDataJSON() : null
    state.calls.push({ method, path, body: input, ifMatch: request.headers()['if-match'] })
    if (!['GET', 'HEAD'].includes(method)) expect(request.headers()['x-csrf-token']).toBe('synthetic-proof')
    let status = 200, body: unknown = {}, headers: Record<string, string> = { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }
    if (path === '/api/auth/session') body = { state: 'authenticated' }
    else if (path === '/api/auth/csrf') body = { csrfToken: 'synthetic-proof' }
    else if (path === '/api/me/note-preferences') body = { defaultAiEnabledForNewNotes: false }
    else if (path === '/api/knowledge/history' && method === 'GET') body = { items: [], nextCursor: null }
    else if (path === '/api/notes' && method === 'GET') body = { items: url.searchParams.get('lifecycle') ? [] : [state.note,
      { ...state.note, id: '01990a55-9e12-7ac4-8f5b-31aa4a91d403', title: 'Ideas worth coming back to', pinned: false, tags: ['Ideas'], markdown: 'An ongoing collection of small ideas. Some need time; some just need a first step.' }], nextCursor: null }
    else if (path === '/api/notes' && method === 'POST') { state.note = { ...state.note, ...input, pinned: false }; status = 201; body = state.note; headers.ETag = `"n${state.revision}"`; headers.Location = `/api/notes/${noteId}` }
    else if (path === `/api/notes/${noteId}`) {
      if (method === 'PUT') {
        state.saveStarted = true
        if (state.holdSave) await new Promise<void>(resolve => { state.releaseSave = resolve })
        if (state.failSave) status = state.failSave
        else if (request.headers()['if-match'] !== `"n${state.revision}"`) status = 412
        else { state.note = { ...state.note, ...input }; state.revision++ }
      }
      body = state.note; headers.ETag = `"n${state.revision}"`
    } else if (path.endsWith('/attachments') || path.endsWith('/versions')) body = { items: [], nextCursor: null }
    else if (path === '/api/notes/search') body = { items: [{ id: noteId, title: state.note.title, snippet: 'A matching saved detail.', lifecycle: 'active', pinned: true, tags: ['Personal'], updatedAt: timestamp, matchLabels: ['body'] }], nextCursor: null }
    else if (path === '/api/knowledge/query') {
      if (state.asyncAsk) { status = 202; body = { operationId, status: 'queued', submittedAt, result: null }; headers.Location = `/api/knowledge/operations/${operationId}` }
      else body = answer
    } else if (path === `/api/knowledge/operations/${operationId}`) body = { operationId, status: state.operationDone ? 'completed' : 'running', submittedAt, result: state.operationDone ? answer : null }
    else if (path === '/api/me/profile') { if (method === 'PUT') state.profile = { ...state.profile, ...input, updatedAt: timestamp }; body = state.profile }
    else if (path === '/api/me/profile/avatar') {
      if (method === 'PUT') { expect(multipart).toBe(true); state.profile.avatar = { mediaType: 'image/png', width: 1, height: 1, byteSize: 68, createdAt: timestamp }; body = state.profile }
      else { state.profile.avatar = null; status = 204 }
    } else if (path === '/api/ai/processing-policy') body = { processingPolicyId: noteId, policyCode: 'synthetic', policyVersion: 1, policyFingerprint: 'a'.repeat(64), disclosureRevision: 'synthetic-v1', effectiveAt: timestamp, acknowledged: false, acknowledgementRequired: true }
    else if (path.endsWith('/ai-processing')) body = { note: { status: 'excluded', reason: 'aiDisabled' }, attachments: [] }
    else throw new Error(`Unhandled synthetic API contract: ${method} ${path}`)
    if (status >= 400) { headers['Content-Type'] = 'application/problem+json'; body = { type: 'about:blank', title: 'Request rejected', status, code: status === 412 ? 'stale_write' : 'service_unavailable', instance: path, traceId: `tr_${'a'.repeat(32)}` } }
    await route.fulfill({ status, headers, ...(status === 204 ? {} : { body: JSON.stringify(body) }) })
  })
  return state
}
