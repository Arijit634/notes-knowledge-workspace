import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router'
import './NotesWorkspace.css'

/** Presentation only: authority and editor state remain with the route. */
export function NotesWorkspace({ children, modalOpen = false }: { children: ReactNode; modalOpen?: boolean }) {
  const location = useLocation()
  return <div className="notes-layout">
    <a className="notes-skip" href="#notes-content" inert={modalOpen}>Skip to Notes content</a>
    <div className="notes-shell">
      <aside className="notes-sidebar" aria-label="Workspace" inert={modalOpen}>
        <Link className="notes-brand" to="/"><span aria-hidden="true" className="notes-brand-mark">n.</span>
          <span>Notes &amp;<br />{' '}Knowledge</span></Link>
        <p className="notes-nav-label">Workspace</p>
        <nav aria-label="Workspace navigation">
          <Link className="notes-nav-current" to="/notes" aria-current={location.pathname === '/notes' ? 'page' : undefined}>All notes</Link>
          <Link to="/settings/security">Security settings</Link>
        </nav>
        <p className="notes-sidebar-foot">A little space for<br />what matters to you.</p>
      </aside>
      <main id="notes-content" className="notes-content" tabIndex={-1}>{children}</main>
    </div>
  </div>
}
