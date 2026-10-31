import { useRef, useState, type MouseEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { NotesWorkspace } from '../notes/NotesWorkspace'
import { notesApi } from '../notes/NotesApi'
import { notePreferenceKeys, noteKeys } from '../notes/NotesKeys'
import { processingPolicyApi, processingCategories } from './ProcessingPolicyApi'
import { ApiProblemError } from '../../app/api/ProblemDetailsDecoder'
import { ConfirmDialog } from '../../app/ui/ConfirmDialog'
import { featureError } from '../../app/ui/featureError'
import { usePrivateScope } from '../../app/security/usePrivateScope'

export function PrivacySettingsPage({ auth }: { auth: AuthRuntime }) {
  const scope = auth.session.viewerScope, current = usePrivateScope(auth)
  const preference = useQuery({ queryKey: notePreferenceKeys.current(scope), queryFn: () => notesApi.preference(auth), retry: false }, auth.queries)
  const policyKey = ['knowledge', scope, 'policy'] as const
  const policy = useQuery({ queryKey: policyKey, queryFn: ({ signal }) => processingPolicyApi.read(auth, signal), retry: false }, auth.queries)
  const [confirmation, setConfirmation] = useState<boolean | null>(null)
  const opener = useRef<HTMLElement | null>(null)
  function confirm(event: MouseEvent<HTMLButtonElement>, value: boolean) { opener.current = event.currentTarget; setConfirmation(value) }
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [status, setStatus] = useState('')
  async function run<T,>(action: () => Promise<T>, success: string | ((result: T) => string)) {
    if (busy) return
    setBusy(true); setError(''); setStatus('')
    try { const result = await action(); if (current()) setStatus(typeof success === 'function' ? success(result) : success) }
    catch (e) { if (current()) setError(featureError(e)) }
    finally { if (current()) { setBusy(false); setConfirmation(null) } }
  }
  async function setPreference(value: boolean) {
    const saved = await notesApi.setPreference(auth, value)
    if (current()) auth.queries.setQueryData(notePreferenceKeys.current(scope), saved)
  }
  async function acknowledge() {
    const displayed = policy.data
    if (busy || !displayed || policy.isFetching || displayed.acknowledged) return
    setBusy(true); setError(''); setStatus('')
    try {
      await processingPolicyApi.acknowledge(auth, displayed)
      if (current()) { await policy.refetch(); setStatus('Disclosure acknowledged. No Note AI choice was changed.') }
    } catch (e) {
      if (current()) {
        if (e instanceof ApiProblemError && e.problem.code === 'processing_policy_changed') {
          await policy.refetch(); setError('The processing policy changed. Review the current disclosure and acknowledge it deliberately.')
        } else setError(featureError(e))
      }
    } finally { if (current()) setBusy(false) }
  }
  return <NotesWorkspace auth={auth} modalOpen={confirmation !== null}><div className="settings-card" inert={confirmation !== null}>
    <h1>AI &amp; Privacy</h1><p>Writing and ordinary search work with AI off.</p>
    <section className="studio-setting"><h2>Default AI access for new notes</h2><p>New notes start with this choice. Existing notes are unchanged.</p>
      {preference.isPending ? <p role="status">Loading preference…</p> : preference.isError ? <p role="alert">{featureError(preference.error)} <button onClick={() => void preference.refetch()}>Retry preference</button></p>
        : <label><input type="checkbox" checked={preference.data} disabled={busy} onChange={e => void run(() => setPreference(e.target.checked), 'Future-note preference saved. Existing notes were not changed.')} />Default AI access for new notes</label>}</section>
    <section className="studio-setting"><h2>Processing disclosure</h2>
      <p>AI can send saved content to an external provider. Review its disclosure below.</p>
      <p>Turning AI on allows processing once all checks pass. Accepting this disclosure does not turn AI on. AI off excludes a note from AI features; it does not encrypt it.</p>
      {policy.isPending ? <p role="status">Loading current disclosure…</p> : policy.isError ? <p role="alert">{featureError(policy.error)} <button onClick={() => void policy.refetch()}>Retry disclosure</button></p> : policy.data && <>
        <p>Disclosure revision: {policy.data.disclosureRevision} · Policy version {policy.data.policyVersion}</p>
        <div aria-label="Current approved processing disclosure" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{policy.data.disclosureContent}</div>
        <h3>Processing covered by this disclosure</h3><ul>{policy.data.providerProcessingCategories.map(category => <li key={category}>{processingCategories[category]}</li>)}</ul>
        {policy.data.acknowledged ? <p role="status">Current disclosure acknowledged.</p>
          : <button disabled={busy || policy.isFetching} onClick={() => void acknowledge()}>I acknowledge this processing disclosure</button>}</>}
    </section><section className="studio-setting"><h2>Change existing notes</h2><p>Apply a choice to existing notes. Your new-note default stays unchanged.</p>
      <div className="actions"><button className="button-secondary" disabled={busy} onClick={event => confirm(event, true)}>Enable AI on existing notes</button>
        <button className="button-secondary" disabled={busy} onClick={event => confirm(event, false)}>Disable AI on existing notes</button></div></section>
    {error && <p role="alert" className="alert">{error}</p>}{status && <p role="status" className="success">{status}</p>}
  </div>{confirmation !== null && <ConfirmDialog opener={opener.current} title={`${confirmation ? 'Enable' : 'Disable'} AI on existing notes?`} confirmText="Confirm existing-note change" busy={busy} onCancel={() => setConfirmation(null)}
    onConfirm={() => void run(async () => {
      const affected = await processingPolicyApi.bulk(auth, confirmation)
      if (current()) { auth.queries.removeQueries({ queryKey: noteKeys.lists(scope) }); await auth.queries.invalidateQueries({ queryKey: ['notes', scope, 'core'] }) }
      return affected
    }, affected => `${affected} existing note${affected === 1 ? '' : 's'} updated. Future-note default is unchanged.`)}><p>This changes the per-note AI choice across your existing eligible notes. It does not save any draft or bypass processing prerequisites.</p></ConfirmDialog>}</NotesWorkspace>
}
