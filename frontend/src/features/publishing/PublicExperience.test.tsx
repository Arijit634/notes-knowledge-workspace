import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { App, PRODUCT_ROUTES } from '../../app/App'
import { AuthRuntime } from '../auth/AuthRuntime'
import { publicationKeys } from './PublicationCache'
import * as f from './PublicFixtures.test-support'

type Call = { path: string; method: string; body: unknown; headers: Headers }
const json = (body: unknown, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
const problem = (status: number, code = 'access_denied') => json({ type: 'about:blank', title: 'Request rejected', status, code, instance: '/api/public/publications', traceId: `tr_${'a'.repeat(32)}` }, status, { 'Content-Type': 'application/problem+json' })
const runtimes: AuthRuntime[] = []
function mount(path: string, state = 'anonymous', handler: (c: Call) => Response | Promise<Response> | undefined = () => undefined) {
  const calls: Call[] = []; window.history.replaceState(null, '', path)
  vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    const c = { path: String(url), method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? JSON.parse(init.body) : null, headers: new Headers(init?.headers) }; calls.push(c)
    if (c.path === '/api/auth/session') return json({ state })
    if (c.path === '/api/auth/csrf') return json({ csrfToken: 'synthetic-proof' })
    const custom = await handler(c); if (custom) return custom
    if (c.path.startsWith('/api/public/explore?') || c.path.startsWith('/api/public/search?')) return json({ items: [f.card], nextCursor: null, rankingMode: 'latest', semanticAvailable: false, exhaustive: false })
    if (c.path === `/api/public/publications/${f.pubId}`) return json(state === 'authenticated' ? f.article : { ...f.article, viewerLiked: undefined })
    if (c.path.startsWith(`/api/public/profiles/${f.author.handle}/publications`)) return json({ items: [{ ...f.card, updatedAt: f.timestamp }], nextCursor: null })
    if (c.path === `/api/public/profiles/${f.author.handle}` || c.path === '/api/me/public-profile') return json(f.author)
    if (c.path === '/api/me/profile') return json(f.profile)
    if (c.path.startsWith('/api/me/publications?')) return json({ items: [f.owner], nextCursor: null })
    if (c.path === `/api/me/publications/${f.pubId}`) return json(f.owner, 200, { ETag: '"p1"' })
    if (c.path.endsWith('/source-status')) return json(f.status)
    if (c.path.startsWith('/api/moderation/reports?')) return state === 'moderator' ? json({ items: [f.report], nextCursor: null }) : problem(403)
    if (c.path === `/api/moderation/reports/${f.reportId}`) return json(f.detail)
    if (c.path.endsWith('/publication-preview')) return json(f.preview)
    if (c.path.startsWith('/api/notes?') || c.path === '/api/notes') return json({ items: [f.note], nextCursor: null })
    if (c.path === `/api/notes/${f.noteId}`) return json(f.note, 200, { ETag: '"n1"' })
    if (c.path.endsWith('/attachments')) return json({ items: [f.attachment], nextCursor: null })
    return problem(503, 'service_unavailable')
  }))
  const auth = new AuthRuntime(); runtimes.push(auth); render(<App auth={auth} />); return { auth, calls }
}
beforeAll(async () => { await Promise.all([import('../discovery/DiscoveryPages'), import('./OwnerPublicationPages'), import('../profile/PublicProfileSettingsPage'), import('../moderation/ModerationPages'), import('../notes/NotesPages'), import('../notes/MarkdownEditorAdapter')]) }, 60_000)
afterEach(() => { cleanup(); runtimes.splice(0).forEach(a => a.queries.clear()); vi.restoreAllMocks(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/') })

describe('public routes and boundaries', () => {
  it('uses only the fresh authoritative owner source identity and denies update after retirement even with stale ready status', async () => {
    const { calls } = mount(`/publications/${f.pubId}`, 'authenticated', c => c.path === `/api/me/publications/${f.pubId}`
      ? json({ ...f.owner, sourceNoteId: null }, 200, { ETag: '"p-retired"' }) : undefined)
    await screen.findByText('The original private Note is no longer available.')
    expect(screen.queryByRole('link', { name: 'Open source Note' })).toBeNull()
    expect((screen.getByRole('button', { name: 'Update public copy' }) as HTMLButtonElement).disabled).toBe(true)
    expect(calls.some(c => c.path.startsWith('/api/notes'))).toBe(false)
  })
  it('accounts for 27 canonical routes, with one reader implementation', () => { expect(PRODUCT_ROUTES).toHaveLength(27); expect(new Set(PRODUCT_ROUTES).size).toBe(27); expect(PRODUCT_ROUTES).not.toContain('/p/:publicationId') })
  it('opens anonymous Explore without any private/module bootstrap', async () => {
    const { calls } = mount('/explore'); await screen.findByRole('heading', { name: f.owner.title })
    expect(calls.every(c => c.path.startsWith('/api/auth/') || c.path.startsWith('/api/public/'))).toBe(true)
    expect(screen.queryByRole('navigation', { name: 'Workspace navigation' })).toBeNull()
    fireEvent.click(screen.getByRole('link', { name: 'Trending' })); await waitFor(() => expect(calls.some(c => c.path.includes('sort=trending'))).toBe(true))
  })
  it('keeps keyword/tag search public, bounded and shareable', async () => {
    const { calls } = mount('/explore/search?q=ideas&tag=Everyday'); await screen.findByRole('heading', { name: f.owner.title })
    expect((screen.getByLabelText('Keywords') as HTMLInputElement).value).toBe('ideas'); expect((screen.getByLabelText('Tag', { exact: true }) as HTMLInputElement).value).toBe('Everyday')
    expect(calls.some(c => c.path.startsWith('/api/public/search?'))).toBe(true); expect(calls.some(c => c.path === '/api/notes/search')).toBe(false)
  })
  it('does not send unbounded or control-bearing URL query input', async () => {
    const { calls } = mount('/explore/search?q=' + 'a'.repeat(401)); await screen.findByText('Start with a word or a tag.')
    expect(calls.some(c => c.path.startsWith('/api/public/search'))).toBe(false)
  })
  it('uses author-only projection and active-publication navigation', async () => {
    const { calls } = mount(`/profile/${f.author.handle}`); await screen.findByRole('heading', { name: f.author.displayName })
    await screen.findByRole('heading', { name: f.owner.title }); expect(screen.queryByText(/@example.test/)).toBeNull()
    expect(calls.some(c => c.path.startsWith('/api/me/'))).toBe(false)
  })
  it('accepts backend /p/ UUID links and redirects once to canonical reader', async () => {
    mount(`/p/${f.pubId}`); await screen.findByRole('heading', { name: f.owner.title }); expect(window.location.pathname).toBe(`/publication/${f.pubId}`)
  })
  it('rejects malformed public IDs before fetching a resource', async () => {
    const { calls } = mount('/publication/not-a-uuid'); await screen.findByRole('heading', { name: 'Publication unavailable' }); expect(calls.some(c => c.path.startsWith('/api/public/publications'))).toBe(false)
  })
  it('offers anonymous Like sign-in continuation without a mutation', async () => {
    const { calls } = mount(`/publication/${f.pubId}`); await screen.findByRole('heading', { name: f.owner.title })
    fireEvent.click(screen.getByRole('button', { name: 'Like' })); await screen.findByRole('heading', { name: 'Welcome back' }); expect(calls.some(c => c.path.endsWith('/like'))).toBe(false)
  })
  it('does not show failed Like as successful optimistic state', async () => {
    mount(`/publication/${f.pubId}`, 'authenticated', c => c.path.endsWith('/like') ? problem(503, 'service_unavailable') : undefined)
    await screen.findByRole('button', { name: 'Like' }); fireEvent.click(screen.getByRole('button', { name: 'Like' })); await screen.findByRole('alert')
    expect(screen.getByRole('button', { name: 'Like' }).getAttribute('aria-pressed')).toBe('false')
  })
  it('returns a generic receipt, never the moderator Location/detail', async () => {
    const { calls } = mount(`/publication/${f.pubId}`, 'authenticated', c => c.path.endsWith('/reports') ? json({ id: f.reportId }, 201, { Location: `/api/moderation/reports/${f.reportId}` }) : undefined)
    await screen.findByRole('button', { name: 'Report' }); fireEvent.click(screen.getByRole('button', { name: 'Report' }))
    const dialog = await screen.findByRole('dialog'); fireEvent.change(within(dialog).getByLabelText('Description'), { target: { value: 'Synthetic public report' } }); fireEvent.click(within(dialog).getByRole('button', { name: 'Submit report' }))
    await screen.findByText(/Report received/); expect(window.location.pathname).toBe(`/publication/${f.pubId}`); expect(calls.some(c => c.path.startsWith('/api/moderation/'))).toBe(false)
  })
  it('does not render malicious Markdown HTML or fetch arbitrary remote images', async () => {
    mount(`/publication/${f.pubId}`, 'anonymous', c => c.path === `/api/public/publications/${f.pubId}` ? json({ ...f.article, markdown: '<script>window.pwn=1</script>\n\n![external](https://example.test/tracker.png)\n\n[evil](javascript:alert(1))', viewerLiked: undefined }) : undefined)
    await screen.findByRole('heading', { name: f.owner.title }); expect(document.querySelector('script')).toBeNull(); expect(document.querySelector('img[src^="https://example.test"]')).toBeNull(); expect(document.querySelector('a[href^="javascript:"]')).toBeNull()
  })
  it('does not show a stale public snapshot when current availability returns 404', async () => {
    mount(`/publication/${f.pubId}`, 'anonymous', c => c.path === `/api/public/publications/${f.pubId}` ? problem(404, 'resource_not_found') : undefined)
    await screen.findByRole('heading', { name: 'Not available' }); expect(screen.queryByText(f.owner.markdown)).toBeNull()
  })
})
describe('deliberate owner workflow', () => {
  it.each(['/publications', '/settings/public-profile', '/moderation/reports'])('gates protected %s before data access', async path => {
    const { calls } = mount(path); await screen.findByRole('heading', { name: 'Welcome back' }); expect(calls.some(c => c.path.startsWith('/api/me/') || c.path.startsWith('/api/moderation/'))).toBe(false)
  })
  it('shows authoritative source booleans, never treats the status as an ETag', async () => {
    mount(`/publications/${f.pubId}`, 'authenticated', c => c.path.endsWith('/source-status') ? json({ ...f.status, sourceUsable: false, updatePublicCopyReady: false }) : undefined)
    await screen.findByText('The source is not currently usable for publishing.'); expect((screen.getByRole('button', { name: 'Update public copy' }) as HTMLButtonElement).disabled).toBe(true)
  })
  it('unpublish uses aggregate If-Match and removes cached reader immediately', async () => {
    const { auth, calls } = mount(`/publications/${f.pubId}`, 'authenticated', c => c.path.endsWith('/unpublish') ? json({ ...f.owner, availability: 'unpublished' }, 200, { ETag: '"p2"' }) : undefined)
    await screen.findByRole('button', { name: 'Unpublish' }); auth.queries.setQueryData(publicationKeys.article(auth, f.pubId), f.article)
    fireEvent.click(screen.getByRole('button', { name: 'Unpublish' })); const dialog = await screen.findByRole('dialog'); fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm unpublish' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull()); expect(auth.queries.getQueryData(publicationKeys.article(auth, f.pubId))).toBeNull()
    expect(calls.find(c => c.path.endsWith('/unpublish'))?.headers.get('If-Match')).toBe('"p1"')
  })
  it('does not activate or publish automatically from public profile settings', async () => {
    const { calls } = mount('/settings/public-profile', 'authenticated'); await screen.findByLabelText('Public handle')
    await screen.findByRole('button', { name: 'Refresh public profile' }); expect(calls.some(c => c.method === 'PUT')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Refresh public profile' })); fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirm public profile' }))
    await screen.findByText('Public profile is active and refreshed. No Note was published.'); expect(calls.filter(c => c.method === 'PUT').map(c => c.path)).toEqual(['/api/me/public-profile'])
  })
  it('requires fresh preview after a rejected fingerprint and never silently retries', async () => {
    const { calls } = mount(`/publications/${f.pubId}`, 'authenticated', c => c.method === 'PUT' ? problem(409, 'invalid_lifecycle_transition') : undefined)
    await screen.findByRole('button', { name: 'Update public copy' }); fireEvent.click(screen.getByRole('button', { name: 'Update public copy' }))
    await screen.findByRole('dialog')
    await screen.findByRole('button', { name: 'Preview public copy' }); await waitFor(() => expect((screen.getByRole('button', { name: 'Preview public copy' }) as HTMLButtonElement).disabled).toBe(false)); fireEvent.click(screen.getByRole('button', { name: 'Preview public copy' }))
    await screen.findByRole('button', { name: 'Confirm public update' }); fireEvent.click(screen.getByRole('button', { name: 'Confirm public update' })); await screen.findByRole('alert')
    expect(screen.queryByRole('button', { name: 'Confirm public update' })).toBeNull(); expect(calls.filter(c => c.method === 'PUT')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Preview public copy' }).isConnected).toBe(true)
  })
})
describe('moderation capability, recent-auth and public-only evidence', () => {
  it('denies the ordinary user without private-resource fallback', async () => {
    const { calls } = mount('/moderation/reports', 'authenticated'); await screen.findByRole('heading', { name: 'Moderation permission unavailable' })
    expect(screen.queryByText(f.report.description)).toBeNull(); expect(calls.some(c => c.path.startsWith('/api/notes'))).toBe(false)
  })
  it('distinguishes typed recent-auth from capability denial', async () => {
    const { auth } = mount('/moderation/reports', 'authenticated', c => c.path.startsWith('/api/moderation/reports?') ? problem(403, 'recent_authentication_required') : undefined)
    await screen.findByRole('heading', { name: 'Confirm your identity' }); fireEvent.click(screen.getByRole('link', { name: 'Confirm identity' })); expect(auth.continuation.returnIntent).toBe('/moderation/reports')
  })
  it('starts review through the explicit transition', async () => {
    const { calls } = mount(`/moderation/reports/${f.reportId}`, 'authenticated', c => c.path.endsWith('/begin-review') ? json({ ...f.detail, report: { ...f.report, status: 'under_review', reviewedAt: f.timestamp } }) : undefined)
    await screen.findByRole('button', { name: 'Begin review' }); fireEvent.click(screen.getByRole('button', { name: 'Begin review' })); await screen.findByRole('heading', { name: 'Reasoned decision' })
    expect(calls.filter(c => c.path.endsWith('/begin-review'))).toHaveLength(1); expect(calls.some(c => c.path.startsWith('/api/notes/'))).toBe(false)
  })
  it('clears restricted detail on lost enforce permission, preserving ordinary authority', async () => {
    const { auth } = mount(`/moderation/reports/${f.reportId}`, 'authenticated', c => c.path.endsWith('/decisions') ? problem(403) : c.path === `/api/moderation/reports/${f.reportId}` ? json({ ...f.detail, report: { ...f.report, status: 'under_review' } }) : undefined)
    await screen.findByRole('button', { name: 'Review decision' }); fireEvent.click(screen.getByRole('button', { name: 'Review decision' })); fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirm decision' }))
    await screen.findByRole('heading', { name: 'Moderation permission unavailable' }); expect(screen.queryByText(f.report.description)).toBeNull(); expect(auth.state).toBe('authenticated')
  })
  it('does not offer terminal reopening or removal without current public evidence', async () => {
    mount(`/moderation/reports/${f.reportId}`, 'authenticated', c => c.path === `/api/moderation/reports/${f.reportId}` ? json({ ...f.detail, targetAvailable: false, publicEvidence: null, report: { ...f.report, status: 'closed' } }) : undefined)
    await screen.findByText(/reported public generation is no longer available/); expect(screen.queryByRole('button', { name: 'Begin review' })).toBeNull(); expect(screen.queryByRole('button', { name: 'Review decision' })).toBeNull()
  })
  it('cannot inherit a prior viewer like state after authority changes', async () => {
    let liked = true
    const { auth } = mount(`/publication/${f.pubId}`, 'authenticated', c => c.path === `/api/public/publications/${f.pubId}` ? json({ ...f.article, viewerLiked: liked }) : undefined)
    await screen.findByRole('button', { name: 'Unlike' }); liked = false
    await act(async () => { await auth.establish('anonymous'); await auth.establish('authenticated') })
    await screen.findByRole('button', { name: 'Like' }); expect(screen.queryByRole('button', { name: 'Unlike' })).toBeNull()
  })
})
