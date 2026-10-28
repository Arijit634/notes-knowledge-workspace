import { ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'
export type SavedDuring = { from: string; toExclusive: string }
export type DateFilter = { preset: 'any' | '7' | '30' | 'custom'; from: string; to: string }
export const anyDate = (): DateFilter => ({ preset: 'any', from: '', to: '' })
function day(value: string): Date {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error('Choose both dates.')
  const [year, month, date] = value.split('-').map(Number), d = new Date(year, month - 1, date)
  if (year < 100 || d.getFullYear() !== year || d.getMonth() !== month - 1 || d.getDate() !== date) throw new Error('Choose valid dates.')
  return d
}
export function savedPeriod(value: DateFilter, now = new Date()): SavedDuring | null {
  if (value.preset === 'any') return null
  const from = value.preset === 'custom' ? day(value.from) : new Date(now.getFullYear(), now.getMonth(), now.getDate() - (value.preset === '7' ? 6 : 29))
  const until = value.preset === 'custom' ? day(value.to) : new Date(now.getFullYear(), now.getMonth(), now.getDate())
  if (from > until) throw new Error('The end date must be on or after the start date.')
  until.setDate(until.getDate() + 1)
  return { from: from.toISOString(), toExclusive: until.toISOString() }
}
export function decodeSavedPeriod(value: unknown): SavedDuring | null {
  if (value == null) return null
  if (typeof value !== 'object' || Array.isArray(value)) throw new ApiProtocolError()
  const d = value as Record<string, unknown>
  if (Object.keys(d).length !== 2 || typeof d.from !== 'string' || typeof d.toExclusive !== 'string'
    || !d.from.endsWith('Z') || !d.toExclusive.endsWith('Z') || !Number.isFinite(Date.parse(d.from)) || !Number.isFinite(Date.parse(d.toExclusive)) || Date.parse(d.from) >= Date.parse(d.toExclusive)) throw new ApiProtocolError()
  return { from: d.from, toExclusive: d.toExclusive }
}
function localDay(d: Date) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
export function filterForSavedPeriod(d: SavedDuring | null): DateFilter {
  return d ? { preset: 'custom', from: localDay(new Date(d.from)), to: localDay(new Date(Date.parse(d.toExclusive) - 1)) } : anyDate()
}
export function periodLabel(d: SavedDuring | null) { return d ? `${new Date(d.from).toLocaleDateString()} – ${new Date(Date.parse(d.toExclusive) - 1).toLocaleDateString()}` : 'Any time' }
