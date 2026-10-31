import { describe, expect, it } from 'vitest'
import { anyDate, decodeSavedPeriod, filterForSavedPeriod, savedPeriod } from './SearchPeriod'
describe('Explicit saved-period boundaries', () => {
  it('defaults to all dates and includes both custom calendar days in local time', () => {
    expect(savedPeriod(anyDate())).toBeNull()
    const d = savedPeriod({ preset: 'custom', from: '2026-03-08', to: '2026-03-08' })!
    expect(d.from).toBe(new Date(2026, 2, 8).toISOString()); expect(d.toExclusive).toBe(new Date(2026, 2, 9).toISOString())
    expect(filterForSavedPeriod(d)).toEqual({ preset: 'custom', from: '2026-03-08', to: '2026-03-08' })
  })
  it('uses local calendar days for seven/thirty-day presets rather than a fixed millisecond duration', () => {
    const now = new Date(2026, 2, 12, 17)
    expect(savedPeriod({ ...anyDate(), preset: '7' }, now)).toEqual({ from: new Date(2026, 2, 6).toISOString(), toExclusive: new Date(2026, 2, 13).toISOString() })
    expect(savedPeriod({ ...anyDate(), preset: '30' }, now)?.from).toBe(new Date(2026, 1, 11).toISOString())
  })
  it('rejects missing, reversed and impossible dates without silently normalizing or widening', () => {
    for (const [from, to] of [['', ''], ['2026-02-30', '2026-03-01'], ['2026-03-02', '2026-03-01']]) expect(() => savedPeriod({ preset: 'custom', from, to })).toThrow()
    expect(() => decodeSavedPeriod({ from: 'bad', toExclusive: '2026-03-01' })).toThrow()
    expect(() => decodeSavedPeriod({ from: '2026-03-02', toExclusive: '2026-03-01' })).toThrow()
  })
})
