import type { AuthRuntime } from '../auth/AuthRuntime'
import type { InfiniteData } from '@tanstack/react-query'
import type { CursorPage } from '../../app/api/ApiClient'

export const publicationKeys = {
  owner: (auth: AuthRuntime) => ['owner-publications', auth.session.viewerScope] as const,
  detail: (auth: AuthRuntime, id: string) => [...publicationKeys.owner(auth), 'detail', id] as const,
  status: (auth: AuthRuntime, id: string) => [...publicationKeys.owner(auth), 'source-status', id] as const,
  article: (auth: AuthRuntime, id: string) => ['public-article', id, auth.session.viewerScope] as const,
  collections: ['public-collections'] as const,
  author: (handle: string) => ['public-author', handle] as const,
}
// Context learned from a successful create, never guessed from content or persisted.
const sources = new WeakMap<AuthRuntime, { scope: object; notes: Map<string, string> }>()
export function sourceLinks(auth: AuthRuntime): Map<string, string> {
  let entry = sources.get(auth)
  if (!entry || entry.scope !== auth.session.viewerScope) {
    entry = { scope: auth.session.viewerScope, notes: new Map() }; sources.set(auth, entry)
    auth.sensitive.register(() => sources.delete(auth))
  }
  return entry.notes
}
export function publicationChanged(auth: AuthRuntime, id: string) {
  // Cancel without reverting so late responses cannot resurrect a superseded snapshot.
  void auth.queries.cancelQueries({ queryKey: ['public-article', id] }, { revert: false })
  auth.queries.setQueriesData({ queryKey: ['public-article', id] }, null)
  void auth.queries.cancelQueries({ queryKey: publicationKeys.collections }, { revert: false })
  auth.queries.setQueriesData<InfiniteData<CursorPage<{ id: string }>>>({ queryKey: publicationKeys.collections }, old => old && ({ ...old, pages: old.pages.map(p => ({ ...p, items: p.items.filter(i => i.id !== id) })) }))
  void auth.queries.invalidateQueries({ queryKey: ['public-article', id] })
  void auth.queries.invalidateQueries({ queryKey: publicationKeys.collections })
  void auth.queries.invalidateQueries({ queryKey: publicationKeys.owner(auth) })
}
export function sourceChanged(auth: AuthRuntime, noteId: string, retired = false) {
  const id = sourceLinks(auth).get(noteId)
  if (id && retired) publicationChanged(auth, id)
  // Without a source-link contract, conservatively retire public materializations
  // rather than guessing which cached publication belongs to the confirmed source.
  else if (retired) authorChanged(auth)
  void auth.queries.invalidateQueries({ queryKey: [...publicationKeys.owner(auth), 'source-status'] })
}
export function authorChanged(auth: AuthRuntime) {
  for (const prefix of [['public-author'], ['public-article'], publicationKeys.collections]) {
    void auth.queries.cancelQueries({ queryKey: prefix }, { revert: false })
    auth.queries.setQueriesData({ queryKey: prefix }, null)
    void auth.queries.invalidateQueries({ queryKey: prefix })
  }
}
