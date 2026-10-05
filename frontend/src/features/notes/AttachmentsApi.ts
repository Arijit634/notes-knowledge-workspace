import type { CursorPage, Etagged } from '../../app/api/ApiClient'
import { ApiProblemError, ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'
import type { AuthRuntime } from '../auth/AuthRuntime'

export type AttachmentCore = {
  id: string; noteId: string; mediaKind: 'image' | 'audio' | 'video' | 'pdf'
  displayFilename: string; mediaType: 'image/png' | 'image/jpeg' | 'audio/wav' | 'video/mp4' | 'application/pdf'
  sizeBytes: number; width: number | null; height: number | null; durationSeconds: number | null; pageCount: number | null
  storageState: 'pending' | 'stored' | 'failed'; validationState: 'pending' | 'accepted' | 'quarantined' | 'rejected'
  cleanupState: 'retained'; createdAt: string; updatedAt: string
}
const fields = ['id', 'noteId', 'mediaKind', 'displayFilename', 'mediaType', 'sizeBytes', 'width', 'height',
  'durationSeconds', 'pageCount', 'storageState', 'validationState', 'cleanupState', 'createdAt', 'updatedAt']
const mediaTypes: Record<AttachmentCore['mediaKind'], readonly string[]> = {
  image: ['image/png', 'image/jpeg'], audio: ['audio/wav'], video: ['video/mp4'], pdf: ['application/pdf'],
}
function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
function nullableNumber(value: unknown, integer = false): boolean {
  return value === null || (typeof value === 'number' && Number.isFinite(value) && value >= 0
    && (!integer || Number.isSafeInteger(value)))
}
export function decodeAttachment(value: unknown): AttachmentCore {
  if (!object(value) || Object.keys(value).length !== fields.length || !fields.every(key => Object.hasOwn(value, key))
      || typeof value.id !== 'string' || !value.id || typeof value.noteId !== 'string' || !value.noteId
      || typeof value.displayFilename !== 'string' || typeof value.createdAt !== 'string' || typeof value.updatedAt !== 'string'
      || typeof value.mediaKind !== 'string' || typeof value.mediaType !== 'string'
      || typeof value.storageState !== 'string' || typeof value.validationState !== 'string'
      || !Object.hasOwn(mediaTypes, value.mediaKind)
      || !mediaTypes[value.mediaKind as AttachmentCore['mediaKind']].includes(String(value.mediaType))
      || !Number.isSafeInteger(value.sizeBytes) || Number(value.sizeBytes) < 0
      || !nullableNumber(value.width, true) || !nullableNumber(value.height, true) || !nullableNumber(value.pageCount, true)
      || !nullableNumber(value.durationSeconds) || !['pending', 'stored', 'failed'].includes(String(value.storageState))
      || !['pending', 'accepted', 'quarantined', 'rejected'].includes(String(value.validationState))
      || value.cleanupState !== 'retained') throw new ApiProtocolError()
  return value as AttachmentCore
}
function path(noteId: string, attachmentId?: string): string {
  return `/api/notes/${encodeURIComponent(noteId)}/attachments${attachmentId === undefined ? '' : `/${encodeURIComponent(attachmentId)}`}`
}
export const attachmentContentUrl = (noteId: string, attachmentId: string) => `${path(noteId, attachmentId)}/content`
export function attachmentCore(body: unknown, etag: string | null, noteId: string, id?: string): Etagged<AttachmentCore> {
  const value = decodeAttachment(body)
  if (!etag || !/^"[A-Za-z0-9_-]+"$/.test(etag) || value.noteId !== noteId || (id !== undefined && value.id !== id)) {
    throw new ApiProtocolError()
  }
  return { value, etag }
}
export const attachmentsApi = {
  list: async (auth: AuthRuntime, noteId: string, cursor: string | null, signal?: AbortSignal): Promise<CursorPage<AttachmentCore>> => {
    const result = await auth.api.request<unknown>('GET', path(noteId) + (cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''), { signal })
    const body = result.body
    if (result.metadata.status !== 200 || !object(body) || !Array.isArray(body.items)
        || !(body.nextCursor === null || typeof body.nextCursor === 'string')) throw new ApiProtocolError()
    const items = body.items.map(decodeAttachment)
    if (items.some(item => item.noteId !== noteId)) throw new ApiProtocolError()
    return { items, nextCursor: body.nextCursor }
  },
  detail: async (auth: AuthRuntime, noteId: string, id: string, signal?: AbortSignal) => {
    const result = await auth.api.request<unknown>('GET', path(noteId, id), { signal })
    if (result.metadata.status !== 200) throw new ApiProtocolError()
    return attachmentCore(result.body, result.metadata.etag, noteId, id)
  },
  remove: async (auth: AuthRuntime, noteId: string, id: string, etag: string, signal?: AbortSignal) => {
    const result = await auth.api.request('DELETE', path(noteId, id), { ifMatch: etag, signal, retryOnCsrfInvalid: false })
    if (result.metadata.status !== 204 || result.body !== null) throw new ApiProtocolError()
  },
}
export function attachmentIssue(error: unknown): string {
  if (error instanceof ApiProblemError) {
    switch (error.problem.status) {
      case 404: return 'This note or attachment is no longer available.'
      case 413: return 'That file is too large.'
      case 415: return 'That file type isn’t supported.'
      case 422: return 'That file could not be accepted. Try another file.'
      case 429: return 'Too many uploads right now. Try again shortly.'
      case 503: return 'File storage is temporarily unavailable. Try again later.'
    }
  }
  return 'Could not complete this file request. Try again.'
}
export function attachmentDescription(file: AttachmentCore): string {
  const size = file.sizeBytes < 1024 ? `${file.sizeBytes} B` : file.sizeBytes < 1024 * 1024
    ? `${(file.sizeBytes / 1024).toFixed(1)} KB` : `${(file.sizeBytes / (1024 * 1024)).toFixed(1)} MB`
  const kind = { image: 'Image', audio: 'Audio', video: 'Video', pdf: 'PDF' }[file.mediaKind]
  const extra = file.mediaKind === 'pdf' && file.pageCount !== null ? `${file.pageCount} ${file.pageCount === 1 ? 'page' : 'pages'}`
    : (file.mediaKind === 'audio' || file.mediaKind === 'video') && file.durationSeconds !== null
      ? `${Math.round(file.durationSeconds)} sec` : file.width !== null && file.height !== null ? `${file.width}×${file.height}` : null
  return [kind, size, extra].filter(Boolean).join(' · ')
}
