import { AttachmentLimits } from './AttachmentLimits'
import { lazy, Suspense, useEffect, useLayoutEffect, useReducer, useRef, useState } from 'react'
import { useBlocker, useLocation, useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { NotesWorkspace } from './NotesWorkspace'
import { FormDialog } from '../../app/ui/FormDialog'
import { ConfirmDialog } from '../../app/ui/ConfirmDialog'
import { Icon } from '../../app/ui/Icon'
import { emptyEditorSession, isDirty, noteEditorSession } from './NoteEditorSession'
import { noteKeys, notePreferenceKeys } from './NotesKeys'
import { notesApi } from './NotesApi'
import { attachmentPreflight } from './AttachmentAcceptance'
import { uploadAttachment } from './AttachmentUploadTransport'
import { attachmentIssue } from './AttachmentsApi'
import { featureError } from '../../app/ui/featureError'
import { safeDestination } from '../auth/SafeDestination'
import { ApiProblemError } from '../../app/api/ProblemDetailsDecoder'
import { FileDropSurface } from './FileDropSurface'
import { LocalImagePreview } from './LocalImagePreview'
const Editor = lazy(() => import('./MarkdownEditorAdapter').then(m => ({ default: m.MarkdownEditorAdapter })))

function StagedFile({ file, remove, disabled }: { file: File; remove: () => void; disabled: boolean }) {
  return <li><LocalImagePreview file={file} /><span>{file.name}</span><button type="button" className="button-secondary" disabled={disabled} onClick={remove} aria-label={`Remove ${file.name}`}>Remove</button></li>
}

export function NewNotePage({ auth }: { auth: AuthRuntime }) {
  const navigate = useNavigate(), location = useLocation(), scope = auth.session.viewerScope
  const [session, dispatch] = useReducer(noteEditorSession, emptyEditorSession)
  const [files, setFiles] = useState<File[]>([]), [ai, setAi] = useState(false), [initialAi, setInitialAi] = useState(false)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [confirm, setConfirm] = useState(false)
  const [created, setCreated] = useState<string | null>(null), [ambiguous, setAmbiguous] = useState(false)
  const initialized = useRef(false), allowLeave = useRef(false), fileInput = useRef<HTMLInputElement>(null), controller = useRef<AbortController | null>(null)
  const busyRef = useRef(false)
  const [leavingBusy, setLeavingBusy] = useState(false)
  const [transfer, setTransfer] = useState<{ name: string; index: number; total: number; percent: number | null; validating: boolean } | null>(null)
  const [elapsed, setElapsed] = useState(0)
  useEffect(() => { if (!busy) return; const started = Date.now(); setElapsed(0); const timer = window.setInterval(() => setElapsed(Math.floor((Date.now() - started) / 1000)), 1000); return () => window.clearInterval(timer) }, [busy])
  const preference = useQuery({ queryKey: notePreferenceKeys.current(scope), queryFn: () => notesApi.preference(auth), retry: false }, auth.queries)
  useLayoutEffect(() => { if (preference.data !== undefined && !initialized.current) { initialized.current = true; setAi(preference.data); setInitialAi(preference.data) } }, [preference.data])
  useEffect(() => () => controller.current?.abort(), [])
  const dirty = isDirty(session) || ai !== initialAi || files.length > 0
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && !allowLeave.current && currentLocation.pathname !== nextLocation.pathname)
  const destination = safeDestination(location.state?.from) ?? '/notes'
  useEffect(() => { if (!dirty) return; const handler = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = '' }; window.addEventListener('beforeunload', handler); return () => window.removeEventListener('beforeunload', handler) }, [dirty])
  function close() { if (busy) { setLeavingBusy(true); return }; if (dirty && !created) setConfirm(true); else { allowLeave.current = true; navigate(created ? `/notes/${created}` : destination, { replace: true }) } }
  function choose(selected: FileList | readonly File[] | null) {
    if (!selected || busyRef.current || created || confirm || blocker.state === 'blocked') return
    const next = Array.from(selected)
    if (!next.length) { setError('Choose files, not a folder or link.'); return }
    if (files.length + next.length > 5) { setError('Choose up to five files at a time.'); return }
    for (const file of next) { const failure = attachmentPreflight(file); if (failure) { setError(failure); return } }
    const identities = [...files, ...next].map(f => `${f.name}:${f.size}:${f.lastModified}`)
    if (new Set(identities).size !== identities.length) { setError('That file is already selected.'); return }
    setError(''); setFiles(previous => [...previous, ...next]); if (fileInput.current) fileInput.current.value = ''
  }
  async function create() {
    if (busyRef.current || confirm || blocker.state === 'blocked' || ambiguous || preference.isPending || preference.isError || (!created && !session.draft.title.trim() && !session.draft.markdown.trim() && !files.length)) return
    const abort = new AbortController(); controller.current = abort
    const current = () => !abort.signal.aborted && auth.session.viewerScope === scope && auth.state === 'authenticated'
    busyRef.current = true
    setBusy(true); setTransfer(null); setError('')
    let id = created
    try {
      if (!id) {
        const title = session.draft.title.trim() || files[0]?.name.replace(/\.[^.]+$/, '').slice(0, 500) || 'Untitled note'
        dispatch({ type: 'saving' })
        const result = await notesApi.create(auth, title, session.draft.markdown, ai)
        if (!current()) return
        id = result.value.id; setCreated(id); dispatch({ type: 'saved', server: result })
        auth.queries.setQueryData(noteKeys.core(scope, id), result)
      }
      for (const [index, file] of files.entries()) {
        setTransfer({ name: file.name, index: index + 1, total: files.length, percent: null, validating: false })
        await uploadAttachment(auth.csrf, id, file, { signal: abort.signal,
          onProgress: percent => { if (current()) setTransfer(previous => previous && { ...previous, percent }) },
          onUploadComplete: () => { if (current()) setTransfer(previous => previous && { ...previous, percent: null, validating: true }) },
        })
        if (!current()) return
        setFiles(previous => previous.filter(f => f !== file))
      }
      await auth.queries.invalidateQueries({ queryKey: noteKeys.lists(scope) })
      if (current()) { allowLeave.current = true; navigate(`/notes/${id}`, { replace: true }) }
    } catch (e) {
      if (!current()) return
      if (id) setError(`Note created. ${attachmentIssue(e)} Check its files before retrying the remaining uploads.`)
      else { dispatch({ type: 'failed' }); setError(featureError(e)); if (!(e instanceof ApiProblemError) || e.problem.status >= 500) { setAmbiguous(true); setError('Creation could not be confirmed. Check your Notes before trying again.') } }
    } finally { busyRef.current = false; if (current()) { setBusy(false); setTransfer(null); void auth.queries.invalidateQueries({ queryKey: noteKeys.lists(scope) }) } }
  }
  useEffect(() => { const handler = (e: KeyboardEvent) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); if (!e.isComposing) void create() } }; document.addEventListener('keydown', handler); return () => document.removeEventListener('keydown', handler) })
  const discardOpen = confirm || blocker.state === 'blocked' || leavingBusy
  return <><div inert><NotesWorkspace auth={auth} modalOpen><h1>Notes</h1></NotesWorkspace></div>
    <FormDialog title="New note" busy={busy} onClose={close} inactive={discardOpen} hideClose>
      <FileDropSurface className="new-note-composer" label="New note file drop area" onFiles={choose} disabled={busy || !!created || discardOpen} inert={discardOpen}>
        {preference.isError && <p role="alert">Could not load your Note preference. <button type="button" onClick={() => void preference.refetch()}>Retry</button></p>}
        <label className="visually-hidden" htmlFor="new-note-title">Title</label><input id="new-note-title" placeholder="Untitled note" maxLength={500} value={session.draft.title} disabled={busy || !!created} onChange={e => dispatch({ type: 'edit', field: 'title', value: e.target.value })} />
        <div className="new-note-media-action"><button type="button" className="button-secondary" disabled={busy || !!created} onClick={() => fileInput.current?.click()}><Icon name="attach" />Add files</button><span>or drop files here · up to 5</span><input ref={fileInput} hidden className="visually-hidden" aria-label="Choose files for new note" type="file" multiple accept=".png,.jpg,.jpeg,.wav,.mp4,.pdf" onChange={e => choose(e.target.files)} /></div>
        <AttachmentLimits /><Suspense fallback={<p role="status">Loading editor…</p>}><Editor value={session.draft.markdown} disabled={busy || !!created} onChange={value => dispatch({ type: 'edit', field: 'markdown', value })} /></Suspense>
        {files.length > 0 && <ul className="new-note-staged-files" aria-label="Files ready to upload">{files.map(file => <StagedFile key={`${file.name}:${file.size}:${file.lastModified}`} file={file} disabled={busy || !!created} remove={() => setFiles(previous => previous.filter(f => f !== file))} />)}</ul>}
        {busy && <div className="attachment-upload-status" aria-busy="true">
          <p role="status">{transfer ? `${transfer.validating ? 'Checking' : 'Uploading'} file ${transfer.index} of ${transfer.total}: ${transfer.name}` : created ? 'Opening your note…' : 'Saving your note…'} · {elapsed}s</p>
          {transfer && (transfer.percent !== null && !transfer.validating ? <progress aria-label="File upload" max={100} value={transfer.percent} /> : <progress aria-label={transfer.validating ? 'Checking file' : 'File upload'} />)}
          {transfer?.validating && <p>The upload reached the server. Checking the file before adding it.</p>}
        </div>}
        {error && <p role="alert">{error}</p>}
        <footer className="new-note-footer"><label className="notes-checkbox"><input type="checkbox" checked={ai} disabled={busy || !!created || preference.isPending} onChange={e => setAi(e.target.checked)} />Use this note with AI</label><div className="actions"><button type="button" className="button-secondary" onClick={close}>{created ? 'Open note' : busy ? 'Leave' : 'Cancel'}</button><button type="button" disabled={busy || ambiguous || preference.isPending || preference.isError || (!created && !session.draft.title.trim() && !session.draft.markdown.trim() && !files.length)} onClick={() => void create()}>{busy ? transfer ? transfer.validating ? 'Checking file…' : 'Uploading…' : 'Saving…' : created ? 'Retry remaining uploads' : 'Create note'}</button></div></footer>
      </FileDropSurface>
    </FormDialog>
    {leavingBusy && <ConfirmDialog title="Stop waiting?" cancelText="Keep waiting" onCancel={() => setLeavingBusy(false)} onConfirm={() => {
      allowLeave.current = true; controller.current?.abort(); navigate(created ? `/notes/${created}` : '/notes', { replace: true })
    }} confirmText={created ? 'Open saved note' : 'Leave and check Notes'}><p>{created ? 'Your note is saved. The current upload may still finish on the server. Refresh its files before retrying. Files still waiting will need to be added again.' : 'Saving may still finish on the server. Check Notes before creating it again. Selected files that have not uploaded will need to be added again.'}</p></ConfirmDialog>}
    {discardOpen && !leavingBusy && <ConfirmDialog title="Leave with unsaved changes?" cancelText="Keep writing" busy={busy} onCancel={() => { setConfirm(false); if (blocker.state === 'blocked') blocker.reset() }} onConfirm={() => { allowLeave.current = true; setConfirm(false); if (blocker.state === 'blocked') blocker.proceed(); else navigate(destination, { replace: true }) }} confirmText="Discard changes and leave"><p>Your unsaved writing and selected files will be removed.</p></ConfirmDialog>}
  </>
}
