import { useCallback, useEffect, useReducer, useRef, useState, type FormEvent } from 'react'
import { Link, useBlocker, useLocation, useNavigate, useParams } from 'react-router'
import { flushSync } from 'react-dom'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { ApiProblemError } from '../../app/api/ProblemDetailsDecoder'
import { MarkdownView } from './MarkdownView'
import { NoteVersionPanel } from './NoteVersionPanel'
import { AttachmentPanel } from './AttachmentPanel'
import { NotesWorkspace } from './NotesWorkspace'
import { emptyEditorSession, isDirty, noteEditorSession } from './NoteEditorSession'
import { notesApi, type NoteCore, type NoteOrganizationCommand, type NoteVersion } from './NotesApi'
import { noteKeys, notePreferenceKeys } from './NotesKeys'

function issue(error: unknown): string {
  if (error instanceof ApiProblemError) {
    if (error.problem.status === 404) return 'This note is unavailable.'
    if (error.problem.status === 503) return 'Notes are temporarily unavailable. Try again later.'
    if (error.problem.status === 422 || error.problem.status === 400) return 'Review the note and try again.'
  }
  return 'Could not complete this request. Try again.'
}

export function NotesListPage({ auth }: { auth: AuthRuntime }) {
  const scope = auth.session.viewerScope
  const location = useLocation()
  const [lifecycle, setLifecycle] = useState<NoteCore['lifecycle']>(location.state?.notesView === 'trashed' ? 'trashed' : 'active')
  const page = useInfiniteQuery({ queryKey: noteKeys.list(scope, { lifecycle, sort: 'updatedAtDesc' }),
    queryFn: ({ pageParam }) => notesApi.list(auth, pageParam, lifecycle), initialPageParam: null as string | null,
    getNextPageParam: last => last.nextCursor ?? undefined, retry: false }, auth.queries)
  const entries = [...new Map(page.data?.pages.flatMap(part => part.items)
    .map(item => [item.id, item] as const) ?? []).values()]
  return <NotesWorkspace>
    <header className="notes-heading"><div><p className="eyebrow">Your workspace</p><h1>Notes</h1></div>
      <Link className="button" to="/notes/new">New note</Link></header>
    <p className="notes-intro">Make room for a thought. Keep it close.</p>
    <nav aria-label="Note views" className="notes-views">
      <button type="button" className="button-secondary" aria-pressed={lifecycle === 'active'} onClick={() => setLifecycle('active')}>Active notes</button>
      <button type="button" className="button-secondary" aria-pressed={lifecycle === 'archived'} onClick={() => setLifecycle('archived')}>Archived notes</button>
      <button type="button" className="button-secondary" aria-pressed={lifecycle === 'trashed'} onClick={() => setLifecycle('trashed')}>Trashed notes</button>
    </nav>
    {page.isPending && <p className="notes-message" role="status">Loading notes…</p>}
    {page.isError && <p className="notes-message notes-message-error" role="alert">{issue(page.error)} <button onClick={() => void page.refetch()}>Retry</button></p>}
    {!page.isPending && !page.isError && entries.length === 0 && <div className="notes-empty"><h2>{lifecycle === 'active' ? 'No notes yet' : lifecycle === 'archived' ? 'No archived notes' : 'No trashed notes'}</h2><p>{lifecycle === 'active' ? 'Start with a thought worth keeping.' : 'Notes in this view will appear here.'}</p><Link to="/notes/new">Create a note</Link></div>}
    {entries.length > 0 && <ul className="notes-list">{entries.map(note => <li key={note.id}>
      <Link to={`/notes/${note.id}`}><div className="notes-card-heading"><strong>{note.title}</strong>{note.pinned && <span className="notes-pill">Pinned</span>}</div>
        <span className="notes-card-meta">Updated <time dateTime={note.updatedAt}>{new Date(note.updatedAt).toLocaleString()}</time></span>
        {note.tags.length > 0 && <ul className="notes-tags" aria-label="Tags">{note.tags.map(tag => <li key={tag}>{tag}</li>)}</ul>}</Link>
    </li>)}</ul>}
    {page.hasNextPage && <button className="button-secondary" disabled={page.isFetchingNextPage}
      onClick={() => void page.fetchNextPage()}>Load more</button>}
  </NotesWorkspace>
}

export function NoteEditorPage({ auth, creating = false }: { auth: AuthRuntime; creating?: boolean }) {
  const { id } = useParams(), navigate = useNavigate()
  const scope = auth.session.viewerScope
  const [session, dispatch] = useReducer(noteEditorSession, emptyEditorSession)
  const [aiEnabled, setAiEnabled] = useState(false)
  const [error, setError] = useState('')
  const [preview, setPreview] = useState(true)
  const [busy, setBusy] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [attachmentModal, setAttachmentModal] = useState(false)
  const attachmentModalRef = useRef(false)
  const changeAttachmentModal = useCallback((open: boolean) => {
    attachmentModalRef.current = open; setAttachmentModal(open)
  }, [])
  const [restoreConfirmation, setRestoreConfirmation] = useState<{ version: NoteVersion; etag: string } | null>(null)
  const restoreCancel = useRef<HTMLButtonElement>(null)
  const restoreInvoker = useRef<HTMLElement | null>(null)
  const [tagInput, setTagInput] = useState('')
  const [tagEtag, setTagEtag] = useState<string | null>(null)
  const [tagsEditing, setTagsEditing] = useState(false)
  const [tagError, setTagError] = useState('')
  const [archiveConfirmation, setArchiveConfirmation] = useState(false)
  const archiveConfirmButton = useRef<HTMLButtonElement>(null)
  const archiveButton = useRef<HTMLButtonElement>(null)
  const previousArchiveConfirmation = useRef(false)
  const [trashConfirmation, setTrashConfirmation] = useState<'draft' | 'publication' | null>(null)
  const [trashConfirmationEtag, setTrashConfirmationEtag] = useState<string | null>(null)
  const trashConfirmButton = useRef<HTMLButtonElement>(null)
  const trashButton = useRef<HTMLButtonElement>(null)
  const previousTrashConfirmation = useRef(false)
  const [deleteConfirmation, setDeleteConfirmation] = useState<'delete' | 'publication' | 'reauth' | null>(null)
  const [deleteEtag, setDeleteEtag] = useState<string | null>(null)
  const [deleted, setDeleted] = useState(false)
  const deleteButton = useRef<HTMLButtonElement>(null)
  const deleteCancelButton = useRef<HTMLButtonElement>(null)
  const previousDeleteConfirmation = useRef(false)
  const leaveCancel = useRef<HTMLButtonElement>(null)
  const leaveInvoker = useRef<HTMLElement | null>(null)
  const [conflictLoading, setConflictLoading] = useState(false)
  const [conflictLoadError, setConflictLoadError] = useState(false)
  const initialPreference = useRef(false)
  const allowNavigation = useRef(false)
  const preference = useQuery({ queryKey: notePreferenceKeys.current(scope),
    queryFn: () => notesApi.preference(auth), enabled: creating, retry: false }, auth.queries)
  const loaded = useQuery({ queryKey: noteKeys.core(scope, id ?? ''),
    queryFn: () => notesApi.get(auth, id!), enabled: !creating && !!id && !deleted, retry: false }, auth.queries)

  useEffect(() => {
    if (creating && preference.data !== undefined && !initialPreference.current) {
      initialPreference.current = true
      setAiEnabled(preference.data)
    }
  }, [creating, preference.data])
  useEffect(() => { if (loaded.data && !deleted) dispatch({ type: 'observed', server: loaded.data }) }, [loaded.data, deleted])

  const dirty = isDirty(session)
  const archived = session.serverVersion?.value.lifecycle === 'archived'
  const trashed = session.serverVersion?.value.lifecycle === 'trashed'
  const organizationEligible = session.serverVersion?.value.lifecycle === 'active' || archived
  const commandBlocked = busy || !organizationEligible || session.phase === 'Conflict' || session.serverChangedWhileDirty
  const lifecycleBlocked = busy || (!organizationEligible && !trashed)
    || session.phase === 'Conflict' || session.serverChangedWhileDirty
  const blocker = useBlocker(({ currentLocation, nextLocation }) => {
    const blocked = !allowNavigation.current && dirty && currentLocation.pathname !== nextLocation.pathname
    if (blocked) leaveInvoker.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    return blocked
  })
  useEffect(() => {
    if (blocker.state === 'blocked') leaveCancel.current?.focus()
    else if (leaveInvoker.current) { leaveInvoker.current.focus(); leaveInvoker.current = null }
  }, [blocker.state])
  useEffect(() => {
    if (!dirty) return
    const prevent = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', prevent)
    return () => window.removeEventListener('beforeunload', prevent)
  }, [dirty])

  async function loadConflictVersion() {
    if (!session.id || conflictLoading) return
    setConflictLoading(true); setConflictLoadError(false)
    try { dispatch({ type: 'observed', server: await notesApi.get(auth, session.id) }) }
    catch { setConflictLoadError(true) }
    finally { setConflictLoading(false) }
  }

  async function save() {
    if (busy || archived || trashed || attachmentModalRef.current || archiveConfirmation || trashConfirmation || deleteConfirmation || restoreConfirmation || blocker.state === 'blocked' || !dirty || session.phase === 'Conflict' || !session.draft.title.trim()) return
    if (!creating && (!session.id || !session.etag)) return
    const { title, markdown } = session.draft
    setBusy(true); setError(''); dispatch({ type: 'saving' })
    try {
      const result = creating
        ? await notesApi.create(auth, title, markdown, aiEnabled)
        : await notesApi.save(auth, session.id!, session.etag!, title, markdown)
      dispatch({ type: 'saved', server: result })
      if (creating) { allowNavigation.current = true; navigate(`/notes/${result.value.id}`, { replace: true }) }
      else auth.queries.setQueryData(noteKeys.core(scope, session.id!), result)
      await auth.queries.invalidateQueries({ queryKey: noteKeys.lists(scope) })
      if (session.id) await auth.queries.invalidateQueries({ queryKey: noteKeys.versions(scope, session.id) })
    } catch (failure) {
      if (failure instanceof ApiProblemError && failure.problem.status === 412 && !creating) {
        dispatch({ type: 'conflict' })
        await loadConflictVersion()
      } else { dispatch({ type: 'failed' }); setError(issue(failure)) }
    } finally { setBusy(false) }
  }

  async function replaceTags(event: FormEvent) {
    event.preventDefault()
    if (busy || !organizationEligible || !session.id || !tagEtag || session.phase === 'Conflict'
        || session.serverChangedWhileDirty) return
    const tags = tagInput.split('\n').map(value => value.trim()).filter(Boolean)
    setBusy(true); setTagError('')
    try {
      const result = await notesApi.replaceTags(auth, session.id, tagEtag, tags)
      dispatch({ type: 'coreCommandSucceeded', server: result })
      auth.queries.setQueryData(noteKeys.core(scope, session.id), result)
      setTagsEditing(false)
      await auth.queries.invalidateQueries({ queryKey: noteKeys.lists(scope) })
    } catch (failure) {
      if (failure instanceof ApiProblemError && failure.problem.status === 412) {
        dispatch({ type: 'conflict' })
        await loadConflictVersion()
      } else setTagError(issue(failure))
    } finally { setBusy(false) }
  }

  useEffect(() => {
    if (restoreConfirmation && !busy) restoreCancel.current?.focus()
    else if (!restoreConfirmation) restoreInvoker.current?.focus()
  }, [restoreConfirmation, busy])

  function closeRestore() {
    setRestoreConfirmation(null)
  }

  async function restoreCheckpoint() {
    if (!restoreConfirmation || !session.id || busy || archived || trashed
        || session.phase === 'Conflict' || session.serverChangedWhileDirty) return
    setBusy(true); setError('')
    try {
      const result = await notesApi.restoreVersion(auth, session.id, restoreConfirmation.version.id, restoreConfirmation.etag)
      // This replacement is deliberate: the confirmation explicitly includes discarding a dirty draft.
      dispatch({ type: 'load', server: result })
      auth.queries.setQueryData(noteKeys.core(scope, session.id), result)
      setTagsEditing(false)
      await Promise.all([
        auth.queries.invalidateQueries({ queryKey: noteKeys.history(scope, session.id) }),
        auth.queries.invalidateQueries({ queryKey: noteKeys.lists(scope) }),
      ])
    } catch (failure) {
      if (failure instanceof ApiProblemError && failure.problem.status === 412) {
        dispatch({ type: 'conflict' }); await loadConflictVersion()
      } else setError(issue(failure))
    } finally { setBusy(false); closeRestore() }
  }

  async function changeAiAccess() {
    if (lifecycleBlocked || !session.id || !session.etag || !session.serverVersion) return
    setBusy(true); setError('')
    try {
      const result = await notesApi.setAiAccess(auth, session.id, session.etag, !session.serverVersion.value.aiEnabled)
      dispatch({ type: 'coreCommandSucceeded', server: result })
      auth.queries.setQueryData(noteKeys.core(scope, session.id), result)
      await auth.queries.invalidateQueries({ queryKey: noteKeys.lists(scope) })
    } catch (failure) {
      if (failure instanceof ApiProblemError && failure.problem.status === 412) {
        dispatch({ type: 'conflict' }); await loadConflictVersion()
      } else setError(issue(failure))
    } finally { setBusy(false) }
  }

  async function organize(command: NoteOrganizationCommand) {
    if (commandBlocked || !session.id || !session.etag) return
    setBusy(true); setError('')
    try {
      const result = await notesApi.organize(auth, session.id, session.etag, command)
      dispatch({ type: 'coreCommandSucceeded', server: result })
      auth.queries.setQueryData(noteKeys.core(scope, session.id), result)
      await auth.queries.invalidateQueries({ queryKey: noteKeys.lists(scope) })
    } catch (failure) {
      if (failure instanceof ApiProblemError && failure.problem.status === 412) {
        dispatch({ type: 'conflict' })
        await loadConflictVersion()
      } else setError(issue(failure))
    } finally { setBusy(false); setArchiveConfirmation(false) }
  }

  useEffect(() => {
    if (archiveConfirmation) archiveConfirmButton.current?.focus()
    else if (previousArchiveConfirmation.current) archiveButton.current?.focus()
    previousArchiveConfirmation.current = archiveConfirmation
  }, [archiveConfirmation])

  async function changeLifecycle(command: 'trash' | 'restore', confirmPublicationUnpublish = false,
    etag = session.etag) {
    if (lifecycleBlocked || !session.id || !etag) return
    setBusy(true); setError('')
    try {
      const result = await notesApi.lifecycle(auth, session.id, etag, command, confirmPublicationUnpublish)
      dispatch({ type: 'coreCommandSucceeded', server: result })
      auth.queries.setQueryData(noteKeys.core(scope, session.id), result)
      setTrashConfirmation(null)
      await auth.queries.invalidateQueries({ queryKey: noteKeys.lists(scope) })
    } catch (failure) {
      if (failure instanceof ApiProblemError && failure.problem.status === 409
          && failure.problem.code === 'publication_consequence_required' && command === 'trash') {
        setTrashConfirmationEtag(etag)
        setTrashConfirmation('publication')
      } else {
        setTrashConfirmation(null)
        if (failure instanceof ApiProblemError && failure.problem.status === 412) {
          dispatch({ type: 'conflict' })
          await loadConflictVersion()
        } else setError(issue(failure))
      }
    } finally { setBusy(false) }
  }

  useEffect(() => {
    if (trashConfirmation) trashConfirmButton.current?.focus()
    else if (previousTrashConfirmation.current) trashButton.current?.focus()
    previousTrashConfirmation.current = trashConfirmation !== null
  }, [trashConfirmation])

  async function permanentlyDelete(confirmPublicationUnpublish = false) {
    if (lifecycleBlocked || !trashed || !session.id || !deleteEtag) return
    const noteId = session.id
    setBusy(true); setError('')
    try {
      await notesApi.permanentlyDelete(auth, noteId, deleteEtag, confirmPublicationUnpublish)
      await auth.queries.cancelQueries({ queryKey: noteKeys.core(scope, noteId) })
      await auth.queries.cancelQueries({ queryKey: noteKeys.history(scope, noteId) })
      await auth.queries.cancelQueries({ queryKey: noteKeys.lists(scope) })
      // Stop the observer and erase editor content before evicting its private cache.
      allowNavigation.current = true
      flushSync(() => { setDeleted(true); dispatch({ type: 'deleted' }); setDeleteConfirmation(null) })
      auth.queries.removeQueries({ queryKey: noteKeys.core(scope, noteId), exact: true })
      auth.queries.removeQueries({ queryKey: noteKeys.history(scope, noteId) })
      auth.queries.removeQueries({ queryKey: noteKeys.lists(scope) })
      navigate('/notes', { replace: true, state: { notesView: 'trashed' } })
    } catch (failure) {
      if (failure instanceof ApiProblemError && failure.problem.code === 'recent_authentication_required'
          && failure.problem.status === 403) setDeleteConfirmation('reauth')
      else if (failure instanceof ApiProblemError && failure.problem.code === 'publication_consequence_required'
          && failure.problem.status === 409) setDeleteConfirmation('publication')
      else {
        setDeleteConfirmation(null)
        if (failure instanceof ApiProblemError && failure.problem.status === 412) {
          dispatch({ type: 'conflict' }); await loadConflictVersion()
        } else setError(issue(failure))
      }
    } finally { setBusy(false) }
  }

  useEffect(() => {
    if (deleteConfirmation && !busy) deleteCancelButton.current?.focus()
    else if (previousDeleteConfirmation.current) deleteButton.current?.focus()
    previousDeleteConfirmation.current = deleteConfirmation !== null
  }, [deleteConfirmation, busy])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault(); void save()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  })

  function submit(event: FormEvent) { event.preventDefault(); void save() }
  const loading = creating ? preference.isPending
    : loaded.isPending || (!!loaded.data && session.id !== loaded.data.value.id)
  const loadError = creating ? preference.error : loaded.error
  if (deleted) return <main><p role="status">Note permanently deleted.</p></main>
  return <NotesWorkspace modalOpen={attachmentModal || archiveConfirmation || trashConfirmation !== null || deleteConfirmation !== null || restoreConfirmation !== null || blocker.state === 'blocked'}>
    <div inert={attachmentModal || archiveConfirmation || trashConfirmation !== null || deleteConfirmation !== null || restoreConfirmation !== null || blocker.state === 'blocked'}>
    {loading ? <p className="notes-message" role="status">Loading editor…</p> : loadError
      ? <p className="notes-message notes-message-error" role="alert">{issue(loadError)} <button onClick={() => void (creating ? preference.refetch() : loaded.refetch())}>Retry</button></p>
        : <><header className="notes-heading"><div><p className="eyebrow">{creating ? 'New note' : 'Private note'}</p>
          <h1>{creating ? 'Create a note' : 'Edit note'}</h1></div><span role="status" className="notes-state" data-phase={session.phase}>
            {session.phase === 'Clean' ? creating ? 'Not created' : 'Saved' : session.phase === 'Dirty' ? 'Unsaved changes'
              : session.phase === 'Saving' ? 'Saving…' : session.phase === 'SaveFailed' ? 'Save failed'
                : 'Conflict'}</span></header>
          <div className={`notes-workbench${creating ? ' notes-workbench-new' : ''}`}>
          <div className="notes-authoring">
          <form className="notes-editor" onSubmit={submit}>
            <label htmlFor="note-title">Title</label><input id="note-title" maxLength={500} required
              value={session.draft.title} onChange={event => dispatch({ type: 'edit', field: 'title', value: event.target.value })} />
            {creating && <label className="notes-checkbox"><input type="checkbox" checked={aiEnabled}
              onChange={event => setAiEnabled(event.target.checked)} />Use this note with AI</label>}
            <label htmlFor="note-markdown">Markdown</label>
            <p id="markdown-help" className="notes-help">Write in Markdown. Save when you’re ready — Ctrl/Cmd + S.</p>
            <textarea id="note-markdown" rows={14} maxLength={1_000_000} aria-describedby="markdown-help" value={session.draft.markdown}
              onChange={event => dispatch({ type: 'edit', field: 'markdown', value: event.target.value })} />
            <div className="notes-actions"><button type="submit" disabled={busy || archiveConfirmation || trashConfirmation !== null || archived || trashed || !dirty || session.phase === 'Conflict' || !session.draft.title.trim()}>
              {creating ? 'Create note' : session.phase === 'SaveFailed' ? 'Retry save' : 'Save note'}</button>
              <button type="button" className="button-secondary" aria-pressed={preview} onClick={() => setPreview(value => !value)}>
                {preview ? 'Hide preview' : 'Show preview'}</button></div>
          </form>
          {error && <p className="notes-message notes-message-error" role="alert">{error}</p>}
          {session.serverChangedWhileDirty && session.phase !== 'Conflict'
            && <p className="notes-message notes-message-warning" role="alert">This note changed on the server while you were editing. Your draft was kept.</p>}
          {session.phase === 'Conflict' && <section className="notes-conflict" aria-label="Save conflict">
            <h2>Another version was saved</h2><p>Your draft is still here. Review the current version before deciding what to keep.</p>
            {!session.serverVersion && <>{conflictLoading && <p role="status">Loading current saved version…</p>}
              {conflictLoadError && <p role="alert">The current saved version could not be loaded. Your draft is unchanged.</p>}
              <button type="button" className="button-secondary" disabled={conflictLoading}
                onClick={() => void loadConflictVersion()}>Retry loading saved version</button></>}
            {session.serverVersion && <><h3>Current saved version</h3>
              <p>{session.serverVersion.value.title}</p><MarkdownView markdown={session.serverVersion.value.markdown} />
              <button type="button" className="button-secondary" onClick={() => dispatch({ type: 'reloadServer' })}>Discard my draft and load saved version</button>
              <button type="button" className="button-secondary" onClick={() => dispatch({ type: 'rebase' })}>Keep my draft and use current version as save base</button></>}
          </section>}
          {preview && <section className="notes-preview" aria-label="Markdown preview"><h2>Preview</h2>
            {!session.draft.markdown && <p className="notes-help">Your Markdown preview will appear here.</p>}
            <MarkdownView markdown={session.draft.markdown} /></section>}
          </div>
          {!creating && <aside className="notes-tools" aria-label="Note tools">
          {session.id && <AttachmentPanel auth={auth} noteId={session.id} lifecycle={session.serverVersion?.value.lifecycle ?? loaded.data?.value.lifecycle ?? 'trashed'} onModalChange={changeAttachmentModal} />}
          <section className="notes-tool" aria-label="Note organization">
            <h2>Organization</h2><p>{session.serverVersion
              ? `${trashed ? 'Trashed' : archived ? 'Archived' : organizationEligible ? 'Active' : 'Organization changes unavailable'} · ${session.serverVersion.value.pinned ? 'Pinned' : 'Not pinned'}`
              : 'Current organization state unavailable'}</p>
            <div className="notes-actions"><button type="button" className="button-secondary" disabled={commandBlocked}
              onClick={() => void organize(session.serverVersion?.value.pinned ? 'unpin' : 'pin')}>
              {session.serverVersion?.value.pinned ? 'Unpin' : 'Pin'}</button>
              <button type="button" className="button-secondary" ref={archiveButton} disabled={commandBlocked}
                onClick={() => archived ? void organize('returnFromArchive') : dirty ? setArchiveConfirmation(true) : void organize('archive')}>
                {archived ? 'Return from archive' : 'Archive'}</button>
              <button type="button" className="button-secondary notes-danger" ref={trashButton} disabled={lifecycleBlocked}
                onClick={() => {
                  if (trashed) void changeLifecycle('restore')
                  else if (dirty) { setTrashConfirmationEtag(session.etag); setTrashConfirmation('draft') }
                  else void changeLifecycle('trash')
                }}>
                {trashed ? 'Restore' : 'Trash'}</button>
              {trashed && <button type="button" className="button-secondary notes-danger" ref={deleteButton} disabled={lifecycleBlocked}
                onClick={() => { setDeleteEtag(session.etag); setDeleteConfirmation('delete') }}>Permanent delete</button>}</div>
            {archived && <p role="status">This note is archived. Return it to Active before saving title or Markdown. Any unsaved draft is kept in this tab.</p>}
            {trashed && <p role="status">This note is in Trash. Restore it before saving. Your title and Markdown draft are kept in this tab; leaving can lose the draft.</p>}
          </section>
          <section className="notes-tool" aria-label="Note tags">
            <h2>Tags</h2>
            {session.serverVersion?.value.tags.length
              ? <ul className="notes-tags">{session.serverVersion.value.tags.map(tag => <li key={tag}>{tag}</li>)}</ul>
              : <p>No tags</p>}
            {!tagsEditing ? <button type="button" className="button-secondary"
              disabled={busy || !organizationEligible || session.phase === 'Conflict' || session.serverChangedWhileDirty}
              onClick={() => { setTagInput((session.serverVersion?.value.tags ?? []).join('\n')); setTagEtag(session.etag); setTagError(''); setTagsEditing(true) }}>Edit tags</button>
              : <form onSubmit={event => void replaceTags(event)}>
                <label htmlFor="note-tags">Tags, one per line</label>
                <textarea id="note-tags" rows={4} maxLength={5100} value={tagInput} disabled={busy}
                  onChange={event => setTagInput(event.target.value)} aria-describedby="tags-help" />
                <p id="tags-help">Up to 50 tags, 100 characters each. Apply tags separately; your title and Markdown draft are not saved.</p>
                <button type="submit" disabled={busy || !organizationEligible || session.phase === 'Conflict' || session.serverChangedWhileDirty}>Apply tags</button>
                <button type="button" className="button-secondary" disabled={busy}
                  onClick={() => { setTagsEditing(false); setTagError('') }}>Cancel tag editing</button>
              </form>}
            {tagError && <p role="alert">{tagError}</p>}
          </section>
          <section className="notes-tool" aria-label="Note AI access">
            <h2>Use this note with AI</h2>
            <p aria-live="polite">AI access is {session.serverVersion?.value.aiEnabled ? 'ON' : 'OFF'}.</p>
            <p id="note-ai-help">{session.serverVersion?.value.aiEnabled
              ? 'This note may participate in AI features once all processing permissions and availability checks are satisfied. Enabling access does not mean processing is ready.'
              : 'Normal note use remains available with AI off. This is not encryption or a visibility change.'}</p>
            <p>Changing AI access does not save your title or Markdown draft.</p>
            <button type="button" className="button-secondary" aria-describedby="note-ai-help"
              disabled={lifecycleBlocked} onClick={() => void changeAiAccess()}>
              {session.serverVersion?.value.aiEnabled ? 'Disable AI' : 'Enable AI'}</button>
          </section>
          {session.id && <div className="notes-tool notes-history-tool">
            <button type="button" className="button-secondary" aria-expanded={historyOpen}
              aria-controls="note-history" onClick={() => setHistoryOpen(value => !value)}>
              {historyOpen ? 'Hide version history' : 'Show version history'}</button>
            {historyOpen && <div id="note-history"><NoteVersionPanel auth={auth} noteId={session.id}
              restoreBlocked={lifecycleBlocked || archived || trashed} onRestore={(version, invoker) => {
                if (!session.etag || lifecycleBlocked || archived || trashed) return
                restoreInvoker.current = invoker
                setRestoreConfirmation({ version, etag: session.etag })
              }} /></div>}
          </div>}
          </aside>}
          </div>
        </>}
    </div>
    {restoreConfirmation && <div className="dialog-backdrop"><div className="confirm-dialog" role="dialog" aria-modal="true"
      aria-labelledby="checkpoint-restore-title" aria-describedby="checkpoint-restore-help" onKeyDown={event => {
        if (event.key === 'Escape' && !busy) closeRestore()
        if (event.key === 'Tab') {
          event.preventDefault()
          const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
          const position = buttons.indexOf(document.activeElement as HTMLButtonElement)
          buttons[(position + (event.shiftKey ? buttons.length - 1 : 1)) % buttons.length]?.focus()
        }
      }}>
      <h2 id="checkpoint-restore-title">Restore saved checkpoint?</h2>
      <p id="checkpoint-restore-help">This replaces the saved title and Markdown with “{restoreConfirmation.version.title}”.
        The immediately previous saved content remains recoverable in history. Tags, pin state and AI access stay unchanged.</p>
      {dirty && <p>Your unsaved title and Markdown draft will be discarded, not saved.</p>}
      <button type="button" className="button-secondary" ref={restoreCancel} disabled={busy} onClick={closeRestore}>Keep current note</button>
      <button type="button" disabled={lifecycleBlocked || archived || trashed} onClick={() => void restoreCheckpoint()}>
        {dirty ? 'Restore and discard draft' : 'Confirm restore'}</button>
    </div></div>}
    {blocker.state === 'blocked' && <div className="dialog-backdrop"><div className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="leave-title"
      onKeyDown={event => {
        if (event.key === 'Escape') blocker.reset()
        if (event.key === 'Tab') {
          event.preventDefault()
          const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button'))
          const position = buttons.indexOf(document.activeElement as HTMLButtonElement)
          buttons[(position + (event.shiftKey ? buttons.length - 1 : 1)) % buttons.length]?.focus()
        }
      }}>
      <h2 id="leave-title">Leave with unsaved changes?</h2><p>Your draft will be lost if you leave.</p>
      <button type="button" ref={leaveCancel} onClick={() => blocker.reset()}>Keep editing</button>
      <button type="button" className="button-secondary" onClick={() => blocker.proceed()}>Discard changes and leave</button>
    </div></div>}
    {archiveConfirmation && <div className="dialog-backdrop"><div className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="archive-title"
      onKeyDown={event => {
        if (event.key === 'Escape' && !busy) { setArchiveConfirmation(false); archiveButton.current?.focus() }
        if (event.key === 'Tab') {
          event.preventDefault()
          const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
          const position = buttons.indexOf(document.activeElement as HTMLButtonElement)
          buttons[(position + (event.shiftKey ? buttons.length - 1 : 1)) % buttons.length]?.focus()
        }
      }}>
      <h2 id="archive-title">Archive with unsaved changes?</h2>
      <p>Your title and Markdown draft will stay in this tab, but will not be saved. Return the note from archive before saving it. Leaving the editor can lose the draft.</p>
      <button type="button" ref={archiveConfirmButton} disabled={commandBlocked} onClick={() => void organize('archive')}>Archive and keep draft</button>
      <button type="button" className="button-secondary" disabled={busy} onClick={() => { setArchiveConfirmation(false); archiveButton.current?.focus() }}>Keep editing</button>
    </div></div>}
    {deleteConfirmation && <div className="dialog-backdrop"><div className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-title" aria-describedby="delete-help"
      onKeyDown={event => {
        if (event.key === 'Escape' && !busy) { setDeleteConfirmation(null); deleteButton.current?.focus() }
        if (event.key === 'Tab') {
          event.preventDefault()
          const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
          const position = buttons.indexOf(document.activeElement as HTMLButtonElement)
          buttons[(position + (event.shiftKey ? buttons.length - 1 : 1)) % buttons.length]?.focus()
        }
      }}>
      <h2 id="delete-title">{deleteConfirmation === 'reauth' ? 'Confirm your identity before deleting' : deleteConfirmation === 'publication' ? 'Unpublish and permanently delete?' : 'Permanently delete this note?'}</h2>
      <p id="delete-help">Unlike Trash, permanent deletion cannot be undone. This note will no longer be recoverable.</p>
      {dirty && <p>Your unsaved title and Markdown will be discarded, not saved, if you proceed{deleteConfirmation === 'reauth' ? ' to identity confirmation' : ' with permanent deletion'}.</p>}
      {deleteConfirmation === 'publication' && <p>Continuing will also unpublish the current public copy.</p>}
      {deleteConfirmation === 'reauth' && <p>After confirming your identity, return to this note and choose Permanent delete again. It will not be deleted automatically.</p>}
      <button type="button" className="button-secondary" ref={deleteCancelButton} disabled={busy}
        onClick={() => setDeleteConfirmation(null)}>Keep note</button>
      <button type="button" disabled={lifecycleBlocked} onClick={() => {
        if (deleteConfirmation === 'reauth' && session.id) {
          auth.continuation.returnIntent = `/notes/${session.id}`
          allowNavigation.current = true
          navigate('/reauth')
        } else void permanentlyDelete(deleteConfirmation === 'publication')
      }}>{deleteConfirmation === 'reauth' ? dirty ? 'Discard draft and confirm identity' : 'Confirm identity' : deleteConfirmation === 'publication' ? 'Unpublish and permanently delete' : dirty ? 'Permanently delete and discard draft' : 'Permanently delete'}</button>
    </div></div>}
    {trashConfirmation && <div className="dialog-backdrop"><div className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="trash-title"
      onKeyDown={event => {
        if (event.key === 'Escape' && !busy) { setTrashConfirmation(null); trashButton.current?.focus() }
        if (event.key === 'Tab') {
          event.preventDefault()
          const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
          const position = buttons.indexOf(document.activeElement as HTMLButtonElement)
          buttons[(position + (event.shiftKey ? buttons.length - 1 : 1)) % buttons.length]?.focus()
        }
      }}>
      <h2 id="trash-title">{trashConfirmation === 'publication' ? 'Unpublish the current public copy and trash?' : 'Trash with unsaved changes?'}</h2>
      <p>Your exact title and Markdown draft will remain in this tab, but will not be saved. Restore the note before saving. Leaving can lose the draft.</p>
      {trashConfirmation === 'publication' && <p>Continuing will unpublish the current public copy. Restore will not republish it.</p>}
      <button type="button" ref={trashConfirmButton} disabled={lifecycleBlocked}
        onClick={() => void changeLifecycle('trash', trashConfirmation === 'publication', trashConfirmationEtag)}>
        {trashConfirmation === 'publication' ? 'Unpublish and trash' : 'Trash and keep draft'}</button>
      <button type="button" className="button-secondary" disabled={busy}
        onClick={() => { setTrashConfirmation(null); trashButton.current?.focus() }}>Keep editing</button>
    </div></div>}
  </NotesWorkspace>
}
