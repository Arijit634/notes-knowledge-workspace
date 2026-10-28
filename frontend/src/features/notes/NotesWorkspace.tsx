import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode, type MouseEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { profileApi } from '../profile/ProfileApi'
import { Link, useLocation, useNavigate } from 'react-router'
import { Icon } from '../../app/ui/Icon'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { ConfirmDialog } from '../../app/ui/ConfirmDialog'
import { featureError } from '../../app/ui/featureError'
import './NotesWorkspace.css'

function AccountMenu({ auth, onLogout }: { auth: AuthRuntime; onLogout: (event: MouseEvent<HTMLButtonElement>) => void }) {
  const profile = useQuery({ queryKey: ['profile', auth.session.viewerScope, 'private'], queryFn: ({ signal }) => profileApi.read(auth, signal), retry: false, staleTime: 60000 }, auth.queries)
  const [avatarFailed, setAvatarFailed] = useState(false)
  useEffect(() => setAvatarFailed(false), [profile.dataUpdatedAt])
  return <details className="workspace-account" onKeyDown={e => { if (e.key === 'Escape') { e.currentTarget.open = false; e.currentTarget.querySelector('summary')?.focus({ preventScroll: true }) } }}><summary aria-label="Account menu">
    {profile.data?.avatar && !avatarFailed ? <span className="studio-avatar-symbol"><img key={`${profile.data.avatar.createdAt}:${profile.data.updatedAt}`} src="/api/me/profile/avatar/content" alt="Account avatar" onError={() => setAvatarFailed(true)} style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 'inherit' }} /></span> : <span className="workspace-account-avatar" aria-hidden="true">{profile.data?.displayName?.slice(0, 1).toUpperCase() || <Icon name="profile" />}</span>}
    <span className="workspace-account-name"><strong>{profile.data?.displayName || 'Account'}</strong><small>Account settings</small></span><span className="workspace-account-chevron" aria-hidden="true">⌃</span>
    </summary><div className="workspace-account-menu"><Link to="/settings/profile">Profile</Link><Link to="/settings/public-profile">Public profile</Link><Link to="/settings/privacy">AI &amp; Privacy</Link><Link to="/settings/security">Security settings</Link><button type="button" onClick={onLogout}>Log out</button></div></details>
}
function LibrarySearchBar({ auth }: { auth: AuthRuntime }) {
  const navigate = useNavigate(), state = useSyncExternalStore(auth.librarySearch.subscribe, auth.librarySearch.snapshot)
  return <form className="workspace-search" role="search" onSubmit={e => { e.preventDefault(); auth.librarySearch.submit(); navigate('/notes') }}><label className="visually-hidden" htmlFor="workspace-search">Search notes</label><input id="workspace-search" type="search" placeholder="Search notes…" maxLength={256} value={state.input} onChange={e => { auth.librarySearch.input(e.target.value); if (!e.target.value) auth.librarySearch.clear() }} /><button className="workspace-search-submit" type="submit" aria-label="Search notes"><Icon name="search" /></button></form>
}

/** Presentation only: authority and editor state remain with the route. */
export function NotesWorkspace({ children, modalOpen = false, auth }: { children: ReactNode; modalOpen?: boolean; auth?: AuthRuntime }) {
  const location = useLocation(), navigate = useNavigate()
  const [logoutOpen, setLogoutOpen] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const blocked = modalOpen || logoutOpen
  const opener = useRef<HTMLElement | null>(null)
  const section = location.pathname.startsWith('/notes') ? 'Notes' : location.pathname.startsWith('/settings') ? 'Settings' : location.pathname.startsWith('/publications') ? 'Publishing' : location.pathname === '/ask' ? 'AI Search' : location.pathname.startsWith('/moderation') ? 'Public safety' : 'Workspace'
  function openLogout(event: MouseEvent<HTMLButtonElement>) { opener.current = event.currentTarget; setLogoutOpen(true) }
  async function logout() {
    if (!auth || busy) return
    setBusy(true); setError('')
    try { await auth.logout(); navigate('/login', { replace: true }) } catch (e) { setError(featureError(e)); setBusy(false) }
  }
  return <div className="notes-layout">
    <a className="notes-skip" href="#notes-content" inert={blocked}>Skip to Notes content</a>
    <div className="notes-shell">
      <aside className="notes-sidebar" aria-label="Workspace" inert={blocked}>
        <Link className="notes-brand" to="/"><span aria-hidden="true" className="notes-brand-mark">n.</span>
          <span>Notes &amp;<br />{' '}Knowledge</span></Link>
        <Link to="/notes/new" state={{ from: location.pathname }} className="studio-new-note"><Icon name="plus" />New note</Link>
        <p className="notes-nav-label">Workspace</p>
        <nav aria-label="Workspace navigation">
          <Link to="/notes" aria-current={location.pathname.startsWith('/notes') ? 'page' : undefined}><Icon name="notes" />All notes</Link>
          <Link to="/ask" aria-current={location.pathname === '/ask' ? 'page' : undefined}><Icon name="sparkle" />AI Search</Link>
          <Link to="/publications" aria-current={location.pathname.startsWith('/publications') ? 'page' : undefined}><Icon name="notes" />Publications</Link>
          <Link to="/explore" aria-current={location.pathname.startsWith('/explore') ? 'page' : undefined}><Icon name="search" />Explore</Link>
        </nav>
        {auth && <AccountMenu auth={auth} onLogout={openLogout} />}
      </aside>
      <div className="workspace-stage">
      <header className="studio-command-bar" inert={blocked}>{auth ? <LibrarySearchBar auth={auth} /> : <span>{section}</span>}<Link className="workspace-mobile-create" to="/notes/new" state={{ from: location.pathname }}><Icon name="plus" /><span>New note</span></Link></header>
      <main id="notes-content" className="notes-content" tabIndex={-1} inert={logoutOpen}>{children}

      </main>
      </div>
    </div>
    {logoutOpen && <ConfirmDialog opener={opener.current} title="Log out of this workspace?" busy={busy} onCancel={() => setLogoutOpen(false)} onConfirm={() => void logout()} confirmText="Log out">
      <p>Unsaved drafts in this tab will be discarded. Saved notes remain in your account.</p>{error && <p role="alert">{error}</p>}</ConfirmDialog>}
  </div>
}
