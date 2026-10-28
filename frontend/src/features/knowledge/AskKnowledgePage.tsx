import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { NotesWorkspace } from '../notes/NotesWorkspace'
import { featureError } from '../../app/ui/featureError'
import { usePrivateScope } from '../../app/security/usePrivateScope'
import { activeOperation, knowledgeApi, type DeterministicOccurrence, type KnowledgeResult, type Operation } from './KnowledgeApi'
import { ApiProblemError } from '../../app/api/ProblemDetailsDecoder'
import { ConfirmDialog } from '../../app/ui/ConfirmDialog'
import { periodLabel, savedPeriod, type DateFilter } from './SearchPeriod'
import { processingPolicyApi } from './ProcessingPolicyApi'

export function CitationList({ citations }: { citations: readonly DeterministicOccurrence[] }) {
  return <ol className="studio-citations">{citations.map((c, index) => <li key={`${c.noteId}:${c.attachmentId}:${index}`}>
    <Link to={`/notes/${c.noteId}`} state={{ attachmentId: c.attachmentId, sourceLocation: c.location }}>Source {index + 1} · {c.attachmentId && c.location ? c.location.kind.replaceAll('_', ' ') : 'Note'}{c.location?.page ? ` · page ${c.location.page}` : ''}{c.location?.timeStart != null ? ` · ${Math.floor(c.location.timeStart)}s` : ''}</Link>
  </li>)}</ol>
}
export function KnowledgeResults({ result }: { result: KnowledgeResult }) {
  const c = result.coverage
  return <section aria-label="Knowledge results">
    {result.degraded && <p className="alert" role="status">{result.degraded === 'processingPolicyRequired'
      ? 'AI processing is not permitted for this request under the current privacy/provider policy. Review AI & Privacy, or use ordinary search.'
      : 'AI is degraded or unavailable for this request. These results may be partial; ordinary search is still available.'}</p>}
    {result.insufficientEvidence && <p role="status">There is not enough eligible evidence to answer reliably. Try a more specific question or review saved sources.</p>}
    {result.aiAnswer && <section className="studio-result" aria-label="AI answer"><h2>From your knowledge</h2>
      {result.aiAnswer.conflicting && <p className="alert">Sources disagree. Review the evidence before relying on this answer.</p>}
      {result.aiAnswer.claims.map((claim, index) => <p key={index}>{claim}</p>)}
      <h3>Evidence</h3><CitationList citations={result.citations} /></section>}
    <section className="studio-result" aria-label="Deterministic results"><h2>Saved matches</h2>
      {result.deterministicResults.length === 0 ? <p>No deterministic matches in this result.</p> : result.deterministicResults.map((item, index) =>
        <div key={index} className="studio-evidence"><strong>{item.kind.replaceAll('_', ' ')}</strong><p>{item.value}</p>
          {!item.aiEnabled && <span className="notes-pill">AI OFF · deterministic only</span>}<CitationList citations={item.occurrences} /></div>)}</section>
    <section className="studio-coverage" aria-label="Coverage"><h2>Coverage</h2><p>{c.inspectedSources} source{c.inspectedSources === 1 ? '' : 's'} inspected · {c.boundary === 'acceptedAiSources' ? 'AI-eligible sources' : c.boundary === 'acceptedNoteAndPdfText' ? 'Saved Note and PDF text' : 'Bounded retrieval'}</p>
      <p>{c.completed && !c.truncated && !c.corpusChanged ? 'Completed within the reported search boundary.' : 'Partial coverage — this is not a claim to have searched every source.'}</p>
      {c.corpusChanged && <p>Saved content changed during this request.</p>}{c.truncated && <p>A processing limit was reached.</p>}
      {c.retryRequired && <p>Submit a new request to search the current saved state.</p>}{c.classificationUncertain && <p>Semantic classification may be uncertain. Check the sources.</p>}</section>
  </section>
}
export function operationInterval(value: Operation, now: number, failed: boolean): number | false {
  const end = value.expiresAt == null ? Date.parse(value.submittedAt) + 86400000 : Date.parse(value.expiresAt)
  if (!activeOperation(value) || !Number.isFinite(end) || now >= end) return false
  if (failed) return 30000
  const age = now - Date.parse(value.submittedAt)
  return age < 60000 ? 3000 : age < 300000 ? 5000 : age < 1800000 ? 15000 : 30000
}
function OperationProgress({ auth, initial, serial }: { auth: AuthRuntime; initial: Operation; serial: number }) {
  const current = usePrivateScope(auth), [visible, setVisible] = useState(!document.hidden && navigator.onLine)
  const [cancelBusy, setCancelBusy] = useState(false), [error, setError] = useState(''), [cancelled, setCancelled] = useState(false), [now, setNow] = useState(Date.now())
  const key = ['knowledge', auth.session.viewerScope, 'active-operation', serial] as const
  useEffect(() => { const update = () => setVisible(!document.hidden && navigator.onLine)
    document.addEventListener('visibilitychange', update); window.addEventListener('online', update); window.addEventListener('offline', update)
    return () => { document.removeEventListener('visibilitychange', update); window.removeEventListener('online', update); window.removeEventListener('offline', update) } }, [])
  useEffect(() => { if (!visible) return; const timer = window.setInterval(() => setNow(Date.now()), 10000); return () => clearInterval(timer) }, [visible])
  const query = useQuery({ queryKey: key, initialData: initial, queryFn: ({ signal }) => knowledgeApi.poll(auth, initial.operationId, signal),
    enabled: q => visible && !cancelled && !(q.state.error instanceof ApiProblemError && q.state.error.problem.status === 404) && operationInterval(q.state.data ?? initial, now, false) !== false, gcTime: 0, retry: false, refetchOnWindowFocus: true, refetchOnReconnect: true,
    refetchInterval: q => !visible || cancelled || q.state.error instanceof ApiProblemError && q.state.error.problem.status === 404 ? false : operationInterval(q.state.data ?? initial, Date.now(), !!q.state.error) }, auth.queries)
  useEffect(() => { if (query.data) auth.ask.observe(query.data) }, [auth, query.data])
  async function cancel() {
    if (cancelBusy) return
    setCancelBusy(true); setError('')
    try { await knowledgeApi.cancel(auth, initial.operationId); if (current()) { setCancelled(true); auth.ask.observe({ ...initial, status: 'cancelled', result: null }); await auth.queries.cancelQueries({ queryKey: key }); auth.queries.removeQueries({ queryKey: key }) } }
    catch (e) { if (current()) setError(featureError(e)) } finally { if (current()) setCancelBusy(false) }
  }
  const value = query.data ?? initial, running = activeOperation(value) && !cancelled
  const elapsed = Math.max(0, now - Date.parse(value.submittedAt)), checked = value.inspectedSources ?? 0, total = value.totalSources
  const remaining = total && checked > 0 && elapsed > 30000 ? elapsed / checked * Math.max(0, total - checked) : null
  const expired = operationInterval(value, now, false) === false && running
  return <section className="studio-result" aria-label="Knowledge operation"><h2>Request status</h2><p role="status">{cancelled ? 'Request cancelled.' : expired ? 'This request has expired.' : value.status === 'completed' ? 'Request completed.' : value.status === 'obsolete' ? 'This request is no longer current. Submit again if needed.' : value.status === 'failed' ? 'The request could not finish.' : value.status === 'cancelled' ? 'Request cancelled.' : value.status === 'queued' ? 'Queued' : value.status === 'retry_wait' ? 'Waiting for provider availability' : 'Processing your notes…'}</p>
    <p role="status">{checked}{total != null ? ` of ${total}` : ''} sources checked</p>
    {running && !expired && <><p>{Math.floor(elapsed / 60000)}m elapsed · {remaining == null ? 'Estimating remaining time…' : `Roughly ${Math.max(1, Math.floor(remaining / 90000))}–${Math.max(2, Math.ceil(remaining / 40000))}m remaining`}</p>{total != null && total > 0 && <progress aria-label="Sources checked" max={total} value={Math.min(total, checked)} />}<p className="notes-help">Provider waits and long notes can change this estimate. You can leave this page; the request stays in History.</p></>}
    {query.dataUpdatedAt > 0 && <p className="notes-help" role="status">{query.isFetching ? 'Checking status…' : `Last checked ${new Date(query.dataUpdatedAt).toLocaleTimeString()}`}</p>}{!visible && <p>Status checks pause while this tab is hidden or offline.</p>}
    {query.isError && <p role="alert">{featureError(query.error)}{!(query.error instanceof ApiProblemError && query.error.problem.status === 404) && ' Status checks will retry; your question will not be resubmitted.'}</p>}{error && <p role="alert">{error}</p>}
    {running && !expired && <div className="actions"><button className="button-secondary" disabled={cancelBusy || query.isFetching} onClick={() => void query.refetch()}>{query.isFetching ? 'Checking…' : 'Check status'}</button><button className="button-secondary" disabled={cancelBusy} onClick={() => void cancel()}>Cancel request</button></div>}
  </section>
}
function SearchHistoryPanel({ auth, serial }: { auth: AuthRuntime; serial: number }) {
  const current = usePrivateScope(auth), [cursor, setCursor] = useState<string | null>(null), [remove, setRemove] = useState<string | 'all' | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const key = ['knowledge', auth.session.viewerScope, 'history', cursor, serial] as const
  const query = useQuery({ queryKey: key, queryFn: ({ signal }) => knowledgeApi.history(auth, cursor, signal), retry: false, gcTime: 0, staleTime: 0 }, auth.queries)
  async function open(id: string) {
    setBusy(true); setError('')
    try { const entry = await knowledgeApi.historyDetail(auth, id, new AbortController().signal); if (current()) auth.ask.resume(entry) }
    catch (e) { if (current()) setError(featureError(e)) } finally { if (current()) setBusy(false) }
  }
  async function erase() {
    if (!remove || busy) return; setBusy(true); setError('')
    try { await knowledgeApi.removeHistory(auth, remove === 'all' ? undefined : remove); if (current()) { if (remove === 'all' || auth.ask.snapshot().historyId === remove) auth.ask.clear(); setRemove(null); setCursor(null); await auth.queries.invalidateQueries({ queryKey: ['knowledge', auth.session.viewerScope, 'history'] }) } }
    catch (e) { if (current()) setError(featureError(e)) } finally { if (current()) setBusy(false) }
  }
  return <section className="studio-search-history" aria-label="Search history"><div className="notes-heading"><h2>History</h2><button className="button-secondary" disabled={busy || !query.data?.items.length} onClick={() => setRemove('all')}>Clear history</button></div>
    <p className="notes-help">Latest 50 searches · kept for 30 days</p>{query.isPending && <p role="status">Loading history…</p>}{query.isError && <p role="alert">{featureError(query.error)}</p>}{error && <p role="alert">{error}</p>}
    {query.data?.items.length === 0 && <p>No saved searches yet.</p>}<ol>{query.data?.items.map(entry => <li key={entry.id}><button className="button-secondary" disabled={busy} onClick={() => void open(entry.id)}>{entry.query}</button><span>{periodLabel(entry.savedDuring ?? null)} · {entry.status.replaceAll('_', ' ')} · {new Date(entry.submittedAt).toLocaleString()}</span><button className="button-secondary" disabled={busy} aria-label={`Remove search: ${entry.query}`} onClick={() => setRemove(entry.id)}>Remove</button></li>)}</ol>
    <div className="actions">{cursor && <button className="button-secondary" onClick={() => setCursor(null)}>Latest</button>}{query.data?.nextCursor && <button className="button-secondary" onClick={() => setCursor(query.data!.nextCursor)}>Older searches</button>}</div>
    {remove && <ConfirmDialog title={remove === 'all' ? 'Clear search history?' : 'Remove this search?'} confirmText="Remove" onCancel={() => setRemove(null)} onConfirm={() => void erase()} busy={busy}><p>This removes the saved question and answer. Any included running search will be cancelled.</p>{error && <p role="alert">{error}</p>}</ConfirmDialog>}
  </section>
}
export function AskKnowledgePage({ auth }: { auth: AuthRuntime }) {
  const [ready, setReady] = useState(!auth.ask.snapshot().historyId)
  useEffect(() => { const controller = new AbortController(); void auth.ask.revalidate(controller.signal).finally(() => { if (!controller.signal.aborted) setReady(true) }); return () => controller.abort() }, [auth])
  const state = useSyncExternalStore(auth.ask.subscribe, auth.ask.snapshot)
  const { input, result, operation, busy, error, serial } = state
  let dateError = ''; try { savedPeriod(state.period) } catch (e) { dateError = e instanceof Error ? e.message : 'Choose valid dates.' }
  useEffect(() => { void auth.queries.invalidateQueries({ queryKey: ['knowledge', auth.session.viewerScope, 'history'] }) }, [auth, state.historyId, operation?.status])
  const policy = useQuery({ queryKey: ['knowledge', auth.session.viewerScope, 'policy'], queryFn: ({ signal }) => processingPolicyApi.read(auth, signal), retry: false }, auth.queries)
  return <NotesWorkspace auth={auth}><div className="studio-ask"><header className="notes-heading"><h1>AI Search</h1></header>
    {(policy.isError || (policy.data && !policy.data.acknowledged)) && <aside className="studio-readiness" aria-label="AI processing prerequisites"><p>{policy.isError ? 'AI setup is unavailable.' : 'Review the processing disclosure to use AI.'}</p><Link to="/settings/privacy">AI &amp; Privacy</Link></aside>}
    <form className="studio-ask-form" onSubmit={event => { event.preventDefault(); void auth.ask.submit() }}><label htmlFor="knowledge-question">What would you like to find?</label>
      <textarea id="knowledge-question" maxLength={2048} required disabled={busy} value={input} onChange={e => auth.ask.input(e.target.value)} placeholder="Ask about your notes and media…" />
      <div className="search-period-controls"><label htmlFor="search-period">Saved during</label><select id="search-period" disabled={busy} value={state.period.preset} onChange={e => auth.ask.period({ ...state.period, preset: e.target.value as DateFilter['preset'] })}>
        <option value="any">Any time</option><option value="7">Last 7 days</option><option value="30">Last 30 days</option><option value="custom">Custom dates</option></select>
        {state.period.preset === 'custom' && <><label htmlFor="search-from">From</label><input id="search-from" type="date" required disabled={busy} value={state.period.from} aria-describedby="search-date-help" onChange={e => auth.ask.period({ ...state.period, from: e.target.value })} />
          <label htmlFor="search-to">Through</label><input id="search-to" type="date" required disabled={busy} value={state.period.to} aria-describedby="search-date-help" onChange={e => auth.ask.period({ ...state.period, to: e.target.value })} /></>}
      </div>{state.period.preset !== 'any' && <p id="search-date-help" className="notes-help">Note creation or file upload date · your local timezone · both selected days included</p>}
      {dateError && <p role="status">{dateError}</p>}
      <div className="actions"><button disabled={!input.trim() || busy || !!dateError}>{busy ? 'Submitting…' : 'Ask your knowledge'}</button><button className="button-secondary" type="button" disabled={busy} onClick={() => auth.ask.clear()}>New search</button></div></form>
    {error && <p role="alert" className="alert">{error}</p>}
    {(operation || result) && <p aria-label="Search date boundary">Saved during: {periodLabel(state.submittedDuring)}</p>}
    {operation && <OperationProgress key={serial} auth={auth} initial={operation} serial={serial} />}
    {state.historyId && <p className="notes-help">Saved search · results reflect the saved search boundary. Opening history rechecks source eligibility.</p>}
    {!ready && <p role="status">Checking saved search…</p>}
    {ready && result && <KnowledgeResults result={result} />}
    {result && state.submittedDuring && (result.insufficientEvidence || !result.aiAnswer && result.deterministicResults.length === 0) && <button type="button" className="button-secondary" onClick={() => auth.ask.period({ preset: 'any', from: '', to: '' })}>Use Any time for next search</button>}
    <SearchHistoryPanel auth={auth} serial={serial + (operation?.status === 'completed' ? 1 : 0)} />
  </div></NotesWorkspace>
}
