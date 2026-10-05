import { describe, expect, it, vi } from 'vitest'
import { CsrfManager } from '../../app/security/CsrfManager'
import { ApiProblemError, ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'
import { syntheticAttachment } from './AttachmentUpload.test-support'
import { AttachmentXhrDouble } from './AttachmentUpload.test-support'
import { attachmentUploadPath, AttachmentUploadAbortedError, uploadAttachment } from './AttachmentUploadTransport'

const noteId = syntheticAttachment.noteId, path = `/api/notes/${noteId}/attachments`
const location = `${path}/${syntheticAttachment.id}`
const file = new File(['synthetic'], 'synthetic.png', { type: 'image/png' })
const json = (body: unknown = syntheticAttachment, status = 201, headers: Record<string, string> = { ETag: '"a1"', Location: location }) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
function begin(options: Parameters<typeof uploadAttachment>[3] = {}) {
  const csrf = new CsrfManager(); csrf.set('synthetic-proof')
  const xhr = new AttachmentXhrDouble(), factory = vi.fn(() => xhr.asXhr())
  const result = uploadAttachment(csrf, noteId, file, options, factory)
  return { xhr, result, factory }
}
async function started(xhr: AttachmentXhrDouble) { await vi.waitFor(() => expect(xhr.send).toHaveBeenCalledTimes(1)) }

describe('Attachment XHR upload', () => {
  it('uses only its canonical same-origin POST with one file and memory CSRF, without a manual boundary', async () => {
    const { xhr, result } = begin(); await started(xhr)
    expect(attachmentUploadPath(noteId)).toBe(path)
    expect(xhr.open).toHaveBeenCalledWith('POST', path, true)
    expect(xhr.withCredentials).toBe(false) // same-origin cookies; not cross-origin credential inclusion
    expect(xhr.headers['X-CSRF-TOKEN']).toBe('synthetic-proof')
    expect(xhr.headers).not.toHaveProperty('Content-Type')
    expect(Array.from(xhr.body!.keys())).toEqual(['file']); expect(xhr.body!.get('file')).toBe(file)
    await xhr.respond(json()); expect(await result).toEqual({ value: syntheticAttachment, etag: '"a1"' })
  })
  it.each(['https://other.invalid/api/notes/x/attachments', '//other.invalid/api/notes/x/attachments', '/api/notes/x/attachments',
    'a\\b', 'a\nb', 'a\u0000b', 'a#b', 'a?b', '.', '..', '', 'a/b'])('rejects invalid endpoint input %j before open/send or CSRF I/O', async note => {
    const loader = vi.fn(), factory = vi.fn()
    await expect(uploadAttachment(new CsrfManager(loader), note, file, {}, factory)).rejects.toBeInstanceOf(ApiProtocolError)
    expect(factory).not.toHaveBeenCalled(); expect(loader).not.toHaveBeenCalled()
  })
  it.each(['', 'not a URL', 'https://other.invalid'+path, new URL('/api/notes/other/attachments', window.location.origin).href,
    new URL(path+'?unexpected=1', window.location.origin).href, new URL(path+'#unexpected', window.location.origin).href])(
    'rejects unexpected final response URL %j as post-response redirect detection', async url => {
      const { xhr, result } = begin(), assertion = expect(result).rejects.toBeInstanceOf(ApiProtocolError)
      await started(xhr); await xhr.respond(json(), url); await assertion
    })
  it('reports only real computable progress and supports indeterminate progress', async () => {
    const progress = vi.fn(), complete = vi.fn(), { xhr, result } = begin({ onProgress: progress, onUploadComplete: complete })
    await started(xhr); xhr.progress(42, 100); xhr.progress(0, 0, false); xhr.progress(101, 100); xhr.progress(100, 100); xhr.uploaded()
    expect(progress.mock.calls).toEqual([[42], [null], [null], [100]]); expect(complete).toHaveBeenCalledTimes(1)
    await xhr.respond(json()); await result
    xhr.progress(0, 100); expect(progress).toHaveBeenCalledTimes(4)
  })
  it('aborts the XHR with a distinct client-stop error, without claiming server rollback', async () => {
    const controller = new AbortController(), { xhr, result } = begin({ signal: controller.signal })
    const assertion = expect(result).rejects.toBeInstanceOf(AttachmentUploadAbortedError)
    await started(xhr); controller.abort(); await assertion; expect(xhr.abort).toHaveBeenCalledTimes(1)
    await xhr.respond(json()); expect(xhr.onload).toBeNull()
  })
  it('does not dispatch an already-aborted upload', async () => {
    const controller = new AbortController(); controller.abort()
    const { result, factory } = begin({ signal: controller.signal })
    await expect(result).rejects.toBeInstanceOf(AttachmentUploadAbortedError); expect(factory).not.toHaveBeenCalled()
  })
  it.each([413, 415, 422, 429, 503, 403])('decodes typed %s without replaying', async status => {
    const { xhr, result } = begin(), assertion = expect(result).rejects.toBeInstanceOf(ApiProblemError)
    await started(xhr); await xhr.respond(new Response(JSON.stringify({ type: 'about:blank', title: 'Safe', status, instance: path,
      code: status === 403 ? 'csrf_invalid' : 'request_failed', traceId: `tr_${'a'.repeat(32)}`, detail: 'untrusted' }),
      { status, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '5' } }))
    await assertion; expect(xhr.send).toHaveBeenCalledTimes(1)
    await result.catch(error => { expect(error.problem).not.toHaveProperty('detail'); expect(error.metadata.retryAfter).toBe('5') })
  })
  it.each([
    () => json(syntheticAttachment, 200), () => json(syntheticAttachment, 201, { Location: location }),
    () => json(syntheticAttachment, 201, { ETag: 'W/"a1"', Location: location }),
    () => json(syntheticAttachment, 201, { ETag: '"a1"', Location: '/wrong' }),
    () => json({ ...syntheticAttachment, noteId: 'other' }), () => json({ ...syntheticAttachment, mediaType: 'text/html' }),
    () => new Response('{bad', { status: 201, headers: { 'Content-Type': 'application/json' } }),
    () => new Response('{}', { status: 201, headers: { 'Content-Type': 'text/html' } }),
  ])('rejects malformed/noncanonical upload success', async response => {
    const { xhr, result } = begin(), assertion = expect(result).rejects.toBeInstanceOf(ApiProtocolError)
    await started(xhr); await xhr.respond(response()); await assertion
  })
  it.each(['onerror', 'ontimeout'] as const)('contains transport %s', async event => {
    const { xhr, result } = begin(), assertion = expect(result).rejects.toBeInstanceOf(ApiProtocolError)
    await started(xhr); xhr[event]?.(new ProgressEvent(event)); await assertion
  })
})
