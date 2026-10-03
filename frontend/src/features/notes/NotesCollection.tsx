import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { useInfiniteQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { notesApi, type NoteCore } from './NotesApi'
import { noteKeys } from './NotesKeys'
import { libraryMemory } from './LibraryMemory'

export function NotesCollection({ auth, compact = false, selected }: { auth: AuthRuntime; compact?: boolean; selected?: string }) {
  const memory = libraryMemory(auth), location = useLocation(), container = useRef<HTMLDivElement>(null)
  const [lifecycle, setLifecycle] = useState<NoteCore['lifecycle']>(location.state?.notesView === 'trashed' ? 'trashed' : memory.lifecycle)
  const page = useInfiniteQuery({ queryKey: noteKeys.list(auth.session.viewerScope, { lifecycle, sort: 'updatedAtDesc' }),
    queryFn: ({ pageParam }) => notesApi.list(auth, pageParam, lifecycle), initialPageParam: null as string | null,
    getNextPageParam: last => last.nextCursor ?? undefined, retry: false }, auth.queries)
  const entries = [...new Map(page.data?.pages.flatMap(part => part.items).map(item => [item.id, item] as const) ?? []).values()]
  const restored = useRef(false)
  useEffect(() => { memory.lifecycle = lifecycle }, [lifecycle, memory])
  useEffect(() => {
    const node = container.current
    if (!node || !page.data || restored.current) return
    restored.current = true
    node.scrollTop = memory.scroll
    if (!compact && memory.focused) Array.from(node.querySelectorAll<HTMLElement>('[data-note-id]')).find(item => item.dataset.noteId === memory.focused)?.focus({ preventScroll: true })
  }, [compact, memory, page.data])
  return <div className={`notes-collection${compact ? ' collection-compact' : ''}`} ref={container} onScroll={event => { memory.scroll = event.currentTarget.scrollTop }}>
    <header className="library-heading"><h1>{compact ? 'Your notes' : 'Notes'}</h1><Link className="button" to="/notes/new">{compact ? '+ New' : '+ New note'}</Link></header>
    <nav className="notes-views" aria-label="Note views">{(['active', 'archived', 'trashed'] as const).map(view => <button key={view} type="button"
      aria-label={`${view === 'active' ? 'Active' : view === 'archived' ? 'Archived' : 'Trashed'} notes`} aria-pressed={view === lifecycle}
      onClick={() => { setLifecycle(view); memory.scroll = 0; memory.focused = null }}>{view === 'active' ? 'Active' : view === 'archived' ? 'Archived' : 'Trash'}</button>)}</nav>
    {page.isPending && <div role="status" className="library-loading"><span>Loading notes…</span><i /><i /><i /></div>}
    {page.isError && <p role="alert" className="alert">Could not load notes. <button onClick={() => void page.refetch()}>Retry</button></p>}
    {!page.isPending && !page.isError && !entries.length && <div className="notes-empty"><span aria-hidden="true">▤</span><h2>{lifecycle === 'active' ? 'No notes yet' : lifecycle === 'archived' ? 'No archived notes' : 'No trashed notes'}</h2>
      {lifecycle === 'active' && <Link className="button" to="/notes/new">Create a note</Link>}</div>}
    <ul className="notes-list">{entries.map(note => <li key={note.id}><Link data-note-id={note.id} aria-current={note.id === selected ? 'page' : undefined} to={`/notes/${note.id}`}
      onClick={() => { memory.focused = note.id }}><div className="notes-row-title"><strong>{note.title}</strong>{note.pinned && <span className="pin-label">Pinned</span>}</div>
      <p className="notes-excerpt">{note.markdown.slice(0, 240).replace(/!\[[^\]]*\]\([^)]*\)/g, '').replace(/[#*_`>|]/g, '').replace(/\s+/g, ' ').trim()}</p>
      <div className="notes-row-foot"><ul className="notes-tags" aria-label="Tags">{note.tags.slice(0, 2).map(tag => <li key={tag}>{tag}</li>)}{note.tags.length > 2 && <li>+{note.tags.length - 2}</li>}</ul>
        <time dateTime={note.updatedAt}>{new Date(note.updatedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}</time></div></Link></li>)}</ul>
    {page.hasNextPage && <button type="button" className="load-more" disabled={page.isFetchingNextPage} onClick={() => void page.fetchNextPage()}>{page.isFetchingNextPage ? 'Loading…' : 'Load more'}</button>}
  </div>
}
