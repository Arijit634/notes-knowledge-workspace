import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useForm } from 'react-hook-form'
import { useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { NotesWorkspace } from '../notes/NotesWorkspace'
import { profileApi, type PrivateProfile } from './ProfileApi'
import { discoveryApi } from '../discovery/DiscoveryApi'
import { PublicAuthorView } from '../discovery/PublicUi'
import { authorChanged, publicationKeys } from '../publishing/PublicationCache'
import { ConfirmDialog } from '../../app/ui/ConfirmDialog'
import { featureError } from '../../app/ui/featureError'
import { ApiProblemError } from '../../app/api/ProblemDetailsDecoder'
import { usePrivateScope } from '../../app/security/usePrivateScope'

type Fields = Pick<PrivateProfile, 'displayName' | 'biography' | 'handle'>
export function PublicProfileSettingsPage({ auth }: { auth: AuthRuntime }) {
  const current = usePrivateScope(auth), initialized = useRef(false)
  const key = ['profile', auth.session.viewerScope, 'private'] as const
  const profile = useQuery({ queryKey: key, queryFn: ({ signal }) => profileApi.read(auth, signal), retry: false }, auth.queries)
  const handle = profile.data?.handle
  const projection = useQuery({ queryKey: publicationKeys.author(handle ?? ''), queryFn: ({ signal }) => discoveryApi.author(auth, handle!, signal), enabled: !!handle, retry: false }, auth.queries)
  const { register, reset, handleSubmit, formState: { errors, isDirty } } = useForm<Fields>()
  const [busy, setBusy] = useState(false), [confirm, setConfirm] = useState(false), [error, setError] = useState(''), [status, setStatus] = useState('')
  useEffect(() => { if (profile.data && !initialized.current) { initialized.current = true; reset(profile.data) } }, [profile.data, reset])
  const save = handleSubmit(async values => {
    if (busy) return
    setBusy(true); setError(''); setStatus('')
    try { const p = await profileApi.save(auth, { displayName: values.displayName, biography: values.biography, handle: values.handle || null }); if (current()) { auth.queries.setQueryData(key, p); reset(p); setStatus('Profile saved privately. Activate or refresh below to copy these fields publicly.') } }
    catch (e) { if (current()) setError(e instanceof ApiProblemError && e.problem.code === 'profile_handle_unavailable' ? 'That handle is unavailable. Choose another.' : featureError(e)) }
    finally { if (current()) setBusy(false) }
  })
  async function activate() {
    if (busy || isDirty || !handle) return
    setBusy(true); setError(''); setStatus('')
    try { const p = await discoveryApi.activate(auth); if (!current()) return
      authorChanged(auth); auth.queries.setQueryData(publicationKeys.author(p.handle), p)
      setStatus('Public profile is active and refreshed. No Note was published.'); setConfirm(false)
    } catch (e) { if (current()) { setConfirm(false); setError(e instanceof ApiProblemError && e.problem.code === 'profile_handle_unavailable' ? 'That handle is unavailable. Choose another.' : featureError(e)) } }
    finally { if (current()) setBusy(false) }
  }
  return <NotesWorkspace auth={auth} modalOpen={confirm}><div className="settings-card" inert={confirm}><p className="studio-section-label">Choose what to share</p><h1>Public profile</h1><p>A private account does not need a handle. Publishing requires an active public profile with a unique handle.</p>
    {profile.isPending ? <p role="status">Loading profile…</p> : profile.isError ? <p role="alert">{featureError(profile.error)} <button onClick={() => void profile.refetch()}>Retry</button></p> : <><form className="settings-form" onSubmit={save}><label htmlFor="public-display">Display name</label><input id="public-display" maxLength={100} disabled={busy} {...register('displayName', { required: 'Enter a display name.' })} />{errors.displayName && <p role="alert">{errors.displayName.message}</p>}
      <label htmlFor="public-bio">Biography</label><textarea id="public-bio" maxLength={500} disabled={busy} {...register('biography')} /><label htmlFor="public-handle">Public handle</label><input id="public-handle" minLength={3} maxLength={30} disabled={busy} aria-invalid={!!errors.handle} aria-describedby="public-handle-help" {...register('handle', { required: 'Choose a public handle.', pattern: { value: /^[a-zA-Z][a-zA-Z0-9_]{2,29}$/, message: 'Use 3–30 letters, digits or underscores, starting with a letter.' } })} /><p id="public-handle-help">3–30 letters, digits or underscores, starting with a letter. A handle is a public address, never your account identity.</p>{errors.handle && <p role="alert">{errors.handle.message}</p>}<button disabled={busy || !isDirty}>Save profile fields</button></form>
      <section className="public-activation"><h2>What people see</h2><p>Activation copies your saved display name, handle, biography and current validated avatar. Email, sessions and security details are never public. Later private edits do not update these fields automatically.</p><Link to="/settings/profile">Manage private avatar</Link><p>{profile.data?.avatar ? 'Your current validated avatar will be copied publicly.' : 'No avatar will be copied.'}</p>
        {projection.data && !projection.isError && <><PublicAuthorView key={projection.data.avatarUrl} author={projection.data} /><Link to={`/profile/${projection.data.handle}`}>View public profile</Link></>}
        <button disabled={busy || isDirty || !handle} onClick={() => setConfirm(true)}>{projection.data ? 'Refresh public profile' : 'Activate public profile'}</button>{isDirty && <p>Save the profile fields before activation.</p>}</section></>}
    {error && <p role="alert">{error}</p>}{status && <p role="status">{status}</p>}</div>
    {confirm && <ConfirmDialog title="Make these profile fields public?" onCancel={() => setConfirm(false)} onConfirm={() => void activate()} confirmText="Confirm public profile" busy={busy}><p>Your saved display name, @{handle}, biography and validated avatar become readable by anyone. This does not publish any Note.</p></ConfirmDialog>}
  </NotesWorkspace>
}
