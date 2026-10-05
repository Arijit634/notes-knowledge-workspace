import { describe, expect, it, vi } from 'vitest'
import { ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { attachmentsApi, attachmentContentUrl, attachmentDescription, decodeAttachment, type AttachmentCore } from './AttachmentsApi'

export const syntheticAttachment: AttachmentCore = {
  id: '01990a55-9e12-7ac4-8f5b-31aa4a91d402', noteId: '01990a55-9e12-7ac4-8f5b-31aa4a91d401',
  mediaKind: 'image', displayFilename: 'synthetic.png', mediaType: 'image/png', sizeBytes: 1024,
  width: 32, height: 24, durationSeconds: null, pageCount: null, storageState: 'stored', validationState: 'accepted',
  cleanupState: 'retained', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
}
function client(body: unknown = syntheticAttachment, status = 200, etag: string | null = '"a1"', location: string | null = null) {
  const request = vi.fn(async () => ({ body, metadata: { status, etag, location } }))
  return { auth: { api: { request } } as unknown as AuthRuntime, request }
}
describe('Attachment protocol', () => {
  it.each([['image', 'image/jpeg'], ['audio', 'audio/wav'], ['video', 'video/mp4'], ['pdf', 'application/pdf']])(
    'decodes %s with nullable metadata', (mediaKind, mediaType) => {
      expect(decodeAttachment({ ...syntheticAttachment, mediaKind, mediaType, width: null, height: null })).toMatchObject({ mediaKind, mediaType })
    })
  it.each([
    { mediaKind: 'document' }, { mediaKind: ['image'] }, { mediaType: 'text/html' }, { mediaKind: 'audio' },
    { sizeBytes: -1 }, { sizeBytes: 1.5 }, { sizeBytes: Number.MAX_SAFE_INTEGER + 1 }, { width: '32' },
    { height: -1 }, { durationSeconds: Infinity }, { pageCount: 1.2 }, { id: null }, { noteId: '' },
    { storageState: 'public' }, { validationState: 'unknown' }, { cleanupState: 'pending' }, { createdAt: 1 },
    { objectReference: 'forbidden' },
  ])('rejects invalid or unexpected protocol shape %j', change => {
    expect(() => decodeAttachment({ ...syntheticAttachment, ...change })).toThrow(ApiProtocolError)
  })
  it('rejects missing fields and nonobjects', () => {
    const { width: omitted, ...missing } = syntheticAttachment; void omitted
    for (const body of [missing, null, [], 'bad']) expect(() => decodeAttachment(body)).toThrow(ApiProtocolError)
  })
  it.each([null, 'W/"a1"', '*', 'a1'])('rejects invalid detail and upload ETag %s', async etag => {
    const { auth } = client(syntheticAttachment, 200, etag)
    await expect(attachmentsApi.detail(auth, syntheticAttachment.noteId, syntheticAttachment.id)).rejects.toThrow(ApiProtocolError)
    const upload = client(syntheticAttachment, 201, etag)
    await expect(attachmentsApi.upload(upload.auth, syntheticAttachment.noteId, new File(['x'], 'synthetic.png'))).rejects.toThrow(ApiProtocolError)
  })
  it('requires 201, matching parent, canonical Location and only one multipart file', async () => {
    const location = `/api/notes/${syntheticAttachment.noteId}/attachments/${syntheticAttachment.id}`
    const good = client(syntheticAttachment, 201, '"a1"', location)
    const file = new File(['synthetic'], 'synthetic.png', { type: 'image/png' })
    expect((await attachmentsApi.upload(good.auth, syntheticAttachment.noteId, file)).etag).toBe('"a1"')
    const options = good.request.mock.calls[0] as unknown as [string, string, { multipart: FormData }]
    expect(Array.from(options[2].multipart.keys())).toEqual(['file'])
    expect(options[2].multipart.get('file')).toBe(file)
    for (const candidate of [client(syntheticAttachment, 200, '"a1"', location), client(syntheticAttachment, 201, '"a1"', '/wrong'),
      client({ ...syntheticAttachment, noteId: 'other' }, 201, '"a1"', location)]) {
      await expect(attachmentsApi.upload(candidate.auth, syntheticAttachment.noteId, file)).rejects.toThrow(ApiProtocolError)
    }
  })
  it('decodes cursor pages, rejects foreign rows and malformed pages', async () => {
    const good = client({ items: [syntheticAttachment], nextCursor: 'opaque+/=' })
    expect((await attachmentsApi.list(good.auth, syntheticAttachment.noteId, 'opaque+/=')).items).toHaveLength(1)
    expect(good.request).toHaveBeenCalledWith('GET', expect.stringContaining('?cursor=opaque%2B%2F%3D'), expect.anything())
    for (const body of [{ items: [], nextCursor: 1 }, { items: [{ ...syntheticAttachment, noteId: 'other' }], nextCursor: null }]) {
      await expect(attachmentsApi.list(client(body).auth, syntheticAttachment.noteId, null)).rejects.toThrow(ApiProtocolError)
    }
  })
  it('requires exact detail identity and 204 empty delete response', async () => {
    await expect(attachmentsApi.detail(client().auth, syntheticAttachment.noteId, 'other')).rejects.toThrow(ApiProtocolError)
    for (const candidate of [client({}, 204), client(null, 200)]) await expect(attachmentsApi.remove(candidate.auth,
      syntheticAttachment.noteId, syntheticAttachment.id, '"a1"')).rejects.toThrow(ApiProtocolError)
    const good = client(null, 204)
    await attachmentsApi.remove(good.auth, syntheticAttachment.noteId, syntheticAttachment.id, '"a1"')
    expect(good.request).toHaveBeenCalledWith('DELETE', expect.any(String), expect.objectContaining({ ifMatch: '"a1"', retryOnCsrfInvalid: false }))
  })
  it('uses only encoded opaque route IDs, never filenames', () => {
    expect(attachmentContentUrl('a/b', 'c?d')).toBe('/api/notes/a%2Fb/attachments/c%3Fd/content')
  })
  it('formats metadata deterministically without locale or technical states', () => {
    expect(attachmentDescription(syntheticAttachment)).toBe('Image · 1.0 KB · 32×24')
    expect(attachmentDescription({ ...syntheticAttachment, mediaKind: 'pdf', sizeBytes: 2097152, pageCount: 12 })).toBe('PDF · 2.0 MB · 12 pages')
    expect(attachmentDescription({ ...syntheticAttachment, mediaKind: 'audio', sizeBytes: 3, durationSeconds: 42.4 })).toBe('Audio · 3 B · 42 sec')
  })
})
