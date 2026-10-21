import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { attachmentsApi, attachmentContentUrl, attachmentDescription, attachmentIssue } from './AttachmentsApi'
import { noteKeys } from './NotesKeys'

export function AttachmentMediaViewer({ auth, noteId, attachmentId, sourceLocation }: { auth: AuthRuntime; noteId: string; attachmentId: string; sourceLocation?: unknown }) {
  const [failed, setFailed] = useState(false)
  const [selectedPage, setSelectedPage] = useState<number | null>(null), [zoom, setZoom] = useState(false)
  useEffect(() => { setFailed(false); setSelectedPage(null); setZoom(false) }, [noteId, attachmentId])
  const detail = useQuery({ queryKey: noteKeys.attachment(auth.session.viewerScope, noteId, attachmentId),
    queryFn: ({ signal }) => attachmentsApi.detail(auth, noteId, attachmentId, signal), retry: false, staleTime: 0 }, auth.queries)
  const file = detail.data?.value, url = attachmentContentUrl(noteId, attachmentId)
  const location = sourceLocation && typeof sourceLocation === 'object' ? sourceLocation as Record<string, unknown> : {}
  const page = typeof location.page === 'number' && Number.isSafeInteger(location.page) && location.page > 0 && location.page <= (file?.pageCount ?? 0) ? location.page : null
  const time = typeof location.timeStart === 'number' && Number.isFinite(location.timeStart) && location.timeStart >= 0 && location.timeStart <= (file?.durationSeconds ?? 0) ? location.timeStart : null
  const mediaUrl = time !== null ? `${url}#t=${time}` : url
  const activePage = Math.min(Math.max(selectedPage ?? page ?? 1, 1), file?.pageCount ?? 1)
  return <>
    <h2 id="attachment-viewer-title">{file && !detail.isError ? file.displayFilename : 'Attachment'}</h2>
    {detail.isPending && <p role="status">Loading attachment…</p>}
    {detail.isError && <p role="alert">{attachmentIssue(detail.error)} <button type="button" onClick={() => void detail.refetch()}>Retry attachment</button></p>}
    {file && !detail.isError && <><p>{attachmentDescription(file)}</p>
      {failed || file.storageState !== 'stored' || file.validationState !== 'accepted' ? <p role="alert">This attachment could not be loaded.</p>
        : <section className="notes-attachment-viewer" aria-label="Attachment content">
          {file.mediaKind === 'image' && <><button type="button" className="button-secondary" aria-pressed={zoom} onClick={() => setZoom(v => !v)}>{zoom ? 'Fit image' : 'Enlarge image'}</button><div className="studio-image-frame"><img src={url} alt={file.displayFilename} style={zoom ? { maxWidth: 'none', maxHeight: 'none', width: '150%' } : undefined} onError={() => setFailed(true)} /></div></>}
          {file.mediaKind === 'audio' && <audio controls preload="metadata" src={mediaUrl} onError={() => setFailed(true)} aria-label={file.displayFilename} />}
          {file.mediaKind === 'video' && <video controls preload="metadata" src={mediaUrl} onError={() => setFailed(true)} aria-label={file.displayFilename} />}
          {file.mediaKind === 'pdf' && <><p>{file.pageCount ? `${file.pageCount} pages` : 'PDF document'}{page ? ` · cited page ${page}` : ''}</p>
            {file.pageCount && <div className="studio-pdf-controls"><label htmlFor="pdf-page">PDF page</label><input id="pdf-page" type="number" min={1} max={file.pageCount} value={activePage} onChange={e => { const n = Number(e.target.value); if (Number.isInteger(n) && n >= 1 && n <= file.pageCount!) setSelectedPage(n) }} /></div>}
            <a href={`${url}#page=${activePage}`} target="_blank" rel="noopener noreferrer">Open PDF</a><p className="notes-help">Opens the authorized PDF in your browser’s reader. Page navigation depends on that reader; embedded active PDF content is not enabled.</p></>}
          {time !== null && <p>Evidence starts at {Math.floor(time)} seconds. Playback uses the authorized original media.</p>}
          <p><a href={url} download rel="noopener noreferrer">Download original</a></p>
        </section>}</>}
  </>
}
