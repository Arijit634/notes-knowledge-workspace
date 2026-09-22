import type { LocalSessionState } from './SessionCoordinator'

export type RouteAccess = 'PUBLIC' | 'ANONYMOUS_ONLY' | 'MFA_CONTINUATION' | 'FULL_AUTHENTICATED'

/** UI gating only; the backend must authorize every operation independently. */
export function canEnterRoute(state: LocalSessionState, access: RouteAccess): boolean {
  switch (access) {
    case 'PUBLIC': return true
    case 'ANONYMOUS_ONLY': return state === 'anonymous'
    case 'MFA_CONTINUATION': return state === 'mfaRequired'
    case 'FULL_AUTHENTICATED': return state === 'authenticated'
  }
}
