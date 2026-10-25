import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { useInfiniteQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { notesApi } from './NotesApi'
import { noteKeys } from './NotesKeys'
import { Icon } from '../../app/ui/Icon'

/** The library context stays in memory and uses the same owner-scoped collection. */
export function NoteNavigator({ auth, selectedId }: { auth: AuthRuntime; selectedId: string }) {
  const [wide, setWide] = useState(() => window.matchMedia?.('(min-width: 1440px)').matches ?? false)
  useEffect(() => {
    const media = window.matchMedia?.('(min-width: 1440px)')
    if (!media) return
    const update = () => setWide(media.matches)
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])
  const notes = useInfiniteQuery({ queryKey: noteKeys.list(auth.session.viewerScope, { lifecycle: 'active', sort: 'updatedAtDesc' }),
    queryFn: ({ pageParam }) => notesApi.list(auth, pageParam, 'active'), initialPageParam: null as string | null,
    getNextPageParam: last => last.nextCursor ?? undefined, retry: false, enabled: wide }, auth.queries)
  const items = [...new Map(notes.data?.pages.flatMap(page => page.items).map(note => [note.id, note] as const) ?? []).values()]
  if (!wide) return null
  return <aside className="studio-note-navigator" aria-label="Note navigation"><header><h2>Your notes</h2><Link to="/notes" aria-label="Open notes library"><Icon name="notes" /></Link></header>
    <p className="notes-help">Recently updated · Active</p>
    {notes.isPending && wide && <p role="status">Loading notes…</p>}
    {notes.isError && <p role="status">The note list is unavailable. Your editor is still here.</p>}
    <nav aria-label="Recently updated notes">{items.map(note => <Link key={note.id} to={`/notes/${note.id}`} aria-current={selectedId === note.id ? 'page' : undefined}>
      <strong>{note.title}</strong><span>{note.markdown.slice(0, 1000).replace(/[#*_`>]/g, '').replace(/\s+/g, ' ').slice(0, 100)}</span>
      {note.tags.length > 0 && <small>{note.tags.slice(0, 2).join(' · ')}</small>}
    </Link>)}</nav>
    {notes.hasNextPage && <button type="button" className="button-secondary" disabled={notes.isFetchingNextPage} onClick={() => void notes.fetchNextPage()}>More notes</button>}
  </aside>
}
