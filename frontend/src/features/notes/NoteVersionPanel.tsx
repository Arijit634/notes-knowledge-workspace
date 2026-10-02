import { useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { notesApi, type NoteVersion } from './NotesApi'
import { noteKeys } from './NotesKeys'
import { MarkdownView } from './MarkdownView'

export function NoteVersionPanel({ auth, noteId, restoreBlocked, onRestore }: {
  auth: AuthRuntime; noteId: string; restoreBlocked: boolean; onRestore: (version: NoteVersion, invoker: HTMLButtonElement) => void
}) {
  const [selected, setSelected] = useState<string | null>(null)
  const scope = auth.session.viewerScope
  const history = useInfiniteQuery({ queryKey: noteKeys.versions(scope, noteId),
    queryFn: ({ pageParam }) => notesApi.versions(auth, noteId, pageParam), initialPageParam: null as string | null,
    getNextPageParam: page => page.nextCursor ?? undefined, retry: false }, auth.queries)
  const detail = useQuery({ queryKey: noteKeys.version(scope, noteId, selected ?? ''),
    queryFn: () => notesApi.version(auth, noteId, selected!), enabled: selected !== null, retry: false }, auth.queries)
  const versions = [...new Map(history.data?.pages.flatMap(page => page.items)
    .map(version => [version.id, version] as const) ?? []).values()]
  return <section className="notes-history" aria-label="Version history">
    <h2>Version history</h2>
    <p>Retained saved checkpoints, not every Save. Inspecting history does not change your draft.</p>
    {history.isPending && <p role="status">Loading history…</p>}
    {history.isError && <p role="alert">History is unavailable. <button onClick={() => void history.refetch()}>Retry history</button></p>}
    {!history.isPending && !history.isError && versions.length === 0 && <p>No retained checkpoints yet.</p>}
    <ul className="notes-timeline">{versions.map(version => <li key={version.id}>
      <button type="button" className="button-secondary" aria-pressed={selected === version.id}
        onClick={() => setSelected(version.id)}>Inspect {version.title}</button>
      <p><time dateTime={version.createdAt}>{new Date(version.createdAt).toLocaleString()}</time>
        {' · '}Revision {version.sourceRevision}{' · '}{version.checkpointKind === 'pre_restore'
          ? 'Before restore' : version.checkpointKind === 'publication' ? 'Publication checkpoint' : 'Saved checkpoint'}</p>
    </li>)}</ul>
    {history.hasNextPage && <button type="button" className="button-secondary" disabled={history.isFetchingNextPage}
      onClick={() => void history.fetchNextPage()}>Load older checkpoints</button>}
    {selected && <section className="notes-checkpoint" aria-label="Selected checkpoint">
      {detail.isPending && <p role="status">Loading checkpoint…</p>}
      {detail.isError && <p role="alert">This checkpoint is unavailable. <button onClick={() => void detail.refetch()}>Retry checkpoint</button></p>}
      {detail.data && !detail.isError && <><h3>{detail.data.title}</h3><MarkdownView markdown={detail.data.markdown} />
        <button type="button" disabled={restoreBlocked} onClick={event => onRestore(detail.data!, event.currentTarget)}>Restore this checkpoint</button></>}
    </section>}
  </section>
}
