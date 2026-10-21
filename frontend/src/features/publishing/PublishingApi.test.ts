import { describe, expect, it, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { ApiClient } from '../../app/api/ApiClient'
import { CsrfManager } from '../../app/security/CsrfManager'
import { SensitiveStateRegistry } from '../../app/security/SensitiveStateRegistry'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { publicAuthor, publicMedia, publicPublication, ownerPublication, previewView, publishingApi } from './PublishingApi'
import { publicationChanged, publicationKeys, sourceLinks, sourceChanged, authorChanged } from './PublicationCache'
import { moderationApi, reportDetail, reportView, reportAlias } from '../moderation/ModerationApi'
import { discoveryApi } from '../discovery/DiscoveryApi'
import { safeDestination } from '../auth/SafeDestination'
import * as f from './PublicFixtures.test-support'

const json = (body: unknown, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
function runtime(response: () => Response = () => json(f.owner, 200, { ETag: '"p1"' })) {
  const fetcher = vi.fn(async () => response()), csrf = new CsrfManager(async () => 'synthetic-csrf')
  const auth = { queries: new QueryClient(), api: new ApiClient(csrf, fetcher), session: { viewerScope: { kind: 'authenticated', viewerEpoch: 'synthetic-a' } }, sensitive: new SensitiveStateRegistry() } as unknown as AuthRuntime
  return { auth, fetcher }
}
describe('public and owner API authority surfaces', () => {
  it('decodes disjoint allowlists without private/source/secret fields', () => {
    const p = publicPublication({ ...f.article, ownerUserId: f.noteId, sourceNoteId: f.noteId, email: 'fiction@example.test' })
    expect(Object.keys(p)).not.toContain('ownerUserId'); expect(Object.keys(p)).not.toContain('sourceNoteId'); expect(Object.keys(p.author)).not.toContain('links')
    expect(ownerPublication(f.owner).publicUrl).toBe(`/p/${f.pubId}`)
    expect(previewView(f.preview).previewFingerprint).toBe('synthetic-preview-proof')
  })
  it.each(['https://example.test/file', '/api/notes/secret/content', `/api/public/publications/${f.noteId}/media/${f.mediaId}/content`, f.media.contentUrl + '?private=1', 'javascript:alert(1)', '//example.test/file'])('rejects public media URL %s without fallback', contentUrl => {
    expect(() => publicMedia({ ...f.media, contentUrl }, f.pubId)).toThrow()
  })
  it('rejects mismatched media kinds and malformed public avatar locators', () => {
    expect(() => publicMedia({ ...f.media, mediaKind: 'video' }, f.pubId)).toThrow()
    expect(() => publicAuthor({ ...f.author, avatarUrl: '/api/me/profile/avatar' })).toThrow()
    expect(() => publicAuthor({ ...f.author, avatarUrl: 'https://example.test/avatar.png' })).toThrow()
    expect(publicAuthor({ ...f.author, avatarUrl: `/api/public/profiles/${f.author.handle}/avatar/${f.mediaId}/content` }).avatarUrl).toContain('/api/public/')
  })
  it('sends Note validator for preview, never projection ETag or draft', async () => {
    const { auth, fetcher } = runtime(() => json({ ...f.preview, media: [{ attachmentId: f.mediaId, mediaKind: 'image', mediaType: 'image/png', displayName: 'Synthetic.png', sizeBytes: 68 }] })); await publishingApi.preview(auth, f.noteId, '"n2"', [f.mediaId])
    const call = fetcher.mock.calls[0] as unknown as [string, RequestInit]
    expect(call[0]).toBe(`/api/notes/${f.noteId}/publication-preview`); expect(new Headers(call[1].headers).get('If-Match')).toBe('"n2"')
    expect(new Headers(call[1].headers).get('X-CSRF-TOKEN')).toBe('synthetic-csrf')
    expect(JSON.parse(String(call[1].body))).toEqual({ selectedAttachmentIds: [f.mediaId] })
  })
  it.each(['create', 'update', 'republish'] as const)('uses the distinct %s command and opaque server fingerprint', async action => {
    const { auth, fetcher } = runtime(() => json(f.owner, action === 'create' ? 201 : 200, { ETag: '"p2"', Location: `/api/me/publications/${f.pubId}` }))
    await publishingApi.commit(auth, f.noteId, action === 'create' ? null : f.pubId, action, action === 'create' ? '"n2"' : '"p1"', [f.mediaId], f.preview.previewFingerprint)
    const call = fetcher.mock.calls[0] as unknown as [string, RequestInit]
    expect(call[1].method).toBe(action === 'update' ? 'PUT' : 'POST'); expect(call[0]).toBe(action === 'create' ? `/api/notes/${f.noteId}/publication` : `/api/me/publications/${f.pubId}${action === 'republish' ? '/republish' : ''}`)
    expect(JSON.parse(String(call[1].body))).toEqual({ selectedAttachmentIds: [f.mediaId], previewFingerprint: f.preview.previewFingerprint })
  })
  it('keeps source-status volatile and independent of mutation validators', async () => {
    const { auth } = runtime(() => json(f.status, 200, { ETag: '"not-authority"' })); expect(await publishingApi.status(auth, f.pubId)).toEqual(f.status)
    expect('etag' in await publishingApi.status(auth, f.pubId)).toBe(false)
  })
  it('does not follow a reporter Location into moderation', async () => {
    const { auth, fetcher } = runtime(() => json({ id: f.reportId }, 201, { Location: `/api/moderation/reports/${f.reportId}` }))
    await discoveryApi.report(auth, f.pubId, 'spam', 'Synthetic report'); expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('accepts only 204 for Like and emits idempotent PUT/DELETE', async () => {
    const { auth, fetcher } = runtime(() => new Response(null, { status: 204 })); await discoveryApi.like(auth, f.pubId, true); await discoveryApi.like(auth, f.pubId, false)
    expect(fetcher.mock.calls.map(c => (c as unknown as [string, RequestInit])[1].method)).toEqual(['PUT', 'DELETE'])
    const wrong = runtime(() => json({}, 200)); await expect(discoveryApi.like(wrong.auth, f.pubId, true)).rejects.toThrow()
  })
  it('validates moderation evidence identity and authoritative state', () => {
    expect(reportDetail(f.detail).report.status).toBe('open')
    expect(() => reportDetail({ ...f.detail, publicEvidence: { ...f.detail.publicEvidence, publicationId: f.noteId } })).toThrow()
    expect(() => reportView({ ...f.report, status: 'admin' })).toThrow()
    expect(() => reportView({ ...f.report, category: 'privateNote' })).toThrow()
  })
  it('does not substitute the reduced author-list contract for Explore cards', async () => {
    const { auth } = runtime(() => json({ items: [f.owner], nextCursor: null }))
    await expect(discoveryApi.explore(auth, 'latest', null)).rejects.toThrow()
  })
  it('does not expose report IDs/text in restricted cache keys', () => { const scope = {}; expect(reportAlias(scope, f.reportId)).toBe(1); expect(reportAlias(scope, f.reportId)).toBe(1); expect(reportAlias(scope, f.pubId)).toBe(2) })
  it('sends one coherent decision without user/account authority input', async () => {
    const { auth, fetcher } = runtime(() => json({ id: f.noteId, reportId: f.reportId, consequence: 'removePublication', reasonCode: 'confirmedPolicyViolation', outcomes: { publication: 'removed', account: 'unchanged' }, decidedAt: f.timestamp }, 201))
    await moderationApi.decide(auth, f.reportId, 'removePublication', 'confirmedPolicyViolation')
    const [, init] = fetcher.mock.calls[0] as unknown as [string, RequestInit]; expect(JSON.parse(String(init.body))).toEqual({ consequence: 'removePublication', reasonCode: 'confirmedPolicyViolation' })
  })
})
describe('current public generation and viewer cache boundaries', () => {
  it('evicts every viewer article and materialized collection synchronously', () => {
    const { auth } = runtime(); const a = publicationKeys.article(auth, f.pubId), b = ['public-article', f.pubId, 'other']
    auth.queries.setQueryData(a, f.article); auth.queries.setQueryData(b, f.article)
    const key = [...publicationKeys.collections, 'explore', 'latest']; auth.queries.setQueryData(key, { pageParams: [null], pages: [{ items: [f.card, { ...f.card, id: f.noteId }], nextCursor: null }] })
    publicationChanged(auth, f.pubId)
    expect(auth.queries.getQueryData(a)).toBeNull(); expect(auth.queries.getQueryData(b)).toBeNull()
    expect((auth.queries.getQueryData(key) as { pages: { items: { id: string }[] }[] }).pages[0].items.map(i => i.id)).toEqual([f.noteId])
  })
  it('never infers a private source relationship and clears learned links with the authority', () => {
    const { auth } = runtime(); expect(sourceLinks(auth).size).toBe(0); sourceLinks(auth).set(f.noteId, f.pubId)
    auth.queries.setQueryData(publicationKeys.article(auth, f.pubId), f.article); sourceChanged(auth, f.noteId, true)
    expect(auth.queries.getQueryData(publicationKeys.article(auth, f.pubId))).toBeNull()
    Object.assign(auth.session, { viewerScope: { kind: 'authenticated', viewerEpoch: 'synthetic-b' } }); expect(sourceLinks(auth).size).toBe(0)
  })
  it('invalidates public author projections without private cache changes', () => {
    const { auth } = runtime(); auth.queries.setQueryData(['private-sentinel'], { private: true }); auth.queries.setQueryData(publicationKeys.author(f.author.handle), f.author)
    authorChanged(auth); expect(auth.queries.getQueryData(publicationKeys.author(f.author.handle))).toBeNull(); expect(auth.queries.getQueryData(['private-sentinel'])).toEqual({ private: true })
  })
  it('cancels a late public fetch so removal cannot be undone by its response', async () => {
    const { auth } = runtime(); let release!: (value: unknown) => void
    const key = publicationKeys.article(auth, f.pubId)
    const request = auth.queries.fetchQuery({ queryKey: key, queryFn: () => new Promise(resolve => { release = resolve }) }).catch(() => undefined)
    publicationChanged(auth, f.pubId); release(f.article); await request
    expect(auth.queries.getQueryData(key)).toBeNull()
  })
  it('conservatively clears public materializations on source retirement without a learned link', () => {
    const { auth } = runtime(); auth.queries.setQueryData(publicationKeys.article(auth, f.pubId), f.article)
    sourceChanged(auth, f.noteId, true); expect(auth.queries.getQueryData(publicationKeys.article(auth, f.pubId))).toBeNull()
  })
  it.each([`/publication/${f.pubId}`, `/publications/${f.pubId}`, `/moderation/reports/${f.reportId}`, `/profile/${f.author.handle}`, '/explore', '/settings/public-profile'])('allows narrow first-party return %s', path => expect(safeDestination(path)).toBe(path))
  it.each(['https://example.test', '//example.test', '/publication/%2f%2fevil', '/profile/../../notes', '/explore?next=https://example.test', '/api/me/profile', '/p/not-a-uuid'])('rejects unsafe/unapproved continuation %s', path => expect(safeDestination(path)).toBeNull())
})
