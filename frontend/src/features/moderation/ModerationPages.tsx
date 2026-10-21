import { useEffect, useState } from 'react'
import { Link, useLocation, useParams, useSearchParams } from 'react-router'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { NotesWorkspace } from '../notes/NotesWorkspace'
import { MarkdownView } from '../notes/MarkdownView'
import { ConfirmDialog } from '../../app/ui/ConfirmDialog'
import { featureError } from '../../app/ui/featureError'
import { usePrivateScope } from '../../app/security/usePrivateScope'
import { useRetryAfter } from '../../app/ui/useRetryAfter'
import { publicationChanged } from '../publishing/PublicationCache'
import { validId } from '../publishing/PublishingApi'
import { dateLabel } from '../discovery/PublicUi'
import { clearModeration, moderationApi, moderationBoundary, moderationDenied, moderationKeys, moderationRecent, reportAlias, reportCategories, reportStates, type Consequence } from './ModerationApi'

function useCapabilityBoundary(auth: AuthRuntime, error: unknown) {
  const [lost, setLost] = useState(false)
  useEffect(() => { if (moderationDenied(error)) { setLost(true); clearModeration(auth) } }, [auth, error])
  return lost || moderationDenied(error)
}
function ModerationError({ auth, error, denied, retry }: { auth: AuthRuntime; error: unknown; denied: boolean; retry?: () => void }) {
  const location = useLocation(), recent = moderationRecent(error)
  function remember() { const id = location.pathname.split('/')[3]; auth.continuation.returnIntent = location.pathname === '/moderation/reports' ? '/moderation/reports' : validId(id) ? `/moderation/reports/${id}` : null }
  return <section className="public-state" role="alert"><h2>{denied ? 'Moderation permission unavailable' : recent ? 'Confirm your identity' : 'Review is unavailable'}</h2><p>{denied ? 'Your current account cannot use this moderation workflow. Restricted information has been cleared.' : recent ? 'This protected workflow needs recent identity confirmation.' : featureError(error)}</p>{recent ? <Link to="/reauth" onClick={remember}>Confirm identity</Link> : !denied && retry && <button onClick={retry}>Retry</button>}<Link to="/notes">Return to workspace</Link></section>
}
export function ModerationQueuePage({ auth }: { auth: AuthRuntime }) {
  const [params, setParams] = useSearchParams(), state = params.get('state') ?? '', category = params.get('category') ?? ''
  const valid = (!state || reportStates.includes(state as typeof reportStates[number])) && (!category || reportCategories.includes(category as typeof reportCategories[number]))
  const queue = useInfiniteQuery({ queryKey: [...moderationKeys.root(auth), 'queue', { state, category }], queryFn: ({ pageParam, signal }) => moderationApi.list(auth, state, category, pageParam, signal), enabled: valid, initialPageParam: null as string | null, getNextPageParam: p => p.nextCursor ?? undefined, retry: false, staleTime: 0 }, auth.queries)
  const denied = useCapabilityBoundary(auth, queue.error)
  const items = [...new Map(queue.data?.pages.flatMap(p => p.items).map(r => [r.id, r] as const) ?? []).values()]
  function filter(field: string, value: string) { const next = new URLSearchParams(params); if (value) next.set(field, value); else next.delete(field); setParams(next) }
  return <NotesWorkspace auth={auth}><div className="moderation-page"><p className="studio-section-label">Public safety</p><h1>Report review</h1><p>Public reports and public evidence only. Review never grants private Note or account-security access.</p>
    {denied || queue.isError ? <ModerationError auth={auth} error={queue.error} denied={denied} retry={() => void queue.refetch()} /> : <><div className="public-search-form"><div><label htmlFor="report-state">State</label><select id="report-state" value={state} onChange={e => filter('state', e.target.value)}><option value="">All states</option>{reportStates.map(s => <option key={s} value={s}>{s.replace('_', ' ')}</option>)}</select></div><div><label htmlFor="queue-category">Category</label><select id="queue-category" value={category} onChange={e => filter('category', e.target.value)}><option value="">All categories</option>{reportCategories.map(c => <option key={c} value={c}>{c === 'harmfulContent' ? 'Harmful content' : c}</option>)}</select></div><p>Newest reports first</p></div>
      {!valid ? <p role="alert">Choose a supported filter.</p> : queue.isPending ? <p role="status">Checking current review access…</p> : !items.length ? <p className="public-state">No reports in this view.</p> : <ul className="moderation-queue">{items.map(r => <li key={r.id}><span className="public-availability">{r.status.replace('_', ' ')}</span><h2><Link to={`/moderation/reports/${r.id}`}>{r.category === 'harmfulContent' ? 'Harmful content' : r.category}</Link></h2><p>{r.description.slice(0, 240)}</p><time dateTime={r.submittedAt}>{dateLabel(r.submittedAt)}</time><Link to={`/moderation/reports/${r.id}`}>Review public report →</Link></li>)}</ul>}
      {queue.hasNextPage && <button disabled={queue.isFetchingNextPage} onClick={() => void queue.fetchNextPage()}>More reports</button>}</>}
  </div></NotesWorkspace>
}
export function ModerationReportPage({ auth }: { auth: AuthRuntime }) {
  const { reportId: id = '' } = useParams(), current = usePrivateScope(auth)
  const key = [...moderationKeys.root(auth), 'detail', reportAlias(auth.session.viewerScope, id)] as const
  const detail = useQuery({ queryKey: key, queryFn: ({ signal }) => moderationApi.detail(auth, id, signal), enabled: validId(id), retry: false, staleTime: 0 }, auth.queries)
  const [error, setError] = useState<unknown>(null), [busy, setBusy] = useState(false), [confirmation, setConfirmation] = useState(false), [complete, setComplete] = useState(false)
  const [consequence, setConsequence] = useState<Consequence>('none'), [dismissReason, setDismissReason] = useState('noPolicyViolation')
  const cooldown = useRetryAfter(error ?? detail.error)
  const denied = useCapabilityBoundary(auth, error ?? detail.error), recent = moderationRecent(error ?? detail.error)
  async function begin() {
    if (busy || cooldown || denied) return
    const boundary = moderationBoundary(auth)
    setBusy(true); setError(null)
    try { const d = await moderationApi.begin(auth, id); if (current() && moderationBoundary(auth) === boundary) { auth.queries.setQueryData(key, d); void auth.queries.invalidateQueries({ queryKey: [...moderationKeys.root(auth), 'queue'] }) } }
    catch (e) { if (current()) setError(e) } finally { if (current()) setBusy(false) }
  }
  async function decide() {
    if (busy || cooldown || denied || !detail.data || detail.data.report.status !== 'under_review') return
    const publicationId = detail.data.report.publicationId
    const boundary = moderationBoundary(auth)
    setBusy(true); setError(null)
    try { await moderationApi.decide(auth, id, consequence, consequence === 'none' ? dismissReason : 'confirmedPolicyViolation'); if (!current() || moderationBoundary(auth) !== boundary) return
      if (consequence !== 'none') publicationChanged(auth, publicationId)
      setConfirmation(false); setComplete(true)
      // Clear evidence before refetch; no old snapshot may remain after removal.
      auth.queries.setQueryData(key, null)
      await auth.queries.invalidateQueries({ queryKey: moderationKeys.root(auth) })
    } catch (e) { if (current()) { setConfirmation(false); setError(e) } } finally { if (current()) setBusy(false) }
  }
  const d = detail.data, hidden = denied || recent || detail.isError
  return <NotesWorkspace auth={auth} modalOpen={confirmation}><div className="moderation-page" inert={confirmation}><Link to="/moderation/reports">← Reports</Link><h1>Public report review</h1>
    {!validId(id) ? <p role="alert">Report unavailable.</p> : hidden ? <ModerationError auth={auth} error={error ?? detail.error} denied={denied} retry={() => void detail.refetch()} /> : detail.isPending ? <p role="status">Checking current review access…</p> : d && <><header className="public-sync"><span className="public-availability">{d.report.status.replace('_', ' ')}</span><h2>{d.report.category === 'harmfulContent' ? 'Harmful content' : d.report.category}</h2><p>{d.report.description}</p><p>Reported {dateLabel(d.report.submittedAt)}</p>{d.targetAvailable && <Link to={`/publication/${d.report.publicationId}`}>View current public note</Link>}</header>
      <section aria-label="Permitted public evidence"><h2>Public evidence</h2>{d.targetAvailable && d.publicEvidence ? <><h3>{d.publicEvidence.title}</h3><MarkdownView markdown={d.publicEvidence.markdown} /></> : <p>This reported public generation is no longer available. No private source is accessed.</p>}</section>
      {d.report.status === 'open' && <button disabled={busy || !!cooldown} onClick={() => void begin()}>Begin review</button>}
      {d.report.status === 'under_review' && !complete && <section className="moderation-decision"><h2>Reasoned decision</h2><label htmlFor="moderation-consequence">Consequence</label><select id="moderation-consequence" value={consequence} disabled={busy} onChange={e => setConsequence(e.target.value as Consequence)}><option value="none">Dismiss — no enforcement</option><option value="removePublication" disabled={!d.targetAvailable}>Remove this publication</option><option value="removePublicationAndSuspendResponsibleAccount" disabled={!d.targetAvailable}>Remove publication and suspend responsible account</option></select>
        {consequence === 'none' ? <><label htmlFor="moderation-reason">Reason</label><select id="moderation-reason" value={dismissReason} disabled={busy} onChange={e => setDismissReason(e.target.value)}><option value="noPolicyViolation">No policy violation</option><option value="insufficientEvidence">Insufficient evidence</option></select></> : <p>Reason: confirmed policy violation. Only this publication is removed. Suspension blocks protected operations; it does not grant private access or automatically remove all other publications.</p>}
        <button disabled={busy || !!cooldown || (consequence !== 'none' && !d.targetAvailable)} onClick={() => setConfirmation(true)}>Review decision</button></section>}
      {d.decision && <p className="success" role="status">Decision recorded: {d.decision.consequence}. Publication: {d.decision.outcomes.publication}. Account: {d.decision.outcomes.account}.</p>}
      <section><h2>Review audit</h2>{d.audit.length ? <ol className="moderation-audit">{d.audit.map((a, i) => <li key={i}><strong>{a.action}</strong> · {a.outcome}{a.reason && ` · ${a.reason}`}<time dateTime={a.occurredAt}>{dateLabel(a.occurredAt)}</time></li>)}</ol> : <p>No review action yet.</p>}</section></>}
    {complete && <p role="status">The server confirmed the decision and its required logical consequences.</p>}{cooldown > 0 && <p role="status">Try again in {cooldown} seconds.</p>}{!!error && !denied && !recent && <p role="alert">{featureError(error)} No successful enforcement was reported. Refresh and review before retrying.</p>}</div>
    {confirmation && !denied && !recent && <ConfirmDialog title="Confirm this moderation decision?" confirmText="Confirm decision" busy={busy} onCancel={() => setConfirmation(false)} onConfirm={() => void decide()}><p>{consequence === 'none' ? 'Dismiss this report with the selected reason. No publication will be removed.' : consequence === 'removePublication' ? 'Remove only the reported publication from public access.' : 'Remove the reported publication and suspend its responsible account. This grants no private-resource access.'}</p><p>Success is shown only after the server establishes all required consequences.</p></ConfirmDialog>}
  </NotesWorkspace>
}
