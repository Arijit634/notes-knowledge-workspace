import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { AuthRuntime } from '../features/auth/AuthRuntime'
import { knowledgeResult, operation } from '../features/knowledge/KnowledgeApi'
import { safeDestination } from '../features/auth/SafeDestination'
import { attachmentPreflight } from '../features/notes/AttachmentAcceptance'

const id = '01990a55-9e12-7ac4-8f5b-31aa4a91d401'
const handle = `v1.${'A'.repeat(94)}`
const location = { kind: 'note_text', start: 0, end: 12, page: null, timeStart: null, timeEnd: null, x: null, y: null, width: null, height: null }
const result = { deterministicResults: [{ kind: 'url', value: 'https://example.test/saved', aiEnabled: false, occurrences: [{ noteId: id, attachmentId: null, location }] }],
  aiAnswer: { claims: ['A grounded synthetic answer.'], conflicting: false }, citations: [{ noteId: id, attachmentId: null, location }],
  coverage: { boundary: 'acceptedAiSources', inspectedSources: 2, completed: false, corpusChanged: false, truncated: true, retryRequired: false, classificationUncertain: true },
  degraded: null, insufficientEvidence: false }
const policy = { processingPolicyId: id, policyCode: 'synthetic', policyVersion: 1, policyFingerprint: 'a'.repeat(64), disclosureRevision: 'synthetic-v1', effectiveAt: '2026-10-20T00:00:00Z', acknowledged: false, acknowledgementRequired: true, disclosureContent: 'SYNTHETIC DISCLOSURE FIXTURE ONLY. No network processing.', providerProcessingCategories: ['groundedGeneration', 'mediaUnderstanding', 'queryEmbedding', 'sourceEmbedding', 'structuredExtraction'] }
const profile = { displayName: 'Synthetic writer', biography: 'A private profile.', handle: null, avatar: null, updatedAt: null }
const json = (body: unknown, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
const problem = (status: number) => json({ type: 'about:blank', status, title: 'Unavailable', code: 'service_unavailable', instance: '/api/knowledge/query', traceId: `tr_${'a'.repeat(32)}` }, status, { 'Content-Type': 'application/problem+json' })
type Call = { method: string; path: string; body: unknown; headers: Headers; signal?: AbortSignal | null }
function mount(path: string, handler: (call: Call) => Response | Promise<Response> | undefined = () => undefined, initial = 'authenticated') {
  window.history.replaceState(null, '', path)
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
    const call = { method: init?.method ?? 'GET', path: String(path), body: typeof init?.body === 'string' ? JSON.parse(init.body) as unknown : init?.body, headers: new Headers(init?.headers), signal: init?.signal }
    calls.push(call)
    if (call.path === '/api/auth/session') return json({ state: initial })
    if (call.path === '/api/auth/csrf') return json({ csrfToken: 'synthetic-csrf' })
    const response = await handler(call); if (response) return response
    if (call.path === '/api/me/note-preferences') return json({ defaultAiEnabledForNewNotes: false })
    if (call.path === '/api/ai/processing-policy') return json(policy)
    if (call.path === '/api/me/profile') return json(profile)
    if (call.path === '/api/notes') return json({ items: [], nextCursor: null })
    return problem(503)
  }))
  const auth = new AuthRuntime(); render(<App auth={auth} />); return { auth, calls }
}
beforeAll(async () => { await Promise.all([import('../features/knowledge/AskKnowledgePage'), import('../features/knowledge/PrivacySettingsPage'), import('../features/profile/ProfileSettingsPage'), import('../features/notes/NotesPages'), import('../features/notes/MarkdownEditorAdapter')]) }, 60_000)
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/') })

describe('real private-workspace transport contracts', () => {
  it('renders the AI answer and a whole-Note deterministic match without inventing a location', async () => {
    mount('/ask', c => c.path === '/api/knowledge/query' ? json({ ...result,
      deterministicResults: [{ kind: 'note', value: 'Synthetic whole-Note match', aiEnabled: false,
        occurrences: [{ noteId: id, attachmentId: null, location: null }] }] }) : undefined)
    fireEvent.change(await screen.findByLabelText('What would you like to find?'), { target: { value: 'Find a saved fact' } })
    fireEvent.submit(screen.getByLabelText('What would you like to find?').closest('form')!)
    expect((await screen.findByRole('region', { name: 'AI answer' })).textContent).toContain('A grounded synthetic answer.')
    const matches = screen.getByRole('region', { name: 'Deterministic results' })
    expect(matches.textContent).toContain('Synthetic whole-Note match')
    expect(matches.textContent).toContain('AI OFF · deterministic only')
    expect(within(matches).getByRole('link', { name: 'Source 1 · Note' }).getAttribute('href')).toBe(`/notes/${id}`)
    expect(matches.textContent).not.toContain('undefined')
  })
  it('explains unavailable AI prerequisites without blocking ordinary search or claiming provider readiness', async () => {
    const { calls } = mount('/ask', call => call.path === '/api/ai/processing-policy' ? problem(503) : undefined)
    await screen.findByText(/AI setup could not be verified/)
    expect(screen.getByRole('link', { name: 'Review AI & Privacy' }).getAttribute('href')).toBe('/settings/privacy')
    fireEvent.click(screen.getByRole('button', { name: 'Ordinary search' }))
    expect(await screen.findByLabelText('Search saved notes')).toBeTruthy()
    expect(calls.some(call => call.path === '/api/knowledge/query')).toBe(false)
  })
  it.each(['insufficient', 'degraded'])('presents %s evidence without inventing an AI answer', async outcome => {
    mount('/ask', c => c.path === '/api/knowledge/query' ? json({ ...result, aiAnswer: null, citations: [],
      insufficientEvidence: outcome === 'insufficient', degraded: outcome === 'degraded' ? 'providerUnavailable' : null }) : undefined)
    fireEvent.change(await screen.findByLabelText('What would you like to find?'), { target: { value: 'Find a saved fact' } })
    fireEvent.submit(screen.getByLabelText('What would you like to find?').closest('form')!)
    await screen.findByText(outcome === 'insufficient' ? /not enough eligible evidence/ : /AI is degraded or unavailable/)
    expect(screen.queryByRole('region', { name: 'AI answer' })).toBeNull()
    expect(screen.getByText('AI OFF · deterministic only')).toBeTruthy()
  })
  it('stops an obsolete operation without showing old evidence or automatically resubmitting', async () => {
    const accepted = { operationId: handle, status: 'queued', submittedAt: '2026-10-20T00:00:00Z', result: null }
    const { calls } = mount('/ask', c => c.path === '/api/knowledge/query' ? json(accepted, 202, { Location: `/api/knowledge/operations/${handle}` })
      : c.path === `/api/knowledge/operations/${handle}` ? json({ ...accepted, status: 'obsolete' }) : undefined)
    fireEvent.change(await screen.findByLabelText('What would you like to find?'), { target: { value: 'A request whose eligibility changed' } })
    fireEvent.submit(screen.getByLabelText('What would you like to find?').closest('form')!)
    await screen.findByText('This request is no longer current. Submit again if needed.')
    expect(screen.queryByRole('region', { name: 'Knowledge results' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Cancel request' })).toBeNull()
    expect(calls.filter(c => c.path === '/api/knowledge/query')).toHaveLength(1)
  })
  it('uploads and removes an avatar through multipart without overwriting a dirty Profile form', async () => {
    let saved = { ...profile, avatar: null as null | { mediaType: string; width: number; height: number; byteSize: number; createdAt: string } }
    const { calls } = mount('/settings/profile', c => {
      if (c.path === '/api/me/profile/avatar' && c.method === 'PUT') {
        expect(c.body).toBeInstanceOf(FormData); expect((c.body as FormData).get('file')).toBeInstanceOf(File)
        expect(c.headers.get('X-CSRF-TOKEN')).toBe('synthetic-csrf')
        expect(c.headers.has('Content-Type')).toBe(false)
        saved = { ...saved, avatar: { mediaType: 'image/png', width: 32, height: 32, byteSize: 100, createdAt: '2026-10-20T00:00:00Z' } }
        return json(saved)
      }
      if (c.path === '/api/me/profile/avatar' && c.method === 'DELETE') { saved = { ...saved, avatar: null }; return new Response(null, { status: 204 }) }
      if (c.path === '/api/me/profile') return json(saved)
      return undefined
    })
    await screen.findByDisplayValue('Synthetic writer')
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Unsaved profile draft' } })
    fireEvent.change(screen.getByLabelText('Choose avatar'), { target: { files: [new File(['synthetic'], 'avatar.png', { type: 'image/png' })] } })
    await screen.findByText('Avatar updated.')
    expect((screen.getByLabelText('Display name') as HTMLInputElement).value).toBe('Unsaved profile draft')
    fireEvent.click(screen.getByRole('button', { name: 'Remove avatar' }))
    await screen.findByText('Avatar removed.')
    expect((screen.getByLabelText('Display name') as HTMLInputElement).value).toBe('Unsaved profile draft')
    expect(calls.filter(c => c.path === '/api/me/profile' && c.method === 'PUT')).toHaveLength(0)
    expect(calls.some(c => /public-profile|publications/.test(c.path))).toBe(false)
  })
  it.each(['image', 'pdf', 'audio', 'video'])('opens an authorized %s citation on the existing Note route with actual page/time provenance', async kind => {
    const attachmentId = '01990a55-9e12-7ac4-8f5b-31aa4a91d402'
    const sourceLocation = { ...location, kind: { image: 'image_region', pdf: 'pdf_text', audio: 'transcript', video: 'video_scene' }[kind], page: kind === 'pdf' ? 3 : null, timeStart: ['audio', 'video'].includes(kind) ? 12 : null }
    const evidence = { ...result, deterministicResults: [], citations: [{ noteId: id, attachmentId, location: sourceLocation }] }
    const media = { id: attachmentId, noteId: id, mediaKind: kind, mediaType: { image: 'image/png', pdf: 'application/pdf', audio: 'audio/wav', video: 'video/mp4' }[kind], displayFilename: 'Synthetic evidence', sizeBytes: 1000,
      width: kind === 'image' ? 32 : null, height: kind === 'image' ? 32 : null, pageCount: kind === 'pdf' ? 4 : null, durationSeconds: ['audio', 'video'].includes(kind) ? 30 : null, storageState: 'stored', validationState: 'accepted', cleanupState: 'retained', createdAt: '2026-10-20T00:00:00Z', updatedAt: '2026-10-20T00:00:00Z' }
    const { calls } = mount('/ask', c => {
      if (c.path === '/api/knowledge/query') return json(evidence)
      if (c.path === `/api/notes/${id}`) return json({ id, title: 'Evidence note', markdown: 'Saved evidence', lifecycle: 'active', pinned: false, tags: [], aiEnabled: true, createdAt: media.createdAt, updatedAt: media.updatedAt }, 200, { ETag: '"n1"' })
      if (c.path === `/api/notes/${id}/attachments`) return json({ items: [media], nextCursor: null })
      if (c.path === `/api/notes/${id}/attachments/${attachmentId}`) return json(media, 200, { ETag: '"a1"' })
      return undefined
    })
    fireEvent.change(await screen.findByLabelText('What would you like to find?'), { target: { value: 'Find synthetic media evidence' } })
    fireEvent.submit(screen.getByLabelText('What would you like to find?').closest('form')!)
    fireEvent.click(await screen.findByRole('link', { name: /Source 1/ }))
    const viewer = await screen.findByRole('dialog', { name: 'Synthetic evidence' })
    expect(window.location.pathname).toBe(`/notes/${id}`)
    const content = `/api/notes/${id}/attachments/${attachmentId}/content`
    if (kind === 'pdf') expect(within(viewer).getByRole('link', { name: 'Open PDF' }).getAttribute('href')).toBe(`${content}#page=3`)
    else if (kind === 'image') expect(within(viewer).getByRole('img').getAttribute('src')).toBe(content)
    else expect(viewer.querySelector(kind === 'audio' ? 'audio' : 'video')?.getAttribute('src')).toBe(`${content}#t=12`)
    expect(calls.some(c => /publications|storage|gemini/.test(c.path))).toBe(false)
  })
  it('separates deterministic AI-OFF facts from grounded answers, exposes bounded coverage and keeps query text out of keys/URLs', async () => {
    const { auth, calls } = mount('/ask', call => call.path === '/api/knowledge/query' ? json(result) : undefined)
    const question = 'A private synthetic question not for URL storage'
    fireEvent.change(await screen.findByLabelText('What would you like to find?'), { target: { value: question } })
    fireEvent.submit(screen.getByLabelText('What would you like to find?').closest('form')!)
    await screen.findByText('A grounded synthetic answer.')
    expect(within(screen.getByRole('region', { name: 'Deterministic results' })).getByText('AI OFF · deterministic only')).toBeTruthy()
    expect(screen.getByText(/Partial coverage/)).toBeTruthy()
    expect(screen.getAllByRole('link', { name: /Source 1/ }).every(link => link.getAttribute('href') === `/notes/${id}`)).toBe(true)
    const request = calls.find(c => c.path === '/api/knowledge/query')!
    expect(request.body).toEqual({ query: question }); expect(request.headers.get('X-CSRF-TOKEN')).toBe('synthetic-csrf')
    expect(JSON.stringify(auth.queries.getQueryCache().getAll().map(q => q.queryKey))).not.toContain(question)
    expect(window.location.search).toBe(''); expect(window.location.hash).toBe('')
    expect(localStorage.length + sessionStorage.length).toBe(0)
  })
  it('follows a safe 202 status resource to completion without resubmitting the question', async () => {
    const { calls } = mount('/ask', call => call.path === '/api/knowledge/query'
      ? json({ operationId: handle, status: 'queued', submittedAt: '2026-10-20T00:00:00Z', result: null }, 202, { Location: `/api/knowledge/operations/${handle}` })
      : call.path === `/api/knowledge/operations/${handle}` ? json({ operationId: handle, status: 'completed', submittedAt: '2026-10-20T00:00:00Z', result }) : undefined)
    fireEvent.change(await screen.findByLabelText('What would you like to find?'), { target: { value: 'List every saved detail' } })
    fireEvent.submit(screen.getByLabelText('What would you like to find?').closest('form')!)
    await screen.findByText('A grounded synthetic answer.')
    expect(calls.filter(c => c.path === '/api/knowledge/query')).toHaveLength(1)
    expect(calls.filter(c => c.path === `/api/knowledge/operations/${handle}`)).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Cancel request' })).toBeNull()
  })
  it('cancels one running operation explicitly without inventing a completed result', async () => {
    const accepted = { operationId: handle, status: 'running', submittedAt: '2026-10-20T00:00:00Z', result: null }
    const { calls } = mount('/ask', c => c.path === '/api/knowledge/query' ? json(accepted, 202, { Location: `/api/knowledge/operations/${handle}` })
      : c.path === `/api/knowledge/operations/${handle}` ? c.method === 'DELETE' ? new Response(null, { status: 204 }) : json(accepted) : undefined)
    fireEvent.change(await screen.findByLabelText('What would you like to find?'), { target: { value: 'List every saved detail' } })
    fireEvent.submit(screen.getByLabelText('What would you like to find?').closest('form')!)
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel request' }))
    await screen.findByText('Request cancelled.')
    expect(calls.filter(c => c.method === 'DELETE')).toHaveLength(1)
    expect(screen.queryByRole('region', { name: 'AI answer' })).toBeNull()
  })
  it('rejects an external operation Location without following it', async () => {
    const { calls } = mount('/ask', c => c.path === '/api/knowledge/query' ? json({ operationId: handle, status: 'queued', submittedAt: '2026-10-20T00:00:00Z', result: null }, 202, { Location: 'https://untrusted.invalid/status' }) : undefined)
    fireEvent.change(await screen.findByLabelText('What would you like to find?'), { target: { value: 'All details' } })
    fireEvent.submit(screen.getByLabelText('What would you like to find?').closest('form')!)
    await screen.findByRole('alert')
    expect(calls.some(c => c.path.includes('untrusted.invalid') || c.path.includes('/operations/'))).toBe(false)
  })
  it.each([403, 503])('keeps rejected Ask %s distinct from no matches and does not falsely end the session', async status => {
    const { auth } = mount('/ask', c => c.path === '/api/knowledge/query' ? problem(status) : undefined)
    fireEvent.change(await screen.findByLabelText('What would you like to find?'), { target: { value: 'Find a detail' } })
    fireEvent.submit(screen.getByLabelText('What would you like to find?').closest('form')!)
    await screen.findByRole('alert'); expect(auth.state).toBe('authenticated')
    expect(screen.queryByRole('region', { name: 'Knowledge results' })).toBeNull()
  })
  it('clears private state immediately when a protected operation loses session authority', async () => {
    const { auth } = mount('/ask', c => c.path === '/api/knowledge/query' ? problem(401) : undefined)
    fireEvent.change(await screen.findByLabelText('What would you like to find?'), { target: { value: 'Synthetic private query' } })
    auth.queries.setQueryData(['private', auth.session.viewerScope], 'synthetic old value')
    fireEvent.submit(screen.getByLabelText('What would you like to find?').closest('form')!)
    await screen.findByRole('heading', { name: 'Welcome back' })
    expect(auth.state).toBe('anonymous'); expect(auth.queries.getQueryCache().getAll()).toHaveLength(0)
    expect(screen.queryByDisplayValue('Synthetic private query')).toBeNull()
  })
  it('never lets a late old-viewer 401 revoke a newer authenticated epoch', async () => {
    let resolve!: (value: Response) => void
    const { auth } = mount('/ask', c => c.path === '/api/knowledge/query' ? new Promise<Response>(done => { resolve = done }) : undefined)
    fireEvent.change(await screen.findByLabelText('What would you like to find?'), { target: { value: 'An old question' } })
    fireEvent.submit(screen.getByLabelText('What would you like to find?').closest('form')!)
    await waitFor(() => expect(resolve).toBeTypeOf('function'))
    const old = auth.session.viewerScope
    await act(async () => { await auth.refreshSession(true) })
    const fresh = auth.session.viewerScope; expect(fresh).not.toBe(old)
    await act(async () => { resolve(problem(401)) })
    expect(auth.state).toBe('authenticated'); expect(auth.session.viewerScope).toBe(fresh)
  })
  it('uses POST for ordinary search with tags, and never puts private terms in keys or URLs', async () => {
    const { calls, auth } = mount('/ask', c => c.path === '/api/notes/search' ? json({ items: [{ id, title: 'Saved result', lifecycle: 'active', pinned: false, tags: ['Idea'], updatedAt: '2026-10-20T00:00:00Z', snippet: '<script>plain text</script>', matchLabels: ['body'] }], nextCursor: null }) : undefined)
    fireEvent.click(await screen.findByRole('button', { name: 'Ordinary search' }))
    fireEvent.change(screen.getByLabelText('Search saved notes'), { target: { value: 'synthetic-private-search' } })
    fireEvent.change(screen.getByLabelText('Tags (comma separated, optional)'), { target: { value: 'Idea' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search notes' }))
    await screen.findByRole('link', { name: /Saved result/ })
    expect(document.querySelector('script')).toBeNull()
    expect(calls.find(c => c.path === '/api/notes/search')?.body).toEqual({ query: 'synthetic-private-search', tags: ['Idea'], lifecycle: 'active' })
    expect(JSON.stringify(auth.queries.getQueryCache().getAll().map(q => q.queryKey))).not.toContain('synthetic-private-search')
  })
  it('updates only allowlisted private Profile fields and does not activate publishing', async () => {
    const { calls } = mount('/settings/profile', c => c.method === 'PUT' ? json({ ...profile, ...(c.body as object), updatedAt: '2026-10-20T00:00:00Z' }) : undefined)
    await screen.findByDisplayValue('Synthetic writer')
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Changed name' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save profile' }))
    await screen.findByText('Profile saved. Nothing was published.')
    expect(calls.find(c => c.method === 'PUT')?.body).toEqual({ displayName: 'Changed name', biography: profile.biography, handle: null })
    expect(calls.some(c => /public-profile|publication/.test(c.path))).toBe(false)
  })
  it('keeps a failed Profile draft for explicit retry', async () => {
    mount('/settings/profile', c => c.method === 'PUT' ? problem(503) : undefined)
    await screen.findByDisplayValue('Synthetic writer')
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Keep this draft' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save profile' })); await screen.findByRole('alert')
    expect((screen.getByLabelText('Display name') as HTMLInputElement).value).toBe('Keep this draft')
  })
  it('displays the exact disclosure and acknowledges deliberately without changing AI choices', async () => {
    let acknowledged = false
    const { calls } = mount('/settings/privacy', c => c.path === '/api/ai/processing-policy' ? json({ ...policy, acknowledged, acknowledgementRequired: !acknowledged })
      : c.path.endsWith('/acknowledgements') ? (acknowledged = true, new Response(null, { status: 204 })) : undefined)
    await screen.findByText(policy.disclosureContent)
    expect(calls.some(c => c.method === 'POST')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'I acknowledge this processing disclosure' }))
    await screen.findByText('Disclosure acknowledged. No Note AI choice was changed.')
    const posts = calls.filter(c => c.method === 'POST')
    expect(posts).toHaveLength(1)
    expect(posts[0].body).toEqual({ processingPolicyId: id, policyCode: policy.policyCode, policyVersion: 1, policyFingerprint: policy.policyFingerprint, disclosureRevision: policy.disclosureRevision, confirmAcknowledgement: true })
    expect(calls.some(c => /ai-access|note-preferences/.test(c.path) && c.method !== 'GET')).toBe(false)
  })
  it('reloads changed disclosure and requires a second explicit action instead of retrying acknowledgement', async () => {
    let version = 1, attempts = 0
    const { calls } = mount('/settings/privacy', c => {
      if (c.path === '/api/ai/processing-policy') return json({ ...policy, policyVersion: version, disclosureRevision: `synthetic-v${version}`, disclosureContent: `SYNTHETIC DISCLOSURE VERSION ${version}.` })
      if (c.path.endsWith('/acknowledgements')) {
        attempts++; version = 2
        return json({ type: 'about:blank', title: 'Changed', status: 409, code: 'processing_policy_changed', instance: c.path, traceId: `tr_${'a'.repeat(32)}` }, 409, { 'Content-Type': 'application/problem+json' })
      }
      return undefined
    })
    await screen.findByText('SYNTHETIC DISCLOSURE VERSION 1.')
    fireEvent.click(screen.getByRole('button', { name: 'I acknowledge this processing disclosure' }))
    await screen.findByText('SYNTHETIC DISCLOSURE VERSION 2.')
    await screen.findByText(/The processing policy changed/)
    expect(attempts).toBe(1)
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(1)
  })
  it('reads a private avatar after fresh bootstrap and releases its object URL on removal', async () => {
    const create = vi.fn(() => 'blob:synthetic-private-avatar'), revoke = vi.fn()
    const OriginalURL = URL
    vi.stubGlobal('URL', Object.assign(class extends OriginalURL {}, { createObjectURL: create, revokeObjectURL: revoke }))
    let avatar: Omit<typeof profile, 'avatar'> & { avatar: object | null } = { ...profile, avatar: { mediaType: 'image/png', width: 1, height: 1, byteSize: 3, createdAt: '2026-10-23T00:00:00Z' } }
    const { calls } = mount('/settings/profile', c => c.path === '/api/me/profile' ? json(avatar)
      : c.path === '/api/me/profile/avatar/content' ? new Response(new Blob(['png'], { type: 'image/png' }), { headers: { 'Content-Type': 'image/png' } })
      : c.path === '/api/me/profile/avatar' && c.method === 'DELETE' ? (avatar = { ...avatar, avatar: null }, new Response(null, { status: 204 })) : undefined)
    const image = await screen.findByRole('img', { name: 'Your private profile avatar' })
    expect(image.getAttribute('src')).toBe('blob:synthetic-private-avatar')
    expect(calls.some(c => c.path === '/api/me/profile/avatar/content' && c.method === 'GET')).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Remove avatar' }))
    await screen.findByText('Avatar removed.')
    expect(revoke).toHaveBeenCalledWith('blob:synthetic-private-avatar')
    expect(screen.queryByRole('img', { name: 'Your private profile avatar' })).toBeNull()
    expect(localStorage.length + sessionStorage.length).toBe(0)
  })
  it('does not offer acknowledgement when the backend has supplied only policy identity, not disclosure text', async () => {
    const { disclosureContent: _content, providerProcessingCategories: _categories, ...identityOnly } = policy
    const { calls } = mount('/settings/privacy', c => c.path === '/api/ai/processing-policy' ? json(identityOnly) : undefined)
    await screen.findByRole('button', { name: 'Retry disclosure' })
    expect(screen.queryByRole('button', { name: 'I acknowledge this processing disclosure' })).toBeNull()
    expect(calls.some(c => c.path.includes('acknowledgements'))).toBe(false)
  })
  it('aborts an old private-avatar request and ignores its late response after authority loss', async () => {
    const create = vi.fn(() => 'blob:must-not-be-created'), revoke = vi.fn(), OriginalURL = URL
    vi.stubGlobal('URL', Object.assign(class extends OriginalURL {}, { createObjectURL: create, revokeObjectURL: revoke }))
    let release!: (response: Response) => void
    const { auth, calls } = mount('/settings/profile', c => c.path === '/api/me/profile'
      ? json({ ...profile, avatar: { mediaType: 'image/png', width: 1, height: 1, byteSize: 3, createdAt: '2026-10-23T00:00:00Z' } })
      : c.path === '/api/me/profile/avatar/content' ? new Promise<Response>(resolve => { release = resolve }) : undefined)
    await waitFor(() => expect(calls.some(c => c.path.endsWith('/avatar/content'))).toBe(true))
    await act(() => auth.establish('anonymous'))
    expect(calls.find(c => c.path.endsWith('/avatar/content'))?.signal?.aborted).toBe(true)
    await act(async () => { release(new Response(new Blob(['png'], { type: 'image/png' }), { headers: { 'Content-Type': 'image/png' } })); await Promise.resolve() })
    expect(create).not.toHaveBeenCalled(); expect(screen.queryByRole('img', { name: 'Your private profile avatar' })).toBeNull()
    expect(auth.queries.getQueryCache().getAll().every(q => !q.queryKey.includes('profile'))).toBe(true)
  })
  it('separates the future-note preference from a confirmed existing-note bulk change', async () => {
    const { calls } = mount('/settings/privacy', c => c.method === 'PUT' ? json({ defaultAiEnabledForNewNotes: true })
      : c.path === '/api/notes/ai-access-bulk' ? json({ affectedCount: 3 }) : undefined)
    const preference = await screen.findByRole('checkbox', { name: 'Default AI access for new notes' })
    fireEvent.click(preference); await screen.findByText('Future-note preference saved. Existing notes were not changed.')
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Enable AI on existing notes' }))
    const dialog = await screen.findByRole('dialog')
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(0)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm existing-note change' }))
    await screen.findByText('3 existing notes updated. Future-note default is unchanged.')
    expect(calls.find(c => c.path === '/api/notes/ai-access-bulk')?.body).toEqual({ aiEnabled: true, scope: 'allExisting', confirm: true })
    expect((preference as HTMLInputElement).checked).toBe(true)
  })
  it.each(['/ask', '/settings/profile', '/settings/privacy'])('does not request private data before full authentication: %s', async path => {
    const { calls } = mount(path, () => undefined, 'anonymous')
    await screen.findByRole('heading', { name: 'Welcome back' })
    expect(calls.every(c => c.path.startsWith('/api/auth/'))).toBe(true)
  })
  it('returns to a safe intended page after password login instead of a signed-in landing', async () => {
    mount('/settings/profile', c => c.path === '/api/auth/login/password' ? json({ state: 'authenticated' }) : undefined, 'anonymous')
    fireEvent.change(await screen.findByLabelText('Email'), { target: { value: 'synthetic@example.test' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
    await screen.findByRole('heading', { name: 'Your profile' })
    expect(window.location.pathname).toBe('/settings/profile')
  })
  it('retains the intended page through a restricted MFA continuation', async () => {
    const challenge = 'B'.repeat(43)
    mount('/settings/privacy', c => c.path === '/api/auth/login/password' ? json({ state: 'mfaRequired', challengeId: challenge }, 202)
      : c.path.includes('/api/auth/mfa/challenges/') ? json({ state: 'authenticated' }) : undefined, 'anonymous')
    fireEvent.change(await screen.findByLabelText('Email'), { target: { value: 'synthetic@example.test' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
    fireEvent.change(await screen.findByLabelText('Authenticator code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Verify and continue' }))
    await screen.findByRole('heading', { name: 'AI & Privacy' }); expect(window.location.pathname).toBe('/settings/privacy')
  })
})

describe('bounded decoding and navigation', () => {
  it.each([`/notes/${id}\n`, `/publication/${id}\r`, '/profile/synthetic_writer\n'])('rejects trailing controls even after a syntactically valid dynamic route %s', target => expect(safeDestination(target)).toBeNull())
  it.each(['https://evil.invalid', '//evil.invalid', '/notes/%2e%2e', '/notes\\evil', '/notes?return=https://evil.invalid', '/ask#private', '/admin', '/notes\n'])('rejects unsafe return target %s', target => expect(safeDestination(target)).toBeNull())
  it('allows only implemented safe destinations', () => { expect(safeDestination(`/notes/${id}`)).toBe(`/notes/${id}`); expect(safeDestination('/ask')).toBe('/ask') })
  it('decodes the backend opaque dotted operation handle without interpreting its authority', () => expect(operation({ operationId: handle, status: 'queued', submittedAt: '2026-10-20T00:00:00Z', result: null }).operationId).toBe(handle))
  it.each(['..', '../notes', 'https://evil.invalid', '%2fnotes'])('rejects unsafe operation locator %s', operationId => expect(() => operation({ operationId, status: 'queued', submittedAt: '', result: null })).toThrow())
  it('rejects malformed evidence instead of rendering it', () => expect(() => knowledgeResult({ ...result, citations: [{ noteId: '../foreign', attachmentId: null, location }] })).toThrow())
  it.each([['png', 'image/png', 5 * 1024 * 1024], ['pdf', 'application/pdf', 10 * 1024 * 1024], ['wav', 'audio/wav', 10 * 1024 * 1024], ['mp4', 'video/mp4', 20_000_000]] as const)('uses the backend %s byte limit exactly', (extension, type, bytes) => {
    const f = new File(['x'], `synthetic.${extension}`, { type }); Object.defineProperty(f, 'size', { value: bytes }); expect(attachmentPreflight(f)).toBeNull()
    const oversized = new File(['x'], `synthetic.${extension}`, { type }); Object.defineProperty(oversized, 'size', { value: bytes + 1 }); expect(attachmentPreflight(oversized)).toContain('too large')
  })
})
