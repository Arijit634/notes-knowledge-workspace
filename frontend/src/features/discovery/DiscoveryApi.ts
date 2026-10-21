import type { AuthRuntime } from '../auth/AuthRuntime'
import { integer, record, text } from '../../app/api/Contract'
import { ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'
import { engagement, page, publicAuthor, tags, validHandle, type PublicAuthor } from '../publishing/PublishingApi'
import { uuid } from '../../app/api/Contract'

export type PublicCard = { id: string; title: string; tags: string[]; publishedAt: string; author?: PublicAuthor; engagement?: { likeCount: number; approximateViewCount: number } }
function card(value: unknown, authorRequired = true): PublicCard {
  const p = record(value)
  if (authorRequired && (p.author === undefined || p.engagement === undefined)) throw new ApiProtocolError()
  return { id: uuid(p.id), title: text(p.title, 500), tags: tags(p.tags), publishedAt: text(p.publishedAt, 64), ...(p.author === undefined ? {} : { author: publicAuthor(p.author) }), ...(p.engagement === undefined ? {} : { engagement: engagement(p.engagement) }) }
}
export const discoveryApi = {
  explore: async (auth: AuthRuntime, sort: 'latest' | 'trending', cursor: string | null, signal?: AbortSignal) => page((await auth.api.request('GET', `/api/public/explore?sort=${sort}&limit=20` + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''), { signal })).body, value => card(value)),
  search: async (auth: AuthRuntime, q: string, tag: string, cursor: string | null, signal?: AbortSignal) => page((await auth.api.request('GET', '/api/public/search?' + new URLSearchParams({ q, tag, limit: '20', ...(cursor ? { cursor } : {}) }), { signal })).body, value => card(value)),
  author: async (auth: AuthRuntime, handle: string, signal?: AbortSignal) => {
    if (!validHandle(handle)) throw new ApiProtocolError()
    const a = publicAuthor((await auth.api.request('GET', `/api/public/profiles/${handle}`, { signal })).body)
    if (a.handle !== handle) throw new ApiProtocolError()
    return a
  },
  authorPublications: async (auth: AuthRuntime, handle: string, cursor: string | null, signal?: AbortSignal) => {
    if (!validHandle(handle)) throw new ApiProtocolError()
    return page((await auth.api.request('GET', `/api/public/profiles/${handle}/publications?limit=20` + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''), { signal })).body, value => card(value, false))
  },
  activate: async (auth: AuthRuntime) => publicAuthor((await auth.api.request('PUT', '/api/me/public-profile', { retryOnCsrfInvalid: false })).body),
  like: async (auth: AuthRuntime, id: string, desired: boolean) => {
    const r = await auth.api.request(desired ? 'PUT' : 'DELETE', `/api/public/publications/${uuid(id)}/like`, { retryOnCsrfInvalid: false })
    if (r.metadata.status !== 204 || r.body !== null) throw new ApiProtocolError()
  },
  report: async (auth: AuthRuntime, id: string, category: string, description: string) => {
    const r = await auth.api.request('POST', `/api/public/publications/${uuid(id)}/reports`, { json: { category, description }, retryOnCsrfInvalid: false })
    // Location is a moderator-only resource. Never navigate or follow it as a reporter.
    if (r.metadata.status !== 201) throw new ApiProtocolError()
    const p = record(r.body); uuid(p.id)
  },
}
export const sizeLabel = (size: number) => size < 1024 * 1024 ? `${Math.ceil(integer(size) / 1024)} KB` : `${(integer(size) / 1024 / 1024).toFixed(1)} MB`
