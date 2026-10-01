import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AuthRuntime } from './AuthRuntime'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(complete => { resolve = complete })
  return { promise, resolve }
}

function json(body: unknown) {
  return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
}

function PrivateObserver({ auth, load }: { auth: AuthRuntime; load: () => Promise<string> }) {
  const scope = auth.session.viewerScope
  if (scope.kind !== 'authenticated') throw new Error('Private observer requires full authority')
  const query = useQuery({ queryKey: ['private', scope.viewerEpoch], queryFn: load }, auth.queries)
  return <div>{query.data ?? 'Loading private state'}</div>
}

function AuthorityGate({ auth, load }: { auth: AuthRuntime; load: () => Promise<string> }) {
  const [, update] = useState(0)
  useEffect(() => auth.subscribe(() => update(value => value + 1)), [auth])
  return auth.state === 'authenticated' ? <PrivateObserver auth={auth} load={load} /> : <div>No private authority</div>
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('authority transition with mounted Query observers', () => {
  it.each(['anonymous', 'mfaRequired'] as const)(
    'unmounts old observers before eviction on authenticated -> %s, even during CSRF bootstrap', async next => {
      let pendingCsrf: Promise<Response> | undefined
      vi.stubGlobal('fetch', vi.fn(async () => pendingCsrf ?? json({ csrfToken: 'synthetic-proof' })))
      const auth = new AuthRuntime()
      await auth.establish('authenticated')
      const oldScope = auth.session.viewerScope
      const oldResult = deferred<string>()
      render(<AuthorityGate auth={auth} load={() => oldResult.promise} />)
      expect(auth.queries.getQueryCache().getAll()[0].getObserversCount()).toBe(1)
      const sensitive = vi.fn()
      auth.sensitive.register(sensitive)
      const clear = auth.queries.clear.bind(auth.queries)
      const eviction = vi.spyOn(auth.queries, 'clear').mockImplementation(() => {
        expect(auth.state).toBe('unknown')
        expect(auth.queries.getQueryCache().getAll().every(query => query.getObserversCount() === 0)).toBe(true)
        clear()
      })
      const csrf = deferred<Response>()
      pendingCsrf = csrf.promise
      let transition!: Promise<void>
      act(() => { transition = auth.establish(next, next === 'mfaRequired' ? 'c'.repeat(43) : undefined) })
      expect(screen.getByText('No private authority')).toBeTruthy()
      expect(auth.queries.getQueryCache().getAll()).toHaveLength(0)
      expect(auth.session.viewerScope).not.toEqual(oldScope)
      expect(sensitive).toHaveBeenCalledTimes(1)
      const nextProof = auth.csrf.forUnsafeRequest()
      await act(async () => {
        oldResult.resolve('Old viewer data')
        csrf.resolve(json({ csrfToken: 'synthetic-next-proof' }))
        await transition
      })
      expect(auth.queries.getQueryCache().getAll()).toHaveLength(0)
      expect(eviction).toHaveBeenCalledTimes(1)
      expect(await nextProof).toBe('synthetic-next-proof')
      expect(await auth.csrf.forUnsafeRequest()).toBe('synthetic-next-proof')
    },
  )

  it('rotates authenticated replacement scope and evicts the old mounted viewer', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
      json(String(input).endsWith('/session') ? { state: 'authenticated' } : { csrfToken: 'synthetic-proof' })))
    const auth = new AuthRuntime()
    await auth.establish('authenticated')
    const oldScope = auth.session.viewerScope
    render(<AuthorityGate auth={auth} load={async () => 'Current viewer data'} />)
    await screen.findByText('Current viewer data')
    await act(async () => { await auth.refreshSession(true) })
    await screen.findByText('Current viewer data')
    const newScope = auth.session.viewerScope
    expect(newScope.kind).toBe('authenticated')
    expect(newScope).not.toEqual(oldScope)
    expect(auth.queries.getQueryCache().getAll()).toHaveLength(1)
    expect(auth.queries.getQueryCache().getAll()[0].queryKey).toEqual([
      'private', newScope.kind === 'authenticated' ? newScope.viewerEpoch : null,
    ])
  })

  it('does not erase a new viewer when old CSRF and private requests finish late', async () => {
    let pendingCsrf: Promise<Response> | undefined
    vi.stubGlobal('fetch', vi.fn(async () => pendingCsrf ?? json({ csrfToken: 'synthetic-proof' })))
    const auth = new AuthRuntime()
    await auth.establish('authenticated')
    const oldScope = auth.session.viewerScope
    const oldResult = deferred<string>()
    const load = vi.fn().mockImplementationOnce(() => oldResult.promise)
      .mockResolvedValue('New viewer data')
    render(<AuthorityGate auth={auth} load={load} />)
    const csrf = deferred<Response>()
    pendingCsrf = csrf.promise
    let staleTransition!: Promise<unknown>
    act(() => {
      // Superseded CSRF refresh is expected to reject, not supply old authority.
      staleTransition = auth.establish('anonymous').catch(error => error)
    })
    pendingCsrf = undefined
    await act(async () => { await auth.establish('authenticated') })
    await screen.findByText('New viewer data')
    const newScope = auth.session.viewerScope
    expect(newScope).not.toEqual(oldScope)
    await act(async () => {
      oldResult.resolve('Old viewer data')
      csrf.resolve(json({ csrfToken: 'synthetic-stale-proof' }))
      expect(await staleTransition).toBeInstanceOf(Error)
    })
    expect(screen.queryByText('Old viewer data')).toBeNull()
    expect(screen.getByText('New viewer data')).toBeTruthy()
    expect(auth.state).toBe('authenticated')
    expect(auth.session.viewerScope).toEqual(newScope)
    expect(auth.queries.getQueryCache().getAll()).toHaveLength(1)
    expect(await auth.csrf.forUnsafeRequest()).toBe('synthetic-proof')
  })
})
