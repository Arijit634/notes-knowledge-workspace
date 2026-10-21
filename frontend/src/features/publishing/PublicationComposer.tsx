import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { attachmentsApi, attachmentDescription } from '../notes/AttachmentsApi'
import { profileApi } from '../profile/ProfileApi'
import { discoveryApi } from '../discovery/DiscoveryApi'
import { MarkdownView } from '../notes/MarkdownView'
import { PublicAuthorView } from '../discovery/PublicUi'
import { FormDialog } from '../../app/ui/FormDialog'
import { featureError } from '../../app/ui/featureError'
import { usePrivateScope } from '../../app/security/usePrivateScope'
import { ApiProblemError } from '../../app/api/ProblemDetailsDecoder'
import { useRetryAfter } from '../../app/ui/useRetryAfter'
import { publicationChanged, publicationKeys, sourceLinks } from './PublicationCache'
import { publishingApi, type PublicationPreview, type OwnerPublication } from './PublishingApi'
import type { Etagged } from '../../app/api/ApiClient'

export function PublicationComposer({ auth, noteId, noteEtag, eligible, publication, onClose, onComplete, onRefresh, opener }: {
  auth: AuthRuntime; noteId: string; noteEtag: string; eligible: boolean; publication?: Etagged<OwnerPublication>;
  onClose: () => void; onComplete: (id: string) => void; onRefresh: () => Promise<unknown>; opener?: HTMLElement | null
}) {
  const current = usePrivateScope(auth), pending = useRef(false), revision = useRef(0)
  const [selected, setSelected] = useState<string[]>([]), [preview, setPreview] = useState<PublicationPreview | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [failure, setFailure] = useState<unknown>(null)
  const cooldown = useRetryAfter(failure)
  const scope = auth.session.viewerScope
  const previewRoot = useRef<HTMLElement | null>(null)
  const profile = useQuery({ queryKey: ['profile', scope, 'private'], queryFn: ({ signal }) => profileApi.read(auth, signal), retry: false }, auth.queries)
  const handle = profile.data?.handle
  const author = useQuery({ queryKey: publicationKeys.author(handle ?? ''), queryFn: ({ signal }) => discoveryApi.author(auth, handle!, signal), enabled: !!handle, retry: false }, auth.queries)
  const files = useInfiniteQuery({ queryKey: ['publication-selection', scope, noteId], queryFn: ({ pageParam, signal }) => attachmentsApi.list(auth, noteId, pageParam, signal), initialPageParam: null as string | null, getNextPageParam: p => p.nextCursor ?? undefined, retry: false }, auth.queries)
  useEffect(() => { revision.current++; setPreview(null) }, [noteId, noteEtag, eligible, publication?.etag, author.data, files.data])
  useEffect(() => { if (preview) previewRoot.current?.focus({ preventScroll: true }) }, [preview])
  const available = files.data?.pages.flatMap(p => p.items).filter(f => f.storageState === 'stored' && f.validationState === 'accepted') ?? []
  function changeSelection(id: string) { revision.current++; setPreview(null); setSelected(old => old.includes(id) ? old.filter(v => v !== id) : old.length < 20 ? [...old, id] : old) }
  async function requestPreview() {
    if (pending.current || cooldown || !eligible || !author.data || files.isError) return
    const captured = revision.current; pending.current = true; setBusy(true); setError(''); setFailure(null); setPreview(null)
    try { const p = await publishingApi.preview(auth, noteId, noteEtag, selected); if (current() && captured === revision.current) setPreview(p) }
    catch (e) { if (current()) { setFailure(e); setError(featureError(e)) } }
    finally { pending.current = false; if (current()) setBusy(false) }
  }
  async function commit() {
    if (!preview || pending.current || cooldown || !eligible || !author.data) return
    pending.current = true; setBusy(true); setError(''); setFailure(null)
    try {
      const p = await publishingApi.commit(auth, noteId, publication?.value.id ?? null, publication ? publication.value.availability === 'unpublished' ? 'republish' : 'update' : 'create', publication?.etag ?? noteEtag, selected, preview.previewFingerprint)
      if (!current()) return
      sourceLinks(auth).set(noteId, p.value.id)
      publicationChanged(auth, p.value.id)
      auth.queries.setQueryData(publicationKeys.detail(auth, p.value.id), p)
      onComplete(p.value.id)
    } catch (e) { if (current()) { setPreview(null); setFailure(e); setError(featureError(e)) } }
    finally { pending.current = false; if (current()) setBusy(false) }
  }
  async function refreshSavedState() {
    if (pending.current || cooldown) return
    pending.current = true; setBusy(true); setPreview(null); revision.current++
    try { await onRefresh(); if (current()) { setFailure(null); setError('Saved state refreshed. Review your selection and request a new preview.') } }
    catch (e) { if (current()) { setFailure(e); setError(featureError(e)) } }
    finally { pending.current = false; if (current()) setBusy(false) }
  }
  const profileMissing = !!profile.data && (!handle || (author.error instanceof ApiProblemError && author.error.problem.status === 404))
  const recent = failure instanceof ApiProblemError && failure.problem.code === 'recent_authentication_required'
  return <FormDialog title={publication ? publication.value.availability === 'unpublished' ? 'Republish public copy' : 'Update public copy' : 'Publish a saved note'} onClose={onClose} busy={busy} opener={opener}>
    <p className="public-disclosure">Only the saved Note and explicitly selected files become public. Private edits never update an existing public copy automatically.</p>
    {!eligible && <p role="alert">Save and resolve the Note before previewing or publishing.</p>}
    {profile.isPending || (handle && author.isPending) ? <p role="status">Checking public author…</p> : profileMissing ? <p role="alert">Activate a public author profile first. <Link to="/settings/public-profile">Set up public profile</Link></p> : author.data && <PublicAuthorView author={author.data} />}
    {profile.isError && <p role="alert">{featureError(profile.error)}</p>}
    {author.isError && !profileMissing && <p role="alert">{featureError(author.error)} <button disabled={busy} onClick={() => void author.refetch()}>Retry author check</button></p>}
    {!preview ? <><h3>Choose public files</h3><p>Choose up to 20 files. No private file is selected automatically. On update, this selection replaces the previous public media.</p>
      {files.isPending ? <p role="status">Loading private file choices…</p> : files.isError ? <p role="alert">{featureError(files.error)} <button onClick={() => void files.refetch()}>Retry files</button></p> : available.length ? <fieldset disabled={busy}><legend>Files to copy publicly</legend>{available.map(f => <label className="public-file-choice" key={f.id}><input type="checkbox" disabled={selected.length >= 20 && !selected.includes(f.id)} checked={selected.includes(f.id)} onChange={() => changeSelection(f.id)} /><span><strong>{f.displayFilename}</strong><small>{attachmentDescription(f)}</small></span></label>)}</fieldset> : <p>No accepted files on this Note.</p>}
      {files.hasNextPage && <button className="button-secondary" disabled={busy || files.isFetchingNextPage} onClick={() => void files.fetchNextPage()}>More files</button>}
      <button disabled={busy || !!cooldown || !eligible || !author.data || files.isPending || files.isError} onClick={() => void requestPreview()}>Preview public copy</button></>
      : <section ref={previewRoot} tabIndex={-1} aria-label="Authoritative publication preview" className="publication-preview"><p className="studio-section-label">Public preview · saved content</p><h3>{preview.title}</h3><ul className="notes-tags">{preview.tags.map(t => <li key={t}>{t}</li>)}</ul><MarkdownView markdown={preview.markdown} />
        <h3>Selected public media</h3>{preview.media.length ? <ul>{preview.media.map(m => <li key={m.attachmentId}>{m.displayName} · {m.mediaKind} · {m.sizeBytes.toLocaleString()} bytes</li>)}</ul> : <p>No media will be included.</p>}{preview.warnings.map((w, i) => <p className="public-disclosure" key={i}>{w}</p>)}
        <div className="actions"><button className="button-secondary" disabled={busy} onClick={() => { revision.current++; setPreview(null) }}>Change selection</button><button disabled={busy || !eligible} onClick={() => void commit()}>{publication ? publication.value.availability === 'unpublished' ? 'Confirm republish' : 'Confirm public update' : 'Confirm publication'}</button></div></section>}
    {error && <p role="alert">{error} Preview again before retrying.</p>}{failure instanceof ApiProblemError && [409, 412].includes(failure.problem.status) && <button className="button-secondary" disabled={busy || !!cooldown} onClick={() => void refreshSavedState()}>Refresh saved state</button>}{cooldown > 0 && <p role="status">Try again in {cooldown} seconds.</p>}{recent && <Link to="/reauth" onClick={() => { auth.continuation.returnIntent = publication ? `/publications/${publication.value.id}` : `/notes/${noteId}` }}>Confirm identity</Link>}
  </FormDialog>
}
