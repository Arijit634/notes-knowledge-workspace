import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { attachmentsApi, attachmentContentUrl, attachmentDescription, attachmentIssue } from './AttachmentsApi'
import { noteKeys } from './NotesKeys'
import { NotesWorkspace } from './NotesWorkspace'

export function AttachmentViewerPage({ auth }: { auth: AuthRuntime }) {
  const { noteId = '', attachmentId = '' } = useParams()
  const [failed, setFailed] = useState(false)
  const detail = useQuery({ queryKey: noteKeys.attachment(auth.session.viewerScope, noteId, attachmentId),
    queryFn: ({ signal }) => attachmentsApi.detail(auth, noteId, attachmentId, signal), retry: false, staleTime: 0 }, auth.queries)
  const file = detail.data?.value, url = attachmentContentUrl(noteId, attachmentId)
  return <NotesWorkspace><Link to={`/notes/${encodeURIComponent(noteId)}`}>Back to note</Link>
    {detail.isPending && <p role="status">Loading attachment…</p>}
    {detail.isError && <p role="alert">{attachmentIssue(detail.error)} <button type="button" onClick={() => void detail.refetch()}>Retry attachment</button></p>}
    {file && !detail.isError && <><header className="notes-heading"><div><h1>{file.displayFilename}</h1><p>{attachmentDescription(file)}</p></div></header>
      {failed || file.storageState !== 'stored' || file.validationState !== 'accepted' ? <p role="alert">This attachment could not be loaded.</p>
        : <section className="notes-attachment-viewer" aria-label="Attachment content">
          {file.mediaKind === 'image' && <img src={url} alt={file.displayFilename} onError={() => setFailed(true)} />}
          {file.mediaKind === 'audio' && <audio controls preload="metadata" src={url} onError={() => setFailed(true)} aria-label={file.displayFilename} />}
          {file.mediaKind === 'video' && <video controls preload="metadata" src={url} onError={() => setFailed(true)} aria-label={file.displayFilename} />}
          {file.mediaKind === 'pdf' && <a href={url} target="_blank" rel="noopener noreferrer">Open PDF</a>}
        </section>}</>}
  </NotesWorkspace>
}
