import { describe, expect, it, vi } from 'vitest'
import { CsrfManager } from './CsrfManager'
import { SensitiveStateRegistry } from './SensitiveStateRegistry'
import { SessionCoordinator } from './SessionCoordinator'
import { ANONYMOUS_VIEWER_SCOPE } from './ViewerCacheScope'
import { canEnterRoute } from './RouteGate'

describe('session authority and viewer scope', () => {
  it('keeps unknown and pre-MFA states out of full private routes', () => {
    expect(canEnterRoute('unknown', 'FULL_AUTHENTICATED')).toBe(false)
    expect(canEnterRoute('mfaRequired', 'FULL_AUTHENTICATED')).toBe(false)
    expect(canEnterRoute('mfaRequired', 'MFA_CONTINUATION')).toBe(true)
    expect(canEnterRoute('authenticated', 'MFA_CONTINUATION')).toBe(false)
    expect(canEnterRoute('anonymous', 'ANONYMOUS_ONLY')).toBe(true)
    expect(canEnterRoute('unknown', 'PUBLIC')).toBe(true)
  })

  it('rotates nonsecret epoch and clears all sensitive owners on authority loss', async () => {
    const csrf = new CsrfManager()
    const sensitive = new SensitiveStateRegistry()
    const cleaner = vi.fn()
    const cacheCleaner = vi.fn()
    sensitive.register(cleaner)
    let nextEpoch = 0
    const coordinator = new SessionCoordinator(csrf, sensitive, cacheCleaner,
      () => `synthetic_epoch_${++nextEpoch}`)
    expect(coordinator.state).toBe('unknown')
    expect(coordinator.viewerScope).toEqual(ANONYMOUS_VIEWER_SCOPE)
    coordinator.transition('mfaRequired')
    expect(canEnterRoute(coordinator.state, 'FULL_AUTHENTICATED')).toBe(false)
    coordinator.transition('authenticated')
    expect(coordinator.viewerScope).toEqual({ kind: 'authenticated', viewerEpoch: 'synthetic_epoch_1' })
    csrf.set('synthetic-proof')
    const afterFirst = cleaner.mock.calls.length
    coordinator.transition('authenticated')
    expect(cleaner).toHaveBeenCalledTimes(afterFirst)
    coordinator.transition('authenticated', true)
    expect(coordinator.viewerScope).toEqual({ kind: 'authenticated', viewerEpoch: 'synthetic_epoch_2' })
    coordinator.markIneligible()
    expect(coordinator.state).toBe('anonymous')
    expect(coordinator.viewerScope).toEqual(ANONYMOUS_VIEWER_SCOPE)
    expect(cacheCleaner).toHaveBeenCalled()
    expect(cleaner).toHaveBeenCalled()
    await expect(csrf.forUnsafeRequest()).rejects.toThrow()
  })

  it('drops authority even when one state cleaner fails', () => {
    const sensitive = new SensitiveStateRegistry()
    const secondCleaner = vi.fn()
    sensitive.register(() => { throw new Error('private value') })
    sensitive.register(secondCleaner)
    const coordinator = new SessionCoordinator(new CsrfManager(), sensitive, vi.fn(),
      () => 'synthetic_epoch_1')
    expect(() => coordinator.transition('authenticated')).toThrow('Session transition could not be completed safely.')
    expect(coordinator.state).toBe('unknown')
    expect(coordinator.viewerScope).toEqual(ANONYMOUS_VIEWER_SCOPE)
    expect(secondCleaner).toHaveBeenCalled()
  })
})
