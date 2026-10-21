import type { AuthRuntime } from '../auth/AuthRuntime'
import { record, text, integer } from '../../app/api/Contract'

export type PrivateProfile = { displayName: string; biography: string; handle: string | null;
  updatedAt: string | null; avatar: null | { mediaType: string; width: number; height: number; byteSize: number; createdAt: string } }
export function profileView(value: unknown): PrivateProfile {
  const p = record(value), a = p.avatar === null ? null : record(p.avatar)
  return { displayName: text(p.displayName, 200), biography: text(p.biography, 1000),
    handle: p.handle === null ? null : text(p.handle, 30), updatedAt: p.updatedAt === null ? null : text(p.updatedAt, 64),
    avatar: a && { mediaType: text(a.mediaType, 40), width: integer(a.width), height: integer(a.height), byteSize: integer(a.byteSize), createdAt: text(a.createdAt, 64) } }
}
export const profileApi = {
  read: async (auth: AuthRuntime, signal?: AbortSignal) => profileView((await auth.api.request('GET', '/api/me/profile', { signal })).body),
  save: async (auth: AuthRuntime, input: Pick<PrivateProfile, 'displayName' | 'biography' | 'handle'>) =>
    profileView((await auth.api.request('PUT', '/api/me/profile', { json: input, retryOnCsrfInvalid: false })).body),
  avatar: async (auth: AuthRuntime, file: File, signal?: AbortSignal) => {
    const multipart = new FormData(); multipart.append('file', file)
    return profileView((await auth.api.request('PUT', '/api/me/profile/avatar', { multipart, signal, retryOnCsrfInvalid: false })).body)
  },
  removeAvatar: async (auth: AuthRuntime) => { await auth.api.request('DELETE', '/api/me/profile/avatar', { retryOnCsrfInvalid: false }) },
}
