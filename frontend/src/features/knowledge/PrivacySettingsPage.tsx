import { useRef, useState, type MouseEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { NotesWorkspace } from '../notes/NotesWorkspace'
import { notesApi } from '../notes/NotesApi'
import { notePreferenceKeys, noteKeys } from '../notes/NotesKeys'
import { processingPolicyApi } from './ProcessingPolicyApi'
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
  return <NotesWorkspace auth={auth} modalOpen={confirmation !== null}><div className="settings-card" inert={confirmation !== null}>
    <h1>AI &amp; Privacy</h1><p>Your notes, your choice. Ordinary writing and search work with AI off.</p>
    <section className="studio-setting"><h2>Default AI access for new notes</h2><p>This only initializes the choice when creating a note. Existing notes keep their own AI state.</p>
      {preference.isPending ? <p role="status">Loading preference…</p> : preference.isError ? <p role="alert">{featureError(preference.error)} <button onClick={() => void preference.refetch()}>Retry preference</button></p>
        : <label><input type="checkbox" checked={preference.data} disabled={busy} onChange={e => void run(() => setPreference(e.target.checked), 'Future-note preference saved. Existing notes were not changed.')} />Default AI access for new notes</label>}</section>
    <section className="studio-setting"><h2>Processing disclosure</h2>
      <p>AI processing can send eligible saved content to an external provider. The following is an explanation of the controls, not the current provider’s processing disclosure.</p>
      <p>AI ON is permission to participate, not proof that processing has happened. Acknowledging this disclosure does not turn any note ON. AI OFF excludes a note from AI processing; it is not encryption and does not change who can see it.</p>
      {policy.isPending ? <p role="status">Loading current disclosure…</p> : policy.isError ? <p role="alert">{featureError(policy.error)} <button onClick={() => void policy.refetch()}>Retry disclosure</button></p> : policy.data && <>
        <p>Disclosure revision: {policy.data.disclosureRevision} · Policy version {policy.data.policyVersion}</p>
        {policy.data.acknowledged ? <p role="status">The server records acknowledgement of the current policy.</p> : <p className="alert" role="status">The server has not supplied the current disclosure text or provider-processing summary. Acknowledgement is unavailable until that information can be displayed. Your notes remain usable, and AI ON does not bypass this requirement.</p>}</>}
    </section><section className="studio-setting"><h2>Change existing notes</h2><p>A separate explicit action changes AI access on existing notes. It does not change the default above.</p>
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
