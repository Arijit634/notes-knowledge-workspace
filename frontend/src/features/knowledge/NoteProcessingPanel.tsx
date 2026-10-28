import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { record, text, array, uuid } from '../../app/api/Contract'
import { featureError } from '../../app/ui/featureError'

const labels: Record<string, string> = { excluded: 'Excluded from AI', blocked: 'Processing blocked', queued: 'Queued', processing: 'Processing',
  reprocessing: 'Reprocessing', ready: 'Ready', failed: 'Processing failed', obsolete: 'Out of date' }
const reasons: Record<string, string> = { aiDisabled: 'AI is OFF for this note.', parentAiDisabled: 'AI is OFF on the parent note.', lifecycleExcluded: 'The saved lifecycle excludes AI processing.',
  policyUnavailable: 'Processing policy is unavailable.', policyAcknowledgementRequired: 'Current disclosure acknowledgement is required.', providerUnavailable: 'The provider is unavailable.',
  noteTextIndexingPaused: 'Note text indexing is paused. Files are processed independently.', processingNotScheduled: 'This source has not been scheduled for processing yet.',
  providerPolicyRestricted: 'Provider policy does not permit this source.', processingFailed: 'Processing did not finish. The stored file remains separate.', sourceChanged: 'The saved source changed.' }
function state(value: unknown) {
  const r = record(value), status = text(r.status, 32), reason = r.reason === null ? null : text(r.reason, 80)
  return { status: labels[status] ?? 'Status unavailable', reason: reason ? reasons[reason] ?? 'Processing is not currently available.' : null }
}
export function NoteProcessingPanel({ auth, noteId, dirty }: { auth: AuthRuntime; noteId: string; dirty: boolean }) {
  const query = useQuery({ queryKey: ['knowledge', auth.session.viewerScope, 'processing', noteId], retry: false, staleTime: 0,
    queryFn: async ({ signal }) => {
      const r = record((await auth.api.request('GET', `/api/notes/${encodeURIComponent(noteId)}/ai-processing`, { signal })).body)
      return { note: state(r.note), attachments: array(r.attachments, value => { const a = record(value); return { attachmentId: uuid(a.attachmentId), ...state(a) } }, 10000) }
    } }, auth.queries)
  return <section aria-label="Saved-source AI processing"><h3>Saved-source processing</h3><p>Upload acceptance and AI indexing are different. This read-only status does not save or change your note.</p>
    {dirty && <p>Your unsaved draft is not included.</p>}
    {query.isPending ? <p role="status">Checking processing…</p> : query.isError ? <p role="alert">{featureError(query.error)}</p> : query.data && <>
      <p>Note text · {query.data.note.status}{query.data.note.reason && ` · ${query.data.note.reason}`}</p><ul>{query.data.attachments.map((a, index) => <li key={a.attachmentId}>
        <Link to={`/notes/${noteId}`} state={{ attachmentId: a.attachmentId }}>Attachment {index + 1}</Link> · {a.status}{a.reason && <p>{a.reason}</p>}</li>)}</ul></>}
    <button type="button" className="button-secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>Refresh processing status</button>
  </section>
}
