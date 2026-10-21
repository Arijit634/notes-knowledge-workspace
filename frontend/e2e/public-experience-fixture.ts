import { expect, type Page } from '@playwright/test'
import * as f from '../src/features/publishing/PublicFixtures.test-support'
export { f }

/** Stateful, no-network HTTP double matching the inspected controller contracts. */
export async function publicBackend(page: Page, authenticated = false) {
  const state = {
    authenticated, eligible: true, review: false, enforce: false, recent: true, active: true, exists: true, sourceUsable: true,
    profileActive: true, note: { ...f.note }, noteRevision: 1, publication: { ...f.owner }, publicationRevision: 1,
    liked: false, report: { ...f.report }, decision: null as object | null, audit: [] as object[],
    attachments: [{ ...f.attachment }], publicMedia: [{ ...f.media }], previewNoteRevision: 0, previewSelection: [] as string[], previewGeneration: 0,
    previewError: 0, commitError: 0, reportError: 0, likeError: 0, sourceChanged: false, pages: false, terminalError: 0,
    holdLike: false, likeStarted: false, releaseLike: () => {},
    profile: { ...f.profile }, author: { ...f.author }, calls: [] as { method: string; path: string; query: string; body: any; ifMatch: string | undefined }[],
  }
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method()
    if (!path.startsWith('/api/')) { await route.continue(); return }
    const body = request.postData() ? request.postDataJSON() : null
    state.calls.push({ method, path, query: url.search, body, ifMatch: request.headers()['if-match'] })
    if (!['GET', 'HEAD'].includes(method)) expect(request.headers()['x-csrf-token']).toBe('synthetic-proof')
    let status = 200, output: unknown = {}, code = 'invalid_lifecycle_transition'
    const headers: Record<string, string> = { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }
    const publicCard = (id = f.pubId) => ({ ...f.card, id, author: state.author, title: state.publication.title, engagement: { ...f.article.engagement, likeCount: state.liked ? 5 : 4 } })
    const ownerView = () => ({ ...state.publication, media: state.publicMedia, availability: state.active ? 'active' : 'unpublished' })
    const reportView = () => ({ ...f.detail, report: state.report, publicEvidence: state.active ? f.detail.publicEvidence : null, targetAvailable: state.active, decision: state.decision, audit: state.audit })
    if (path === '/api/auth/session') output = { state: state.authenticated && state.eligible ? 'authenticated' : 'anonymous' }
    else if (path === '/api/auth/csrf') output = { csrfToken: 'synthetic-proof' }
    else if (path === '/api/auth/login/password') { state.authenticated = true; output = { state: 'authenticated' } }
    else if (path === '/api/auth/logout') { state.authenticated = false; state.liked = false; status = 204 }
    else if (path.startsWith('/api/moderation/') && (!state.authenticated || !state.eligible)) { status = 401; code = 'invalid_credentials' }
    else if (path.startsWith('/api/moderation/') && !state.recent) { status = 403; code = 'recent_authentication_required' }
    else if (path.startsWith('/api/moderation/') && (!state.review || (path.endsWith('/decisions') && !state.enforce))) { status = 403; code = 'access_denied' }
    else if (path === '/api/moderation/reports') {
      const matches = (!url.searchParams.get('state') || url.searchParams.get('state') === state.report.status) && (!url.searchParams.get('category') || url.searchParams.get('category') === state.report.category)
      output = { items: matches ? [state.report] : [], nextCursor: null }
    } else if (path === `/api/moderation/reports/${f.reportId}`) output = reportView()
    else if (path.endsWith('/begin-review')) {
      if (!['open', 'under_review'].includes(state.report.status)) status = 409
      else { state.report.status = state.active ? 'under_review' : 'closed'; state.audit.push({ action: 'begin_review', outcome: 'recorded', reason: null, occurredAt: f.timestamp }); output = reportView() }
    } else if (path.endsWith('/decisions')) {
      if (state.terminalError) { status = state.terminalError; code = status === 503 ? 'service_unavailable' : 'invalid_lifecycle_transition' }
      else if (state.report.status !== 'under_review') status = 409
      else {
        state.report.status = body.consequence === 'none' ? 'dismissed' : 'actioned'
        if (body.consequence !== 'none') state.active = false
        state.decision = { id: f.noteId, reportId: f.reportId, consequence: body.consequence, reasonCode: body.reasonCode, outcomes: { publication: body.consequence === 'none' ? 'unchanged' : 'removed', account: body.consequence === 'removePublicationAndSuspendResponsibleAccount' ? 'suspended' : 'unchanged' }, decidedAt: f.timestamp }
        output = state.decision; status = 201
      }
    } else if (path === '/api/me/profile') { if (method === 'PUT') state.profile = { ...state.profile, ...body }; output = state.profile }
    else if (path === '/api/me/public-profile') { state.profileActive = true; state.author = { ...state.author, handle: state.profile.handle, displayName: state.profile.displayName, biography: state.profile.biography }; output = state.author }
    else if (path.startsWith('/api/public/profiles/')) {
      if (!state.profileActive || !path.includes(`/${state.author.handle}`)) { status = 404; code = 'resource_not_found' }
      else if (path.endsWith('/publications')) output = { items: state.active && state.exists ? [{ ...publicCard(), updatedAt: f.timestamp }] : [], nextCursor: null }
      else output = state.author
    } else if (path === '/api/public/explore' || path === '/api/public/search') {
      const second = url.searchParams.has('cursor')
      output = { items: state.active && state.exists && (!second || state.pages) ? [publicCard(second ? f.mediaId : f.pubId)] : [], nextCursor: state.pages && !second ? 'synthetic-next-page' : null, rankingMode: path.endsWith('search') ? 'lexical_fuzzy' : url.searchParams.get('sort'), semanticAvailable: false, exhaustive: false }
    } else if (path === `/api/public/publications/${f.pubId}`) {
      if (!state.active || !state.exists || !state.profileActive) { status = 404; code = 'resource_not_found' }
      else output = { ...f.article, title: state.publication.title, markdown: state.publication.markdown, media: state.publicMedia, author: state.author, ...(state.authenticated ? { viewerLiked: state.liked } : { viewerLiked: undefined }), engagement: { ...f.article.engagement, likeCount: state.liked ? 5 : 4 } }
    } else if (path.includes('/media/') && path.endsWith('/content')) {
      if (!state.active) { status = 404; code = 'resource_not_found' }
      else { await route.fulfill({ status: 200, contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=', 'base64') }); return }
    } else if (path.endsWith('/like')) {
      state.likeStarted = true
      const admitted = state.authenticated
      if (state.holdLike) await new Promise<void>(resolve => { state.releaseLike = resolve })
      if (state.likeError) { status = state.likeError; code = 'service_unavailable' }
      else if (!admitted || !state.eligible) { status = 401; code = 'invalid_credentials' }
      else if (!state.active) { status = 404; code = 'resource_not_found' }
      else { state.liked = method === 'PUT'; status = 204 }
    } else if (path === `/api/public/publications/${f.pubId}/reports`) {
      if (state.reportError) { status = state.reportError; code = status === 429 ? 'rate_limited' : 'invalid_credentials'; headers['Retry-After'] = '30' }
      else { status = 201; output = { id: f.reportId, publicationId: f.pubId, category: body.category, description: body.description, status: 'open', submittedAt: f.timestamp }; headers.Location = `/api/moderation/reports/${f.reportId}` }
    } else if (path === '/api/me/publications') output = { items: state.exists ? [ownerView()] : [], nextCursor: null }
    else if (path === `/api/me/publications/${f.pubId}/source-status`) output = { ...f.status, sourceUsable: state.sourceUsable, updatePublicCopyReady: state.sourceUsable, driftedSincePublication: state.sourceChanged }
    else if (path === `/api/me/publications/${f.pubId}` && method === 'GET') { output = ownerView(); headers.ETag = `"p${state.publicationRevision}"` }
    else if (path === '/api/me/security') output = { email: 'synthetic@example.test', passwordConfigured: true, mfaState: 'disabled', oidcLinks: [] }
    else if (path === '/api/me/note-preferences') output = { defaultAiEnabledForNewNotes: false }
    else if (path === '/api/notes') output = { items: [state.note], nextCursor: null }
    else if (path === `/api/notes/${f.noteId}`) {
      if (method === 'PUT') { if (request.headers()['if-match'] !== `"n${state.noteRevision}"`) { status = 412; code = 'stale_write' } else { state.note = { ...state.note, ...body }; state.noteRevision++; state.sourceChanged = true } }
      output = state.note; headers.ETag = `"n${state.noteRevision}"`
    } else if (path.endsWith('/attachments')) output = { items: state.attachments, nextCursor: null }
    else if (path.endsWith('/versions')) output = { items: [], nextCursor: null }
    else if (path.endsWith('/publication-preview')) {
      if (state.previewError) { status = state.previewError; code = status === 403 ? 'recent_authentication_required' : 'stale_write' }
      else if (!state.authenticated || !state.eligible) { status = 401; code = 'invalid_credentials' }
      else if (!state.profileActive) { status = 409; code = 'public_profile_required' }
      else if (!state.sourceUsable) status = 409
      else if (request.headers()['if-match'] !== `"n${state.noteRevision}"`) { status = 412; code = 'stale_write' }
      else { state.previewNoteRevision = state.noteRevision; state.previewSelection = body.selectedAttachmentIds; state.previewGeneration++; output = { ...f.preview, title: state.note.title, markdown: state.note.markdown, media: state.attachments.filter(a => body.selectedAttachmentIds.includes(a.id)).map(a => ({ attachmentId: a.id, mediaKind: a.mediaKind, mediaType: a.mediaType, displayName: a.displayFilename, sizeBytes: a.sizeBytes })), previewFingerprint: `synthetic-preview-${state.previewGeneration}` } }
    } else if (path.endsWith('/unpublish')) {
      if (request.headers()['if-match'] !== `"p${state.publicationRevision}"`) { status = 412; code = 'stale_write' }
      else { state.active = false; state.publicationRevision++; output = ownerView(); headers.ETag = `"p${state.publicationRevision}"` }
    } else if (path.endsWith('/publication') || (path === `/api/me/publications/${f.pubId}` && method === 'PUT') || path.endsWith('/republish')) {
      const create = path.endsWith('/publication')
      if (state.commitError) { status = state.commitError; code = status === 401 ? 'invalid_credentials' : status === 412 ? 'stale_write' : 'invalid_lifecycle_transition' }
      else if (request.headers()['if-match'] !== (create ? `"n${state.noteRevision}"` : `"p${state.publicationRevision}"`)) { status = 412; code = 'stale_write' }
      else if (state.previewNoteRevision !== state.noteRevision || body.previewFingerprint !== `synthetic-preview-${state.previewGeneration}` || JSON.stringify(body.selectedAttachmentIds) !== JSON.stringify(state.previewSelection)) status = 409
      else if (!state.sourceUsable || (create && state.exists)) status = 409
      else {
        state.exists = true; state.active = true; state.sourceChanged = false; state.publicationRevision++
        state.publication = { ...state.publication, title: state.note.title, markdown: state.note.markdown }
        state.publicMedia = state.previewSelection.map(() => ({ ...f.media })); output = ownerView(); status = create ? 201 : 200; headers.ETag = `"p${state.publicationRevision}"`; if (create) headers.Location = `/api/me/publications/${f.pubId}`
      }
    } else throw new Error(`Unhandled public test contract: ${method} ${path}`)
    if (status >= 400) { headers['Content-Type'] = 'application/problem+json'; output = { type: 'about:blank', title: 'Request rejected', status, code, instance: path, traceId: `tr_${'a'.repeat(32)}` } }
    await route.fulfill({ status, headers, ...(status === 204 ? {} : { body: JSON.stringify(output) }) })
  })
  return state
}
