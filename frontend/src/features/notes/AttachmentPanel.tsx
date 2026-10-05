import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router'
import { useInfiniteQuery, type InfiniteData } from '@tanstack/react-query'
import type { CursorPage, Etagged } from '../../app/api/ApiClient'
import { ApiProblemError } from '../../app/api/ProblemDetailsDecoder'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { attachmentsApi, attachmentDescription, attachmentIssue, type AttachmentCore } from './AttachmentsApi'
import { noteKeys } from './NotesKeys'

export function AttachmentPanel({ auth, noteId, lifecycle, onModalChange }: {
  auth: AuthRuntime; noteId: string; lifecycle: string; onModalChange: (open: boolean) => void
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
  const input = useRef<HTMLInputElement>(null), cancel = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLElement>(null), invoker = useRef<HTMLElement | null>(null)
  const dialog = useRef<HTMLDivElement>(null)
  const controller = useRef<AbortController | null>(null), alive = useRef(false)
  const current = () => alive.current && auth.session.viewerScope === scope
  useEffect(() => { alive.current = true; return () => { alive.current = false; controller.current?.abort() } }, [])
  useEffect(() => { onModalChange(confirmation !== null); if (confirmation) cancel.current?.focus()
    return () => onModalChange(false) }, [confirmation, onModalChange])
  useEffect(() => { if (busy === 'remove') dialog.current?.focus() }, [busy])
  function close() { onModalChange(false); setConfirmation(null); requestAnimationFrame(() => {
    if (invoker.current?.isConnected) invoker.current.focus(); else panel.current?.focus()
  }) }
  async function invalidate(id?: string, removed = false) {
    if (id) auth.queries.removeQueries({ queryKey: noteKeys.attachment(scope, noteId, id), exact: true })
    if (id && removed) auth.queries.setQueryData<InfiniteData<CursorPage<AttachmentCore>>>(noteKeys.attachments(scope, noteId), data =>
      data ? { ...data, pages: data.pages.map(page => ({ ...page, items: page.items.filter(file => file.id !== id) })) } : data)
    await auth.queries.invalidateQueries({ queryKey: noteKeys.attachments(scope, noteId), exact: true })
  }
  function begin(operation: 'upload' | 'detail' | 'remove') {
    controller.current = new AbortController(); setBusy(operation); setError(''); setStatus('')
    return controller.current.signal
  }
  async function upload(file: File) {
    if (busy || lifecycle === 'trashed') return
    if (file.size > 25 * 1024 * 1024) { setError('That file is too large. Choose a file up to 25 MiB.'); if (input.current) input.current.value = ''; return }
    const signal = begin('upload')
    try {
      const entry = await attachmentsApi.upload(auth, noteId, file, signal)
      if (!current() || signal.aborted) return
      auth.queries.setQueryData(noteKeys.attachment(scope, noteId, entry.value.id), entry)
      await invalidate()
      if (current()) setStatus('File added.')
    } catch (failure) { if (current() && !signal.aborted) setError(attachmentIssue(failure)) }
    finally { if (current() && controller.current?.signal === signal) { setBusy(null); if (input.current) input.current.value = '' } }
  }
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
      } else setError(attachmentIssue(failure))
    } finally { if (current()) setBusy(null) }
  }
  return <><section ref={panel} tabIndex={-1} className="notes-tool notes-attachments" aria-label="Attachments" inert={!!confirmation}>
    <h2>Attachments</h2><p>Adding or removing files does not save your note text.</p>
    <label htmlFor="note-attachment-file">Add file</label>
    <input ref={input} id="note-attachment-file" type="file" accept=".png,.jpg,.jpeg,.wav,.mp4,.pdf"
      disabled={busy !== null || lifecycle === 'trashed' || unavailable} onChange={event => {
        const file = event.target.files?.[0]; if (file) void upload(file)
      }} />
    {lifecycle === 'trashed' && <p>Restore this note before adding more files.</p>}
    {busy === 'upload' && <div role="status">Uploading… <progress aria-label="Uploading file" /></div>}
    {busy === 'detail' && <p role="status">Checking file…</p>}
    {status && <p role="status">{status}</p>}{error && <p role="alert">{error}</p>}
    {list.isPending && <p role="status">Loading attachments…</p>}
    {list.isError && <p role="alert">{attachmentIssue(list.error)} <button type="button" onClick={() => void list.refetch()}>Retry files</button></p>}
    {!list.isPending && !list.isError && files.length === 0 && <p>No attachments yet.</p>}
    {files.length > 0 && <><p>{files.length} {files.length === 1 ? 'file' : 'files'}{list.hasNextPage ? ' loaded' : ''}</p>
      <ul className="notes-attachment-list">{files.map(file => <li key={file.id}>
        <strong>{file.displayFilename}</strong><span>{attachmentDescription(file)}</span>
        <div className="notes-actions"><Link to={`/notes/${encodeURIComponent(noteId)}/attachments/${encodeURIComponent(file.id)}`}>Open <span className="attachment-sr-only">{file.displayFilename}</span></Link>
          <button type="button" className="button-secondary" disabled={busy !== null} onClick={event => void prepare(file, event.currentTarget)}>Remove <span className="attachment-sr-only">{file.displayFilename}</span></button></div>
      </li>)}</ul></>}
    {list.hasNextPage && <button type="button" className="button-secondary" disabled={list.isFetchingNextPage}
      onClick={() => void list.fetchNextPage()}>Load more files</button>}
  </section>{confirmation && createPortal(<div className="notes-layout notes-attachment-modal"><div className="dialog-backdrop">
    <div ref={dialog} tabIndex={-1} className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="attachment-remove-title" aria-describedby="attachment-remove-help"
      onKeyDown={event => {
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); event.stopPropagation() }
        if (event.key === 'Escape' && busy !== 'remove') close()
        if (event.key === 'Tab') { event.preventDefault()
          const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
          if (buttons.length === 0) { event.currentTarget.focus(); return }
          const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
          buttons[(index + (event.shiftKey ? buttons.length - 1 : 1)) % buttons.length]?.focus()
        }
      }}>
      <h2 id="attachment-remove-title">Remove attachment?</h2><p id="attachment-remove-help">“{confirmation.value.displayFilename}” will no longer be available from this note.</p>
      <button type="button" ref={cancel} className="button-secondary" disabled={busy === 'remove'} onClick={close}>Keep file</button>
      <button type="button" disabled={busy === 'remove'} onClick={() => void remove()}>{busy === 'remove' ? 'Removing…' : 'Remove file'}</button>
      {error && <p role="alert">{error}</p>}
    </div></div></div>, document.body)}</>
}
