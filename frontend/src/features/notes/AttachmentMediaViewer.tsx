import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { attachmentsApi, attachmentContentUrl, attachmentDescription, attachmentIssue } from './AttachmentsApi'
import { noteKeys } from './NotesKeys'

export function AttachmentMediaViewer({ auth, noteId, attachmentId }: { auth: AuthRuntime; noteId: string; attachmentId: string }) {
  const [failed, setFailed] = useState(false)
  const detail = useQuery({ queryKey: noteKeys.attachment(auth.session.viewerScope, noteId, attachmentId),
    queryFn: ({ signal }) => attachmentsApi.detail(auth, noteId, attachmentId, signal), retry: false, staleTime: 0 }, auth.queries)
  const file = detail.data?.value, url = attachmentContentUrl(noteId, attachmentId)
  return <>
    <h2 id="attachment-viewer-title">{file && !detail.isError ? file.displayFilename : 'Attachment'}</h2>
    {detail.isPending && <p role="status">Loading attachment…</p>}
    {detail.isError && <p role="alert">{attachmentIssue(detail.error)} <button type="button" onClick={() => void detail.refetch()}>Retry attachment</button></p>}
    {file && !detail.isError && <><p>{attachmentDescription(file)}</p>
      {failed || file.storageState !== 'stored' || file.validationState !== 'accepted' ? <p role="alert">This attachment could not be loaded.</p>
        : <section className="notes-attachment-viewer" aria-label="Attachment content">
          {file.mediaKind === 'image' && <img src={url} alt={file.displayFilename} onError={() => setFailed(true)} />}
          {file.mediaKind === 'audio' && <audio controls preload="metadata" src={url} onError={() => setFailed(true)} aria-label={file.displayFilename} />}
          {file.mediaKind === 'video' && <video controls preload="metadata" src={url} onError={() => setFailed(true)} aria-label={file.displayFilename} />}
          {file.mediaKind === 'pdf' && <a href={url} target="_blank" rel="noopener noreferrer">Open PDF</a>}
        </section>}</>}
  </>
}
