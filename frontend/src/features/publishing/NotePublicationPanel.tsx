import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { publishingApi } from './PublishingApi'
import { publicationKeys, sourceLinks } from './PublicationCache'
import { PublicationComposer } from './PublicationComposer'
import { featureError } from '../../app/ui/featureError'
import { noteKeys } from '../notes/NotesKeys'

export function NotePublicationPanel({ auth, noteId, etag, eligible, onModalChange }: { auth: AuthRuntime; noteId: string; etag: string; eligible: boolean; onModalChange: (open: boolean) => void }) {
  const [open, setOpen] = useState(false), [id, setId] = useState<string | undefined>(() => sourceLinks(auth).get(noteId))
  const opener = useRef<HTMLElement | null>(null)
  const owner = useQuery({ queryKey: publicationKeys.detail(auth, id ?? ''), queryFn: ({ signal }) => publishingApi.owner(auth, id!, signal), enabled: !!id, retry: false }, auth.queries)
  const status = useQuery({ queryKey: publicationKeys.status(auth, id ?? ''), queryFn: ({ signal }) => publishingApi.status(auth, id!, signal), enabled: !!id, retry: false }, auth.queries)
  useEffect(() => { onModalChange(open); return () => onModalChange(false) }, [open, onModalChange])
  return <section className="notes-tool" aria-label="Public copy"><h2>Public copy</h2><p>Publish a separate public copy. Private edits stay private until you update it.</p>
    {!eligible && <p>Save your changes before publishing.</p>}
    {status.data && <p role="status">{!status.data.sourceUsable ? 'Source is not currently publishable.' : status.data.driftedSincePublication ? 'Private source changed. The public copy is unchanged.' : 'Public copy matches the saved source.'}</p>}
    {id ? <><Link to={`/publications/${id}`}>Manage public copy</Link>{owner.data?.value.availability === 'active' && <Link to={`/publication/${id}`}>View published copy</Link>}
      <button className="button-secondary" disabled={!eligible || !owner.data || !status.data?.updatePublicCopyReady || owner.data.value.availability === 'removed'} onClick={e => { opener.current = e.currentTarget; setOpen(true) }}>{owner.data?.value.availability === 'unpublished' ? 'Republish' : 'Update public copy'}</button></>
      : <><button className="button-secondary" disabled={!eligible} onClick={e => { opener.current = e.currentTarget; setOpen(true) }}>Publish</button><Link to="/publications">Already published? Open your publications</Link></>}
    {(owner.isError || status.isError) && <p role="alert">{featureError(owner.error ?? status.error)}</p>}
    {open && <PublicationComposer auth={auth} noteId={noteId} noteEtag={etag} eligible={eligible} publication={owner.data} opener={opener.current} onRefresh={() => Promise.all([auth.queries.invalidateQueries({ queryKey: noteKeys.core(auth.session.viewerScope, noteId), exact: true }), ...(id ? [owner.refetch(), status.refetch()] : [])])} onClose={() => setOpen(false)} onComplete={newId => { setId(newId); setOpen(false) }} />}
  </section>
}
