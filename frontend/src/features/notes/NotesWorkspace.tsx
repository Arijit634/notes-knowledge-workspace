import { useRef, useState, type ReactNode, type MouseEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { Icon } from '../../app/ui/Icon'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { ConfirmDialog } from '../../app/ui/ConfirmDialog'
import { featureError } from '../../app/ui/featureError'
import './NotesWorkspace.css'

/** Presentation only: authority and editor state remain with the route. */
export function NotesWorkspace({ children, modalOpen = false, auth }: { children: ReactNode; modalOpen?: boolean; auth?: AuthRuntime }) {
  const location = useLocation(), navigate = useNavigate()
  const [logoutOpen, setLogoutOpen] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const blocked = modalOpen || logoutOpen
  const opener = useRef<HTMLElement | null>(null)
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
        <p className="notes-nav-label">Workspace</p>
        <nav aria-label="Workspace navigation">
          <Link to="/notes" aria-current={location.pathname.startsWith('/notes') ? 'page' : undefined}><Icon name="notes" />All notes</Link>
          <Link to="/ask" aria-current={location.pathname === '/ask' ? 'page' : undefined}><Icon name="ask" />Search &amp; Ask</Link>
          <Link to="/publications" aria-current={location.pathname.startsWith('/publications') ? 'page' : undefined}><Icon name="notes" />Publications</Link>
          <Link to="/explore"><Icon name="search" />Explore</Link>
          <Link to="/settings/profile" aria-current={location.pathname === '/settings/profile' ? 'page' : undefined}><Icon name="profile" />Profile</Link>
          <Link to="/settings/privacy" aria-current={location.pathname === '/settings/privacy' ? 'page' : undefined}><Icon name="settings" />AI &amp; Privacy</Link>
          <Link to="/settings/security" aria-current={location.pathname.startsWith('/settings/security') ? 'page' : undefined}><Icon name="shield" />Security settings</Link>
          <details className="workspace-more"><summary><Icon name="settings" />More</summary><div>
            <Link to="/settings/profile">Profile</Link><Link to="/settings/public-profile">Public profile</Link><Link to="/settings/privacy">AI &amp; Privacy</Link><Link to="/settings/security">Security settings</Link>
          </div></details>
        </nav>
        <Link to="/notes/new" className="studio-new-note"><Icon name="plus" />New note</Link>
        <p className="notes-sidebar-foot">Your private workspace</p>
        {auth && <button type="button" className="studio-logout" onClick={openLogout}>Log out</button>}
      </aside>
      <main id="notes-content" className="notes-content" tabIndex={-1} inert={logoutOpen}>{children}
        {auth && location.pathname.startsWith('/settings') && <button type="button" className="button-secondary studio-mobile-logout" aria-label="Sign out of account" onClick={openLogout}>Log out</button>}
      </main>
    </div>
    {logoutOpen && <ConfirmDialog opener={opener.current} title="Log out of this workspace?" busy={busy} onCancel={() => setLogoutOpen(false)} onConfirm={() => void logout()} confirmText="Log out">
      <p>Unsaved drafts in this tab will be discarded. Saved notes remain in your account.</p>{error && <p role="alert">{error}</p>}</ConfirmDialog>}
  </div>
}
