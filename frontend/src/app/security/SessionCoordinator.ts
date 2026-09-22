import { CsrfManager } from './CsrfManager'
import { SensitiveStateRegistry } from './SensitiveStateRegistry'
import {
  ANONYMOUS_VIEWER_SCOPE, authenticatedViewerScope, randomViewerEpoch,
  type ViewerCacheScope, type ViewerEpochFactory,
} from './ViewerCacheScope'

export type ServerSessionAuthority = 'anonymous' | 'mfaRequired' | 'authenticated'
export type LocalSessionState = 'unknown' | ServerSessionAuthority

/** Phase 3 will supply actual session bootstrap/refresh; this class never calls an auth API. */
export class SessionCoordinator {
  private current: LocalSessionState = 'unknown'
  private scope: ViewerCacheScope = ANONYMOUS_VIEWER_SCOPE

  constructor(
    private readonly csrf: CsrfManager,
    private readonly sensitive: SensitiveStateRegistry,
    private readonly clearViewerCaches: () => void,
    private readonly epochFactory: ViewerEpochFactory = randomViewerEpoch,
  ) {}

  get state(): LocalSessionState { return this.current }
  get viewerScope(): ViewerCacheScope { return this.scope }

  /** A full-session replacement rotates the viewer epoch even when authority stays full. */
  transition(next: ServerSessionAuthority, replaceAuthenticated = false): void {
    if (next !== 'anonymous' && next !== 'mfaRequired' && next !== 'authenticated') {
      throw new Error('Session state could not be established.')
    }
    if (next === this.current && !(next === 'authenticated' && replaceAuthenticated)) return

    this.csrf.clear()
    const wasAuthenticated = this.current === 'authenticated'
    const isAuthenticated = next === 'authenticated'
    const mustClear = wasAuthenticated || this.current === 'mfaRequired' || isAuthenticated
    if (mustClear) {
      // Drop authority first; a failed cleaner must not leave an authenticated cache scope.
      this.current = 'unknown'
      this.scope = ANONYMOUS_VIEWER_SCOPE
      let failure = false
      try { this.sensitive.clearAll() } catch { failure = true }
      try { this.clearViewerCaches() } catch { failure = true }
      if (failure) throw new Error('Session transition could not be completed safely.')
    }

    if (isAuthenticated) this.scope = authenticatedViewerScope(this.epochFactory)
    this.current = next
  }

  markIneligible(): void { this.transition('anonymous') }
}
