import type { AuthRuntime } from '../auth/AuthRuntime'
import type { Etagged, CursorPage } from '../../app/api/ApiClient'
import { array, flag, integer, record, text, uuid } from '../../app/api/Contract'
import { ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'

export const validId = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
export const validHandle = (value: unknown): value is string => typeof value === 'string' && /^[a-z][a-z0-9_]{2,29}$/.test(value)
export type PublicAuthor = { handle: string; displayName: string; biography: string; avatarUrl: string | null }
export function publicAuthor(value: unknown): PublicAuthor {
  const p = record(value), handle = text(p.handle, 30)
  if (!validHandle(handle)) throw new ApiProtocolError()
  const avatarUrl = p.avatarUrl === null ? null : text(p.avatarUrl, 180)
  if (avatarUrl !== null && !new RegExp(`^/api/public/profiles/${handle}/avatar/[0-9a-f-]{36}/content$`).test(avatarUrl)) throw new ApiProtocolError()
  if (avatarUrl) uuid(avatarUrl.split('/')[6])
  return { handle, displayName: text(p.displayName, 200), biography: text(p.biography, 1000), avatarUrl }
}
export type PublicMedia = { id: string; mediaKind: 'image' | 'audio' | 'video' | 'pdf'; mediaType: string; displayName: string; sizeBytes: number; contentUrl: string }
const mediaTypes = { image: ['image/jpeg', 'image/png'], audio: ['audio/wav'], video: ['video/mp4'], pdf: ['application/pdf'] }
export function mediaKind(kind: unknown, type: unknown): PublicMedia['mediaKind'] {
  if (typeof kind !== 'string' || !Object.hasOwn(mediaTypes, kind) || !mediaTypes[kind as keyof typeof mediaTypes].includes(String(type))) throw new ApiProtocolError()
  return kind as PublicMedia['mediaKind']
}
export function publicMedia(value: unknown, publicationId: string): PublicMedia {
  const m = record(value), id = uuid(m.id), contentUrl = text(m.contentUrl, 200)
  if (contentUrl !== `/api/public/publications/${publicationId}/media/${id}/content`) throw new ApiProtocolError()
  return { id, mediaKind: mediaKind(m.mediaKind, m.mediaType), mediaType: text(m.mediaType, 40), displayName: text(m.displayName, 500), sizeBytes: integer(m.sizeBytes), contentUrl }
}
export type OwnerSummary = { id: string; title: string; tags: string[]; availability: 'active' | 'unpublished' | 'removed'; publishedAt: string; updatedAt: string }
export type OwnerPublication = OwnerSummary & { markdown: string; publicUrl: string; media: PublicMedia[] }
export type PublicPublication = { id: string; title: string; markdown: string; tags: string[]; author: PublicAuthor; media: PublicMedia[]; publishedAt: string; engagement: { likeCount: number; approximateViewCount: number }; viewerLiked?: boolean }
export const tags = (value: unknown) => array(value, v => text(v, 100), 50)
export function summary(value: unknown): OwnerSummary {
  const p = record(value), availability = p.availability
  if (availability !== 'active' && availability !== 'unpublished' && availability !== 'removed') throw new ApiProtocolError()
  return { id: uuid(p.id), title: text(p.title, 500), tags: tags(p.tags), availability, publishedAt: text(p.publishedAt, 64), updatedAt: text(p.updatedAt, 64) }
}
export function ownerPublication(value: unknown): OwnerPublication {
  const p = record(value), s = summary(p), publicUrl = text(p.publicUrl, 100)
  if (publicUrl !== `/p/${s.id}`) throw new ApiProtocolError()
  return { ...s, publicUrl, markdown: text(p.markdown), media: array(p.media, m => publicMedia(m, s.id), 100) }
}
export function engagement(value: unknown) { const e = record(value); return { likeCount: integer(e.likeCount), approximateViewCount: integer(e.approximateViewCount) } }
export function publicPublication(value: unknown): PublicPublication {
  const p = record(value), id = uuid(p.id)
  return { id, title: text(p.title, 500), markdown: text(p.markdown), tags: tags(p.tags), author: publicAuthor(p.author), media: array(p.media, m => publicMedia(m, id), 100), publishedAt: text(p.publishedAt, 64), engagement: engagement(p.engagement), ...(p.viewerLiked === undefined ? {} : { viewerLiked: flag(p.viewerLiked) }) }
}
export function page<T>(value: unknown, decode: (value: unknown) => T): CursorPage<T> {
  const p = record(value)
  return { items: array(p.items, decode, 100), nextCursor: p.nextCursor === null ? null : text(p.nextCursor, 8192) }
}
export type PublicationPreview = { title: string; markdown: string; tags: string[]; sourceRevision: number; media: { attachmentId: string; mediaKind: PublicMedia['mediaKind']; mediaType: string; displayName: string; sizeBytes: number }[]; warnings: string[]; previewFingerprint: string }
export function previewView(value: unknown): PublicationPreview {
  const p = record(value)
  if (!text(p.previewFingerprint, 8192).length) throw new ApiProtocolError()
  return { title: text(p.title, 500), markdown: text(p.markdown), tags: tags(p.tags), sourceRevision: integer(p.sourceRevision), warnings: array(p.warnings, v => text(v, 2000), 20), previewFingerprint: text(p.previewFingerprint, 8192), media: array(p.media, v => { const m = record(v); return { attachmentId: uuid(m.attachmentId), mediaKind: mediaKind(m.mediaKind, m.mediaType), mediaType: text(m.mediaType, 40), displayName: text(m.displayName, 500), sizeBytes: integer(m.sizeBytes) } }, 100) }
}
function etag(value: string | null): string { if (!value || !/^"[A-Za-z0-9_-]+"$/.test(value)) throw new ApiProtocolError(); return value }
const ownerPath = (id: string) => `/api/me/publications/${uuid(id)}`
export const publishingApi = {
  list: async (auth: AuthRuntime, cursor: string | null, signal?: AbortSignal) => page((await auth.api.request('GET', '/api/me/publications?limit=20' + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''), { signal })).body, summary),
  owner: async (auth: AuthRuntime, id: string, signal?: AbortSignal): Promise<Etagged<OwnerPublication>> => {
    const r = await auth.api.request('GET', ownerPath(id), { signal }), value = ownerPublication(r.body)
    if (value.id !== id) throw new ApiProtocolError()
    return { value, etag: etag(r.metadata.etag) }
  },
  status: async (auth: AuthRuntime, id: string, signal?: AbortSignal) => {
    const p = record((await auth.api.request('GET', ownerPath(id) + '/source-status', { signal })).body)
    return { sourceExists: flag(p.sourceExists), sourceUsable: flag(p.sourceUsable), driftedSincePublication: flag(p.driftedSincePublication), updatePublicCopyReady: flag(p.updatePublicCopyReady) }
  },
  preview: async (auth: AuthRuntime, noteId: string, noteEtag: string, selectedAttachmentIds: string[]) => {
    const p = previewView((await auth.api.request('POST', `/api/notes/${uuid(noteId)}/publication-preview`, { ifMatch: etag(noteEtag), json: { selectedAttachmentIds }, retryOnCsrfInvalid: false })).body)
    if (p.media.length !== selectedAttachmentIds.length || new Set(p.media.map(m => m.attachmentId)).size !== p.media.length || p.media.some(m => !selectedAttachmentIds.includes(m.attachmentId))) throw new ApiProtocolError()
    return p
  },
  commit: async (auth: AuthRuntime, noteId: string, id: string | null, action: 'create' | 'update' | 'republish', validator: string, selectedAttachmentIds: string[], previewFingerprint: string): Promise<Etagged<OwnerPublication>> => {
    const path = action === 'create' ? `/api/notes/${uuid(noteId)}/publication` : ownerPath(uuid(id)) + (action === 'republish' ? '/republish' : '')
    const r = await auth.api.request(action === 'update' ? 'PUT' : 'POST', path, { ifMatch: etag(validator), json: { selectedAttachmentIds, previewFingerprint }, retryOnCsrfInvalid: false })
    if (r.metadata.status !== (action === 'create' ? 201 : 200)) throw new ApiProtocolError()
    const value = ownerPublication(r.body)
    if (value.availability !== 'active' || (action === 'create' ? r.metadata.location !== `/api/me/publications/${value.id}` : value.id !== id)) throw new ApiProtocolError()
    return { value, etag: etag(r.metadata.etag) }
  },
  unpublish: async (auth: AuthRuntime, id: string, validator: string): Promise<Etagged<OwnerPublication>> => {
    const r = await auth.api.request('POST', ownerPath(id) + '/unpublish', { ifMatch: etag(validator), retryOnCsrfInvalid: false })
    const value = ownerPublication(r.body)
    if (r.metadata.status !== 200 || value.id !== id || value.availability !== 'unpublished') throw new ApiProtocolError()
    return { value, etag: etag(r.metadata.etag) }
  },
  public: async (auth: AuthRuntime, id: string, signal?: AbortSignal) => {
    const p = publicPublication((await auth.api.request('GET', `/api/public/publications/${uuid(id)}`, { signal })).body)
    if (p.id !== id) throw new ApiProtocolError()
    return p
  },
}
