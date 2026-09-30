import { ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'
import type { Etagged, CursorPage } from '../../app/api/ApiClient'
import type { AuthRuntime } from '../auth/AuthRuntime'

export type NoteCore = {
  id: string
  title: string
  markdown: string
  lifecycle: 'active' | 'archived' | 'trashed'
  pinned: boolean
  tags: string[]
  aiEnabled: boolean
  createdAt: string
  updatedAt: string
}

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function note(value: unknown): NoteCore {
  if (!object(value) || typeof value.id !== 'string' || typeof value.title !== 'string'
      || typeof value.markdown !== 'string' || !['active', 'archived', 'trashed'].includes(String(value.lifecycle))
      || typeof value.pinned !== 'boolean' || typeof value.aiEnabled !== 'boolean'
      || !Array.isArray(value.tags) || !value.tags.every(tag => typeof tag === 'string')
      || typeof value.createdAt !== 'string' || typeof value.updatedAt !== 'string') throw new ApiProtocolError()
  return value as NoteCore
}

function etagged(body: unknown, etag: string | null): Etagged<NoteCore> {
  if (!etag || !/^"[A-Za-z0-9_-]+"$/.test(etag)) throw new ApiProtocolError()
  return { value: note(body), etag }
}

export const notesApi = {
  preference: async (auth: AuthRuntime): Promise<boolean> => {
    const body: unknown = (await auth.api.request<unknown>('GET', '/api/me/note-preferences')).body
    if (!object(body) || typeof body.defaultAiEnabledForNewNotes !== 'boolean') throw new ApiProtocolError()
    return body.defaultAiEnabledForNewNotes
  },
  setPreference: async (auth: AuthRuntime, enabled: boolean): Promise<boolean> => {
    const body: unknown = (await auth.api.request<unknown>('PUT', '/api/me/note-preferences',
      { json: { defaultAiEnabledForNewNotes: enabled } })).body
    if (!object(body) || typeof body.defaultAiEnabledForNewNotes !== 'boolean') throw new ApiProtocolError()
    return body.defaultAiEnabledForNewNotes
  },
  create: async (auth: AuthRuntime, title: string, markdown: string, aiEnabled: boolean) => {
    const result = await auth.api.request<unknown>('POST', '/api/notes', { json: { title, markdown, aiEnabled } })
    const entry = etagged(result.body, result.metadata.etag)
    if (result.metadata.status !== 201 || result.metadata.location !== `/api/notes/${entry.value.id}`) {
      throw new ApiProtocolError()
    }
    return entry
  },
  get: async (auth: AuthRuntime, id: string): Promise<Etagged<NoteCore>> => {
    const result = await auth.api.request<unknown>('GET', `/api/notes/${encodeURIComponent(id)}`)
    return etagged(result.body, result.metadata.etag)
  },
  save: async (auth: AuthRuntime, id: string, etag: string, title: string, markdown: string) => {
    const result = await auth.api.request<unknown>('PUT', `/api/notes/${encodeURIComponent(id)}`,
      { json: { title, markdown }, ifMatch: etag, retryOnCsrfInvalid: false })
    return etagged(result.body, result.metadata.etag)
  },
  list: async (auth: AuthRuntime, cursor: string | null): Promise<CursorPage<NoteCore>> => {
    const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''
    const body: unknown = (await auth.api.request<unknown>('GET', `/api/notes${query}`)).body
    if (!object(body) || !Array.isArray(body.items)
        || !(body.nextCursor === null || typeof body.nextCursor === 'string')) throw new ApiProtocolError()
    return { items: body.items.map(note), nextCursor: body.nextCursor }
  },
}
