import { QueryClient } from '@tanstack/react-query'
import { flushSync } from 'react-dom'
import { ApiClient } from '../../app/api/ApiClient'
import { ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'
import { CsrfManager } from '../../app/security/CsrfManager'
import { SensitiveStateRegistry } from '../../app/security/SensitiveStateRegistry'
import { SessionCoordinator, type LocalSessionState } from '../../app/security/SessionCoordinator'
import { AuthContinuationState, validChallenge } from './AuthContinuationState'
import { captureSecurityLink } from './SecurityLinkIngress'
import { SignInDestination } from './SafeDestination'
import { deploymentPresentation } from './DeploymentPresentation'
import { AskWorkspace } from '../knowledge/AskWorkspace'
import { LibrarySearch } from '../notes/LibrarySearch'

type SessionProjection = { state?: unknown; challengeId?: unknown }

export class AuthRuntime {
  readonly presentation = deploymentPresentation(document)
  readonly continuation = new AuthContinuationState()
  readonly signInDestination = new SignInDestination()
  readonly sensitive = new SensitiveStateRegistry()
  readonly ask = new AskWorkspace(this)
  readonly librarySearch = new LibrarySearch(this)
  readonly queries = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  readonly csrf: CsrfManager
  readonly api: ApiClient
  readonly session: SessionCoordinator
  private bootPromise: Promise<void> | null = null
  private listeners = new Set<() => void>()
  private registered = false
  private completingTransition = false
  private transitionEpoch = 0

  constructor() {
    captureSecurityLink(window.location, window.history, this.continuation)
    let client: ApiClient
    this.csrf = new CsrfManager(async () => {
      const result = await client.request<{ csrfToken?: unknown }>('GET', '/api/auth/csrf')
      if (typeof result.body?.csrfToken !== 'string') throw new ApiProtocolError()
      return result.body.csrfToken
    })
    client = new ApiClient(this.csrf, fetch, path => {
      const admittedScope = this.session?.viewerScope
      return failure => {
        // Auth proof failures may be invalid passwords/MFA, not session loss.
        // A late response from a previous viewer must never evict a new viewer.
        if (!path.startsWith('/api/auth/') && failure.problem.status === 401
            && this.state === 'authenticated' && this.session.viewerScope === admittedScope) {
          this.session.markIneligible(); this.notify()
        }
      }
    })
    this.api = client
    this.session = new SessionCoordinator(this.csrf, this.sensitive, endingScope => {
      if (endingScope.kind === 'authenticated') {
        // Commit the unknown-authority route gate before eviction: mounted observers
        // could otherwise recreate old-viewer queries while CSRF bootstrap is pending.
        flushSync(() => this.notify())
      }
      this.queries.clear()
    })
  }

  get state(): LocalSessionState { return this.completingTransition ? 'unknown' : this.session.state }
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private notify(): void { for (const listener of this.listeners) listener() }

  bootstrap(): Promise<void> {
    if (!this.bootPromise) this.bootPromise = this.refreshSession().finally(() => { this.bootPromise = null })
    return this.bootPromise
  }

  async refreshSession(replaceAuthenticated = false): Promise<void> {
    const result = await this.api.request<SessionProjection>('GET', '/api/auth/session')
    const projection = result.body
    if (!projection || (projection.state !== 'anonymous' && projection.state !== 'mfaRequired'
        && projection.state !== 'authenticated')) throw new ApiProtocolError()
    this.session.transition(projection.state, replaceAuthenticated)
    if (projection.state === 'mfaRequired') {
      this.continuation.challengeId = validChallenge(projection.challengeId) ? projection.challengeId : null
    } else {
      this.continuation.challengeId = null
    }
    if (!this.registered) {
      this.sensitive.register(() => { this.continuation.clear(); this.signInDestination.clear() })
      this.registered = true
    }
    // A changed authority invalidates the old proof; fetch for the new session.
    try { await this.csrf.refresh() } finally { this.notify() }
  }

  async establish(state: 'anonymous' | 'mfaRequired' | 'authenticated', challengeId?: unknown,
    afterProtectionReady?: () => void | Promise<void>): Promise<void> {
    if (state === 'mfaRequired' && !validChallenge(challengeId)) throw new ApiProtocolError()
    // Sign-in navigation hides intermediate authority until proof and navigation
    // commit. In-place flows (such as reset success) must not be remounted.
    // This flag grants no authority.
    const epoch = ++this.transitionEpoch
    this.completingTransition = afterProtectionReady !== undefined
    try {
      this.session.transition(state)
      this.continuation.clear()
      if (state === 'mfaRequired') this.continuation.challengeId = challengeId as string
      if (this.completingTransition) this.notify()
      await this.csrf.refresh()
      if (epoch !== this.transitionEpoch) throw new ApiProtocolError()
      await afterProtectionReady?.()
    } finally {
      if (epoch === this.transitionEpoch) { this.completingTransition = false; this.notify() }
    }
  }

  async logout(): Promise<void> {
    await this.api.request('POST', '/api/auth/logout')
    await this.establish('anonymous')
  }
}
