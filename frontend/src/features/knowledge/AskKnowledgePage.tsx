import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { NotesWorkspace } from '../notes/NotesWorkspace'
import { PrivateSearchPanel } from '../notes/PrivateSearchPanel'
import { featureError } from '../../app/ui/featureError'
import { usePrivateScope } from '../../app/security/usePrivateScope'
import { activeOperation, knowledgeApi, type Citation, type KnowledgeResult, type Operation } from './KnowledgeApi'

export function CitationList({ citations }: { citations: Citation[] }) {
  return <ol className="studio-citations">{citations.map((c, index) => <li key={`${c.noteId}:${c.attachmentId}:${index}`}>
    <Link to={`/notes/${c.noteId}`} state={{ attachmentId: c.attachmentId, sourceLocation: c.location }}>Source {index + 1} · {c.attachmentId ? c.location.kind.replaceAll('_', ' ') : 'Note'}{c.location.page ? ` · page ${c.location.page}` : ''}{c.location.timeStart !== null ? ` · ${Math.floor(c.location.timeStart)}s` : ''}</Link>
  </li>)}</ol>
}
export function KnowledgeResults({ result }: { result: KnowledgeResult }) {
  const c = result.coverage
  return <section aria-label="Knowledge results">
    {result.degraded && <p className="alert" role="status">AI is degraded or unavailable for this request. These results may be partial; ordinary search is still available.</p>}
    {result.insufficientEvidence && <p role="status">There is not enough eligible evidence to answer reliably. Try a more specific question or review saved sources.</p>}
    {result.aiAnswer && <section className="studio-result" aria-label="AI answer"><h2>From your knowledge</h2>
      {result.aiAnswer.conflicting && <p className="alert">Sources disagree. Review the evidence before relying on this answer.</p>}
      {result.aiAnswer.claims.map((claim, index) => <p key={index}>{claim}</p>)}
      <h3>Evidence</h3><CitationList citations={result.citations} /></section>}
    <section className="studio-result" aria-label="Deterministic results"><h2>Saved facts &amp; matches</h2>
      <p className="notes-help">Separate from the AI answer. AI-OFF notes can appear here without being model evidence.</p>
      {result.deterministicResults.length === 0 ? <p>No deterministic matches in this result.</p> : result.deterministicResults.map((item, index) =>
        <div key={index} className="studio-evidence"><strong>{item.kind.replaceAll('_', ' ')}</strong><p>{item.value}</p>
          {!item.aiEnabled && <span className="notes-pill">AI OFF · deterministic only</span>}<CitationList citations={item.occurrences} /></div>)}</section>
    <section className="studio-coverage" aria-label="Coverage"><h2>Coverage</h2><p>{c.inspectedSources} source{c.inspectedSources === 1 ? '' : 's'} inspected · {c.boundary === 'acceptedAiSources' ? 'AI-eligible sources' : c.boundary === 'acceptedNoteAndPdfText' ? 'Saved Note and PDF text' : 'Bounded retrieval'}</p>
      <p>{c.completed && !c.truncated && !c.corpusChanged ? 'Completed within the reported search boundary.' : 'Partial coverage — this is not a claim to have searched every source.'}</p>
      {c.corpusChanged && <p>Saved content changed during this request.</p>}{c.truncated && <p>A processing limit was reached.</p>}
      {c.retryRequired && <p>Submit a new request to search the current saved state.</p>}{c.classificationUncertain && <p>Semantic classification may be uncertain. Check the sources.</p>}</section>
  </section>
}
function OperationProgress({ auth, initial, onResult, onTerminal }: { auth: AuthRuntime; initial: Operation; onResult: (result: KnowledgeResult) => void; onTerminal: () => void }) {
  const current = usePrivateScope(auth), [visible, setVisible] = useState(!document.hidden && navigator.onLine)
  const [cancelBusy, setCancelBusy] = useState(false), [error, setError] = useState(''), [cancelled, setCancelled] = useState(false)
  const key = ['knowledge', auth.session.viewerScope, 'operation', initial.operationId] as const
  useEffect(() => { const update = () => setVisible(!document.hidden && navigator.onLine)
    document.addEventListener('visibilitychange', update); window.addEventListener('online', update); window.addEventListener('offline', update)
    return () => { document.removeEventListener('visibilitychange', update); window.removeEventListener('online', update); window.removeEventListener('offline', update) } }, [])
  const query = useQuery({ queryKey: key, initialData: initial, queryFn: ({ signal }) => knowledgeApi.poll(auth, initial.operationId, signal),
    enabled: q => visible && !cancelled && (!q.state.data || activeOperation(q.state.data)), gcTime: 0, retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false,
    refetchInterval: q => q.state.error || !visible || !q.state.data || !activeOperation(q.state.data) || q.state.dataUpdateCount >= 60 ? false : 2000 }, auth.queries)
  useEffect(() => { if (query.data && !activeOperation(query.data)) { if (query.data.status === 'completed' && query.data.result) onResult(query.data.result); onTerminal() } }, [query.data, onResult, onTerminal])
  async function cancel() {
    if (cancelBusy) return
    setCancelBusy(true); setError('')
    try { await knowledgeApi.cancel(auth, initial.operationId); if (current()) { setCancelled(true); onTerminal(); await auth.queries.cancelQueries({ queryKey: key }); auth.queries.removeQueries({ queryKey: key }) } }
    catch (e) { if (current()) setError(featureError(e)) } finally { if (current()) setCancelBusy(false) }
  }
  return <section className="studio-result" aria-label="Knowledge operation"><h2>Background request</h2><p role="status">{cancelled ? 'Request cancelled.' : query.data?.status === 'completed' ? 'Request completed.' : query.data?.status === 'obsolete' ? 'This request is no longer current. Submit again if needed.' : query.data?.status === 'failed' ? 'The request could not finish.' : query.data?.status === 'cancelled' ? 'Request cancelled.' : 'Your request is being processed.'}</p>
    {!visible && <p>Status checks pause while this tab is hidden or offline.</p>}
    {(auth.queries.getQueryState(key)?.dataUpdateCount ?? 0) >= 60 && activeOperation(query.data ?? initial) && <p>Automatic checks have paused. Check the status explicitly, or cancel this request.</p>}
    {query.isError && <p role="alert">{featureError(query.error)}</p>}{error && <p role="alert">{error}</p>}
    {!cancelled && activeOperation(query.data ?? initial) && <div className="actions"><button className="button-secondary" disabled={cancelBusy || query.isFetching} onClick={() => void query.refetch()}>Check status</button><button className="button-secondary" disabled={cancelBusy} onClick={() => void cancel()}>Cancel request</button></div>}
  </section>
}
export function AskKnowledgePage({ auth }: { auth: AuthRuntime }) {
  const current = usePrivateScope(auth), [mode, setMode] = useState<'ask' | 'search'>('ask')
  const [input, setInput] = useState(''), [result, setResult] = useState<KnowledgeResult | null>(null), [operation, setOperation] = useState<Operation | null>(null)
  const [busy, setBusy] = useState(false), [running, setRunning] = useState(false), [error, setError] = useState('')
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])
  async function submit(event: FormEvent) {
    event.preventDefault(); if (!input.trim() || busy || running) return
    const controller = new AbortController(); request.current = controller
    setBusy(true); setError(''); setResult(null); setOperation(null)
    try { const response = await knowledgeApi.query(auth, input.trim(), controller.signal)
      if (!current() || controller.signal.aborted) return
      if (response.kind === 'result') setResult(response.result)
      else { setOperation(response.operation); setRunning(activeOperation(response.operation)) }
    } catch (e) { if (current() && !controller.signal.aborted) setError(featureError(e)) }
    finally { if (current()) setBusy(false) }
  }
  return <NotesWorkspace auth={auth}><div className="studio-ask"><header className="notes-heading"><h1>Search &amp; Ask</h1></header><p className="notes-intro">Find a saved detail. Connect the dots. Keep the evidence close.</p>
    <div className="notes-views" role="group" aria-label="Search mode"><button aria-pressed={mode === 'ask'} onClick={() => setMode('ask')}>Ask your knowledge</button><button aria-pressed={mode === 'search'} onClick={() => setMode('search')}>Ordinary search</button></div>
    {mode === 'search' ? <PrivateSearchPanel auth={auth} /> : <><form className="studio-ask-form" onSubmit={event => void submit(event)}><label htmlFor="knowledge-question">What would you like to find?</label>
      <textarea id="knowledge-question" maxLength={2048} required value={input} onChange={e => setInput(e.target.value)} placeholder="Ask about something you’ve saved…" />
      <p className="notes-help">Uses saved content, not unsaved drafts. The server chooses the retrieval strategy. AI requires eligible sources and processing permission.</p>
      <div className="actions"><button disabled={!input.trim() || busy || running}>{busy ? 'Submitting…' : 'Ask your knowledge'}</button><button className="button-secondary" type="button" disabled={busy || running} onClick={() => { setInput(''); setResult(null); setOperation(null); setError('') }}>Clear question</button></div></form>
      {error && <p role="alert" className="alert">{error}</p>}
    </>}
    {operation && <OperationProgress auth={auth} initial={operation} onResult={setResult} onTerminal={() => setRunning(false)} />}
    {result && <KnowledgeResults result={result} />}
    {!result && !operation && !busy && !error && mode === 'ask' && <div className="notes-empty"><h2>Start with a question</h2><p>Answers stay connected to the notes and media that support them.</p>
      <div className="actions" aria-label="Example questions">{['Find my notes about project planning', 'List the URLs I saved', 'What did I write about my travel plans?'].map(example => <button type="button" className="button-secondary" key={example} onClick={() => setInput(example)}>{example}</button>)}</div>
      <Link to="/settings/privacy">Review AI &amp; Privacy</Link></div>}
  </div></NotesWorkspace>
}
