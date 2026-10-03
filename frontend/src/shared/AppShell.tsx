import { useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { Icon } from './Icon'

export function AppShell({ children, modalOpen = false, onLogout }: {
  children: ReactNode; modalOpen?: boolean; onLogout?: () => Promise<void>
}) {
  const location = useLocation(), navigate = useNavigate()
  const [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const writing = location.pathname.startsWith('/notes/')
  const notes = location.pathname.startsWith('/notes')
  async function logout() {
    if (!onLogout) return
    setBusy(true); setError('')
    try { await onLogout(); navigate('/', { replace: true }) }
    catch { setError('Could not sign out. Try again.'); setBusy(false) }
  }
  return <div className={`app-shell${writing ? ' app-writing' : ''}`}>
    <a className="skip-link" href="#notes-content" inert={modalOpen}>Skip to Notes content</a>
    <aside className="app-navigation" aria-label="Workspace" inert={modalOpen}>
      <Link className="app-wordmark" to="/notes"><span aria-hidden="true">n.</span><span>Notes &amp;<br />Knowledge</span></Link>
      <nav aria-label="Workspace navigation"><Link to="/notes" aria-current={notes ? 'page' : undefined}><Icon name="notes" /> All notes</Link>
        <Link to="/settings/security" aria-current={!notes ? 'page' : undefined}><Icon name="security" /> Security settings</Link></nav>
      <div className="app-account">{onLogout && <button type="button" disabled={busy} onClick={() => void logout()}>Log out <Icon name="logout" /></button>}{error && <p role="alert">{error}</p>}</div>
    </aside>
    <main id="notes-content" className="app-main" tabIndex={-1}>{children}
      {!notes && onLogout && <div className="mobile-account" inert={modalOpen}>
        <button type="button" className="text-button" disabled={busy} onClick={() => void logout()}>Log out</button>
        {error && <p role="alert">{error}</p>}
      </div>}
    </main>
    {!writing && <nav className="mobile-navigation" aria-label="Application" inert={modalOpen}>
      <Link to="/notes" aria-current={notes ? 'page' : undefined}>Notes</Link><Link className="mobile-create" to="/notes/new">+ New note</Link><Link to="/settings/security" aria-current={!notes ? 'page' : undefined}>Settings</Link>
    </nav>}
  </div>
}
