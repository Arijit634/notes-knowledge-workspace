import type { ReactNode } from 'react'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { AppShell } from '../../shared/AppShell'
import './NotesWorkspace.css'

/** Presentation only: authority and editor state remain with the route. */
export function NotesWorkspace({ children, auth, modalOpen = false }: { children: ReactNode; auth: AuthRuntime; modalOpen?: boolean }) {
  return <AppShell modalOpen={modalOpen} onLogout={() => auth.logout()}>{children}</AppShell>
}
