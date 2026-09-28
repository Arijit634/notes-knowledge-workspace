import type { AuthContinuationState } from './AuthContinuationState'

/** Capture first, scrub synchronously, then let React or diagnostics render. */
export function captureSecurityLink(location: Location, history: History, state: AuthContinuationState): void {
  if (location.pathname !== '/verify-email' && location.pathname !== '/reset-password'
      && location.pathname !== '/settings/security') return
  const fragment = location.hash
  if (!fragment) return
  history.replaceState(null, '', location.pathname)
  const encoded = fragment.startsWith('#token=') ? fragment.slice('#token='.length) : fragment.slice(1)
  let token: string
  try { token = decodeURIComponent(encoded) } catch { return }
  if (!/^[A-Za-z0-9._~-]{1,128}$/.test(token)) return
  if (location.pathname === '/verify-email') state.verificationToken = token
  else if (location.pathname === '/reset-password') state.resetToken = token
  else state.emailChangeToken = token
}
