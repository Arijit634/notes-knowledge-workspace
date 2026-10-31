import { AttachmentLimits } from './AttachmentLimits'
import { useCallback, useEffect, useImperativeHandle, useRef, useState, type Ref } from 'react'
import { createPortal } from 'react-dom'
import { useInfiniteQuery, type InfiniteData } from '@tanstack/react-query'
import type { CursorPage, Etagged } from '../../app/api/ApiClient'
import { ApiProblemError } from '../../app/api/ProblemDetailsDecoder'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { attachmentsApi, attachmentContentUrl, attachmentDescription, attachmentIssue, type AttachmentCore } from './AttachmentsApi'
import { noteKeys } from './NotesKeys'
import { AttachmentMediaViewer } from './AttachmentMediaViewer'
import { uploadAttachment } from './AttachmentUploadTransport'
import { attachmentPreflight } from './AttachmentAcceptance'
import { restoreFocus } from '../../app/ui/ConfirmDialog'
import { Icon } from '../../app/ui/Icon'

export type AttachmentDropHandle = { dropFiles: (files: readonly File[]) => void }
export function AttachmentPanel({ auth, noteId, lifecycle, onModalChange, initialAttachmentId, sourceIntentKey, sourceLocation, ref }: {
  auth: AuthRuntime; noteId: string; lifecycle: string; onModalChange: (open: boolean) => void
  initialAttachmentId?: string; sourceIntentKey?: string; sourceLocation?: unknown
  ref?: Ref<AttachmentDropHandle>
}) {
  const scope = auth.session.viewerScope
  const list = useInfiniteQuery({ queryKey: noteKeys.attachments(scope, noteId),
    queryFn: ({ pageParam, signal }) => attachmentsApi.list(auth, noteId, pageParam, signal),
    initialPageParam: null as string | null, getNextPageParam: page => page.nextCursor ?? undefined, retry: false }, auth.queries)
  const unavailable = list.error instanceof ApiProblemError && list.error.problem.status === 404
  const files = unavailable ? [] : [...new Map(list.data?.pages.flatMap(page => page.items).map(file => [file.id, file] as const) ?? []).values()]
  const [busy, setBusy] = useState<'upload' | 'detail' | 'remove' | null>(null)
  const [error, setError] = useState(''), [status, setStatus] = useState('')
  const [confirmation, setConfirmation] = useState<Etagged<AttachmentCore> | null>(null)
  const [viewing, setViewing] = useState<string | null>(null)
  useEffect(() => { if (initialAttachmentId) setViewing(initialAttachmentId) }, [initialAttachmentId, sourceIntentKey])
  const [progress, setProgress] = useState<number | null>(null)
  const [validating, setValidating] = useState(false)
  const input = useRef<HTMLInputElement>(null), cancel = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLElement>(null), invoker = useRef<HTMLElement | null>(null)
  const dialog = useRef<HTMLDivElement>(null)
  const viewerClose = useRef<HTMLButtonElement>(null)
  const controller = useRef<AbortController | null>(null), alive = useRef(false)
  const retryFile = useRef<File | null>(null)
  const uploading = useRef(false)
  const current = () => alive.current && auth.session.viewerScope === scope
  useEffect(() => {
    alive.current = true
    const clear = () => { alive.current = false; controller.current?.abort(); retryFile.current = null }
    const unregister = auth.sensitive.register(clear)
    return () => { clear(); unregister() }
  }, [auth])
  useEffect(() => { onModalChange(confirmation !== null || viewing !== null)
    if (confirmation) cancel.current?.focus(); else if (viewing) viewerClose.current?.focus()
    return () => onModalChange(false) }, [confirmation, viewing, onModalChange])
  useEffect(() => { if (busy === 'remove') dialog.current?.focus() }, [busy])
  function close() { onModalChange(false); setConfirmation(null); setViewing(null); requestAnimationFrame(() => {
    restoreFocus(invoker.current, panel.current)
  }) }
  async function invalidate(id?: string, removed = false) {
    if (id) auth.queries.removeQueries({ queryKey: noteKeys.attachment(scope, noteId, id), exact: true })
    if (id && removed) auth.queries.setQueryData<InfiniteData<CursorPage<AttachmentCore>>>(noteKeys.attachments(scope, noteId), data =>
      data ? { ...data, pages: data.pages.map(page => ({ ...page, items: page.items.filter(file => file.id !== id) })) } : data)
    await auth.queries.invalidateQueries({ queryKey: noteKeys.attachments(scope, noteId), exact: true })
    await auth.queries.invalidateQueries({ queryKey: noteKeys.lists(scope) })
  }
  function begin(operation: 'upload' | 'detail' | 'remove') {
    controller.current = new AbortController(); setBusy(operation); setError(''); setStatus(''); setProgress(null); setValidating(false)
    return controller.current.signal
  }
  async function upload(file: File) {
    if (busy || uploading.current || lifecycle === 'trashed' || unavailable || confirmation || viewing) return
    const invalid = attachmentPreflight(file)
    if (invalid) { setError(invalid); retryFile.current = null; if (input.current) input.current.value = ''; return }
    retryFile.current = file
    uploading.current = true
    const signal = begin('upload')
    try {
      const entry = await uploadAttachment(auth.csrf, noteId, file, { signal,
        onProgress: value => { if (current() && !signal.aborted) setProgress(value) },
        onUploadComplete: () => { if (current() && !signal.aborted) setValidating(true) } })
      if (!current() || signal.aborted) return
      retryFile.current = null
      auth.queries.setQueryData(noteKeys.attachment(scope, noteId, entry.value.id), entry)
      await invalidate()
      if (current()) setStatus('File added.')
    } catch (failure) { if (current()) {
      if (signal.aborted) {
        setStatus('Upload stopped in this tab. Refresh files if it had already finished on the server.')
        await invalidate()
      } else { setError(attachmentIssue(failure)); if (failure instanceof ApiProblemError && [413, 415, 422].includes(failure.problem.status)) retryFile.current = null }
    } }
    finally { uploading.current = false; if (current() && controller.current?.signal === signal) { setBusy(null); if (input.current) input.current.value = '' } }
  }
  function dropFiles(selected: readonly File[]) {
    if (busy || uploading.current || lifecycle === 'trashed' || unavailable || confirmation || viewing) return
    if (selected.length !== 1) { setError('Drop one file at a time.'); return }
    void upload(selected[0])
  }
  useImperativeHandle(ref, () => ({ dropFiles }))
  async function prepare(file: AttachmentCore, button: HTMLButtonElement) {
    if (busy) return
    invoker.current = button
    const signal = begin('detail')
    try {
      // Always fetch current metadata: list rows do not carry mutation validators.
      const entry = await attachmentsApi.detail(auth, noteId, file.id, signal)
      if (current() && !signal.aborted) { onModalChange(true); setConfirmation(entry) }
    } catch (failure) {
      if (current() && !signal.aborted) {
        setError(attachmentIssue(failure))
        if (failure instanceof ApiProblemError && failure.problem.status === 404) await invalidate(file.id, true)
      }
    } finally { if (current()) setBusy(null) }
  }
  async function remove() {
    if (!confirmation || busy) return
    const entry = confirmation, signal = begin('remove')
    try {
      await attachmentsApi.remove(auth, noteId, entry.value.id, entry.etag, signal)
      if (!current() || signal.aborted) return
      close(); await invalidate(entry.value.id, true)
      if (current()) setStatus('File removed.')
    } catch (failure) {
      if (!current() || signal.aborted) return
      if (failure instanceof ApiProblemError && [404, 412].includes(failure.problem.status)) {
        close(); await invalidate(entry.value.id, failure.problem.status === 404)
        if (!current()) return
        if (failure.problem.status === 404) setStatus('That attachment is no longer available.')
        else {
          // Re-observe for review, never replay the rejected mutation with a fresh ETag.
          try { await attachmentsApi.detail(auth, noteId, entry.value.id, signal)
            if (current()) setError('This attachment changed. Review it before removing.')
          } catch (next) { if (current()) {
            if (next instanceof ApiProblemError && next.problem.status === 404) { await invalidate(entry.value.id, true); if (current()) setStatus('That attachment is no longer available.') }
            else setError(attachmentIssue(next))
          } }
        }
      } else { setError(attachmentIssue(failure)); if (failure instanceof ApiProblemError && [413, 415, 422].includes(failure.problem.status)) retryFile.current = null }
    } finally { if (current()) setBusy(null) }
  }
  return <><section ref={panel} tabIndex={-1} className="notes-tool notes-attachments" aria-label="Attachments" inert={!!confirmation || !!viewing}
    onDragOver={event => { if (event.dataTransfer.types.includes('Files')) event.preventDefault() }}
    onDrop={event => { event.preventDefault(); if (busy || lifecycle === 'trashed' || unavailable) return
      dropFiles(Array.from(event.dataTransfer.files))
    }}>
    <header className="attachment-panel-heading"><h2>Files</h2><button type="button" className="button-secondary" disabled={busy !== null || lifecycle === 'trashed' || unavailable} onClick={() => input.current?.click()}><Icon name="attach" />Add file</button></header>
    {lifecycle !== 'trashed' && <p className="attachment-drop-hint">Drop a file anywhere in this note to add it.</p>}
    <AttachmentLimits />
    <label className="visually-hidden" htmlFor="note-attachment-file">Add file</label>
    <input ref={input} id="note-attachment-file" hidden type="file" accept=".png,.jpg,.jpeg,.wav,.mp4,.pdf"
      disabled={busy !== null || lifecycle === 'trashed' || unavailable} onChange={event => {
        const file = event.target.files?.[0]; if (file) void upload(file)
      }} />
    {lifecycle === 'trashed' && <p>Restore this note before adding more files.</p>}
    {busy === 'upload' && <div role="status">{validating ? 'Validating file…' : `Uploading…${progress === null ? '' : ` ${progress}%`}`}
      {!validating && <progress aria-label="Uploading file" max={100} value={progress ?? undefined} />}
      <button type="button" className="button-secondary" onClick={() => controller.current?.abort()}>Stop upload</button></div>}
    {busy === 'detail' && <p role="status">Checking file…</p>}
    {status && <p role="status">{status}</p>}{error && <p role="alert">{error}</p>}
    {!busy && retryFile.current && (error || status) && <><p>Refresh files first if the request may already have finished on the server.</p><button type="button" className="button-secondary" onClick={() => { if (retryFile.current) void upload(retryFile.current) }}>Retry selected file</button></>}
    {list.isPending && <p role="status">Loading attachments…</p>}
    {list.isError && <p role="alert">{attachmentIssue(list.error)} <button type="button" onClick={() => void list.refetch()}>Retry files</button></p>}
    {!list.isPending && !list.isError && files.length === 0 && <p>No attachments yet.</p>}
    {files.length > 0 && <><p>{files.length} {files.length === 1 ? 'file' : 'files'}{list.hasNextPage ? ' loaded' : ''}</p>
      <ul className="notes-attachment-list">{files.map(file => <li key={file.id}>
        {file.mediaKind === 'image' && <button type="button" className="attachment-image-preview" aria-label={`Preview ${file.displayFilename}`} onClick={event => { invoker.current = event.currentTarget; setViewing(file.id) }}><img src={attachmentContentUrl(noteId, file.id)} alt={file.displayFilename} loading="lazy" /></button>}
        {(file.mediaKind === 'audio' || file.mediaKind === 'video') && file.storageState === 'stored' && file.validationState === 'accepted' && <InlineMedia kind={file.mediaKind} src={attachmentContentUrl(noteId, file.id)} name={file.displayFilename} inactive={!!viewing || !!confirmation} />}
        <strong>{file.displayFilename}</strong><span>{attachmentDescription(file)}</span>
        <div className="notes-actions"><button type="button" className="button-secondary" disabled={busy !== null} onClick={event => {
          invoker.current = event.currentTarget; onModalChange(true); setViewing(file.id)
        }}>Open <span className="attachment-sr-only">{file.displayFilename}</span></button>
          <button type="button" className="button-secondary" disabled={busy !== null} onClick={event => void prepare(file, event.currentTarget)}>Remove <span className="attachment-sr-only">{file.displayFilename}</span></button></div>
      </li>)}</ul></>}
    <button type="button" className="button-secondary" disabled={busy !== null || list.isFetching} onClick={() => void list.refetch()}>Refresh files</button>
    {list.hasNextPage && <button type="button" className="button-secondary" disabled={list.isFetchingNextPage}
      onClick={() => void list.fetchNextPage()}>Load more files</button>}
  </section>{(confirmation || viewing) && createPortal(<div className="notes-layout notes-attachment-modal"><div className="dialog-backdrop">
    <div ref={dialog} tabIndex={-1} className={`confirm-dialog${viewing ? ' notes-media-dialog' : ''}`} role="dialog" aria-modal="true"
      aria-labelledby={viewing ? 'attachment-viewer-title' : 'attachment-remove-title'} aria-describedby={viewing ? undefined : 'attachment-remove-help'}
      onKeyDown={event => {
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); event.stopPropagation() }
        if (event.key === 'Escape' && busy !== 'remove') close()
        if (event.key === 'Tab') { event.preventDefault()
          const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), a[href], audio[controls], video[controls]'))
          if (buttons.length === 0) { event.currentTarget.focus(); return }
          const index = buttons.indexOf(document.activeElement as HTMLElement)
          buttons[(index + (event.shiftKey ? buttons.length - 1 : 1)) % buttons.length]?.focus()
        }
      }}>
      {viewing ? <><button ref={viewerClose} type="button" className="button-secondary" onClick={close}>Close attachment</button>
        <AttachmentMediaViewer key={viewing} auth={auth} noteId={noteId} attachmentId={viewing} sourceLocation={viewing === initialAttachmentId ? sourceLocation : undefined} /></>
        : confirmation && <><h2 id="attachment-remove-title">Remove attachment?</h2><p id="attachment-remove-help">“{confirmation.value.displayFilename}” will no longer be available from this note.</p>
      <button type="button" ref={cancel} className="button-secondary" disabled={busy === 'remove'} onClick={close}>Keep file</button>
      <button type="button" disabled={busy === 'remove'} onClick={() => void remove()}>{busy === 'remove' ? 'Removing…' : 'Remove file'}</button>
      {error && <p role="alert">{error}</p>}</>}
    </div></div></div>, document.body)}</>
}

function InlineMedia({ kind, src, name, inactive }: { kind: 'audio' | 'video'; src: string; name: string; inactive: boolean }) {
  const ref = useRef<HTMLMediaElement | null>(null)
  const [failed, setFailed] = useState(false)
  const nativeRef = useCallback((media: HTMLMediaElement | null) => {
    ref.current = media
    if (!media) return
    if (media.getAttribute('src') !== src) media.setAttribute('src', src)
    return () => { media.pause(); media.removeAttribute('src'); media.load(); ref.current = null }
  }, [src])
  useEffect(() => { if (inactive) ref.current?.pause() }, [inactive])
  if (failed) return <p role="status">Preview unavailable in this browser. Open or download the file.</p>
  return kind === 'video' ? <video ref={nativeRef} src={src} controls preload="metadata" aria-label={name} onError={() => setFailed(true)} /> : <audio ref={nativeRef} src={src} controls preload="none" aria-label={name} onError={() => setFailed(true)} />
}
