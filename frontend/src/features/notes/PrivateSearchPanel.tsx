import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import type { LibrarySearchRequest } from './LibrarySearch'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { array, record, text, flag, uuid } from '../../app/api/Contract'
import { ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'
import { featureError } from '../../app/ui/featureError'

export type SearchHit = { id: string; title: string; lifecycle: string; pinned: boolean; tags: string[]; updatedAt: string; snippet: string; matchLabels: string[] }
export function searchPage(value: unknown): { items: SearchHit[]; nextCursor: string | null } {
  const p = record(value)
  return { nextCursor: p.nextCursor === null ? null : text(p.nextCursor, 8192), items: array(p.items, value => {
    const r = record(value), lifecycle = text(r.lifecycle, 10)
    if (!['active', 'archived', 'trashed'].includes(lifecycle)) throw new ApiProtocolError()
    return { id: uuid(r.id), title: text(r.title, 1000), lifecycle, pinned: flag(r.pinned), tags: array(r.tags, value => text(value, 200), 50),
      updatedAt: text(r.updatedAt, 64), snippet: text(r.snippet, 8192), matchLabels: array(r.matchLabels, value => text(value, 80), 20) }
  }, 100) }
}
export function PrivateSearchPanel({ auth, lifecycle = 'active', onActiveChange, externalRequest, compact = false }: { auth: AuthRuntime; lifecycle?: string; onActiveChange?: (active: boolean) => void; externalRequest?: LibrarySearchRequest | null; compact?: boolean }) {
  const [input, setInput] = useState(''), [tags, setTags] = useState(''), [localRequest, setRequest] = useState<{ query: string; tags: string[]; serial: number } | null>(null)
  const request = externalRequest === undefined ? localRequest : externalRequest
  const serial = useRef(0), scope = auth.session.viewerScope
  useEffect(() => { onActiveChange?.(!!request); return () => onActiveChange?.(false) }, [request, onActiveChange])
  const results = useInfiniteQuery({ queryKey: ['notes', scope, 'search', request?.serial, lifecycle], enabled: !!request,
    queryFn: async ({ signal, pageParam }) => searchPage((await auth.api.request('POST', '/api/notes/search', {
      signal, json: { query: request!.query, tags: request!.tags, lifecycle, ...(pageParam ? { cursor: pageParam } : {}) }, retryOnCsrfInvalid: false })).body),
    initialPageParam: null as string | null, getNextPageParam: last => last.nextCursor ?? undefined,
    gcTime: 0, retry: false }, auth.queries)
  function submit(event: FormEvent) { event.preventDefault(); if (input.trim()) setRequest({ query: input.trim(), tags: tags.split(',').map(t => t.trim()).filter(Boolean), serial: ++serial.current }) }
  const hits = [...new Map(results.data?.pages.flatMap(page => page.items).map(hit => [hit.id, hit]) ?? []).values()]
  return <section aria-label="Ordinary saved-note search" className="studio-search-panel">{!compact && <form onSubmit={submit} className="studio-search-form">
    <label htmlFor="private-search">Search saved notes</label><input id="private-search" type="search" maxLength={256} required value={input} onChange={e => setInput(e.target.value)} placeholder="Find something you saved…" />
    <label htmlFor="private-search-tags">Tags (comma separated, optional)</label><input id="private-search-tags" maxLength={500} value={tags} onChange={e => setTags(e.target.value)} />
    <div className="actions"><button disabled={!input.trim()}>Search notes</button><button type="button" className="button-secondary" onClick={() => { setRequest(null); setInput(''); setTags('') }}>Clear search</button></div></form>}
    {compact && request && <details className="library-search-filters"><summary>Filter by tags</summary><label htmlFor="private-search-tags">Tags (comma separated)</label><input id="private-search-tags" maxLength={500} value={tags} onChange={e => setTags(e.target.value)} /><button type="button" className="button-secondary" onClick={() => auth.librarySearch.submit(tags.split(',').map(t => t.trim()).filter(Boolean))}>Apply filters</button></details>}
    {request && (results.isPending ? <p role="status">Searching saved notes…</p> : results.isError ? <p role="alert">{featureError(results.error)} <button onClick={() => void results.refetch()}>Retry search</button></p>
      : <><h2>{hits.length ? 'Search results' : 'No matching notes'}</h2><ul className="notes-list">{hits.map(hit => <li key={hit.id}><Link to={`/notes/${hit.id}`}><strong>{hit.title}</strong><p className="notes-card-snippet">{hit.snippet}</p><span className="notes-card-meta">{hit.matchLabels.join(' · ')}</span></Link></li>)}</ul>
        {results.hasNextPage && <button className="button-secondary" disabled={results.isFetchingNextPage} onClick={() => void results.fetchNextPage()}>More search results</button>}</>)}
  </section>
}
