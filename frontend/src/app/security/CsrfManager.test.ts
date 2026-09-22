import { describe, expect, it, vi } from 'vitest'
import { CsrfManager, CsrfUnavailableError } from './CsrfManager'

describe('CsrfManager', () => {
  it('keeps proof in memory and shares a pending refresh', async () => {
    let resolve!: (value: string) => void
    const loader = vi.fn(() => new Promise<string>(done => { resolve = done }))
    const manager = new CsrfManager(loader)
    const first = manager.forUnsafeRequest()
    const second = manager.forUnsafeRequest()
    resolve('synthetic-proof')
    expect(await first).toBe('synthetic-proof')
    expect(await second).toBe('synthetic-proof')
    expect(await manager.forUnsafeRequest()).toBe('synthetic-proof')
    expect(loader).toHaveBeenCalledTimes(1)
  })

  it('rejects a late proof after authority is cleared', async () => {
    let resolve!: (value: string) => void
    const manager = new CsrfManager(() => new Promise(done => { resolve = done }))
    const pending = manager.refresh()
    manager.clear()
    resolve('stale-proof')
    await expect(pending).rejects.toBeInstanceOf(CsrfUnavailableError)
  })

  it('fails closed without a loader or with invalid proof', async () => {
    const manager = new CsrfManager()
    await expect(manager.forUnsafeRequest()).rejects.toBeInstanceOf(CsrfUnavailableError)
    expect(() => manager.set('bad\r\nproof')).toThrow(CsrfUnavailableError)
    manager.set('first-proof')
    manager.set('replacement-proof')
    expect(await manager.forUnsafeRequest()).toBe('replacement-proof')
    manager.clear()
    await expect(manager.forUnsafeRequest()).rejects.toBeInstanceOf(CsrfUnavailableError)
  })
})
