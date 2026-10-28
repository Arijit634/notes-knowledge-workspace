import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { NotesWorkspace } from '../notes/NotesWorkspace'
import { profileApi, type PrivateProfile } from './ProfileApi'
import { Icon } from '../../app/ui/Icon'
import { featureError } from '../../app/ui/featureError'
import { usePrivateScope } from '../../app/security/usePrivateScope'
import { Link } from 'react-router'
import { PrivateAvatar } from './PrivateAvatar'

export function ProfileSettingsPage({ auth }: { auth: AuthRuntime }) {
  const key = ['profile', auth.session.viewerScope, 'private'] as const
  const profile = useQuery({ queryKey: key, queryFn: ({ signal }) => profileApi.read(auth, signal), retry: false }, auth.queries)
  const current = usePrivateScope(auth), initialized = useRef(false)
  const { register, handleSubmit, reset } = useForm<Pick<PrivateProfile, 'displayName' | 'biography' | 'handle'>>()
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [status, setStatus] = useState('')
  const [avatarEpoch, setAvatarEpoch] = useState(0)
  const controller = useRef<AbortController | null>(null), fileInput = useRef<HTMLInputElement>(null)
  useEffect(() => () => controller.current?.abort(), [])
  useEffect(() => { if (profile.data && !initialized.current) { initialized.current = true; reset(profile.data) } }, [profile.data, reset])
  const save = handleSubmit(async draft => {
    if (busy) return
    setBusy(true); setError(''); setStatus('')
    try { const p = await profileApi.save(auth, { displayName: draft.displayName, biography: draft.biography, handle: draft.handle || null });
      if (current()) { auth.queries.setQueryData(key, p); reset(p); setStatus('Profile saved. Nothing was published.') } }
    catch (e) { if (current()) setError(featureError(e)) } finally { if (current()) setBusy(false) }
  })
  async function avatar(file: File | undefined) {
    if (!file || busy) return
    if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 5 * 1024 * 1024 || !file.size) { setError('Choose a PNG or JPEG up to 5 MiB.'); return }
    setBusy(true); setError(''); setStatus(''); const request = new AbortController(); controller.current = request
    try { const p = await profileApi.avatar(auth, file, request.signal); if (current() && !request.signal.aborted) { auth.queries.setQueryData(key, p); setAvatarEpoch(value => value + 1); setStatus('Avatar updated.'); } }
    catch (e) { if (current() && !request.signal.aborted) setError(featureError(e)) }
    finally { if (current() && !request.signal.aborted) setBusy(false); if (fileInput.current) fileInput.current.value = '' }
  }
  async function removeAvatar() {
    setBusy(true); setError(''); setStatus('')
    try { await profileApi.removeAvatar(auth); if (current()) { auth.queries.setQueryData(key, (previous: PrivateProfile | undefined) => previous && { ...previous, avatar: null }); setAvatarEpoch(value => value + 1); await profile.refetch(); setStatus('Avatar removed.') } }
    catch (e) { if (current()) setError(featureError(e)) } finally { if (current()) setBusy(false) }
  }
  return <NotesWorkspace auth={auth}><div className="settings-card"><h1>Your profile</h1><Link to="/settings/public-profile">Public profile</Link>
    {profile.isPending ? <p role="status">Loading profile…</p> : profile.isError ? <p role="alert">{featureError(profile.error)} <button onClick={() => void profile.refetch()}>Retry</button></p> : profile.data && <>
      <div className="studio-profile-avatar">{profile.data.avatar ? <PrivateAvatar key={`${avatarEpoch}:${profile.data.avatar.createdAt}`} auth={auth} /> : <span className="studio-avatar-symbol"><Icon name="profile" /></span>}<div>
        <strong>{profile.data.avatar ? 'Avatar is configured' : 'No avatar yet'}</strong><p>PNG or JPEG · validated by the server</p>
        <label htmlFor="profile-avatar">Choose avatar</label><input ref={fileInput} id="profile-avatar" type="file" accept="image/png,image/jpeg" disabled={busy} onChange={e => void avatar(e.target.files?.[0])} />
        {profile.data.avatar && <button type="button" disabled={busy} className="button-secondary" onClick={() => void removeAvatar()}>Remove avatar</button>}</div></div>
      <form className="settings-form" onSubmit={event => void save(event)}><label htmlFor="display-name">Display name</label>
        <input id="display-name" maxLength={100} disabled={busy} {...register('displayName')} />
        <label htmlFor="biography">About you</label><textarea id="biography" maxLength={500} disabled={busy} {...register('biography')} />
        <label htmlFor="profile-handle">Public handle (optional)</label><input id="profile-handle" maxLength={30} disabled={busy} pattern="[a-zA-Z][a-zA-Z0-9_]{2,29}" {...register('handle')} />
        <p className="notes-help">Reserving a handle does not activate a public profile or publish any note.</p><button disabled={busy}>Save profile</button></form></>}
    {error && <p role="alert" className="alert">{error}</p>}{status && <p role="status" className="success">{status}</p>}</div></NotesWorkspace>
}
