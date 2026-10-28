import { afterEach, describe, expect, it, vi } from 'vitest'
import { AuthRuntime } from '../auth/AuthRuntime'
import { knowledgeApi, type Operation, type SearchHistory, type KnowledgeResult } from './KnowledgeApi'
import { operationInterval } from './AskKnowledgePage'

const id = '01990a55-9e12-7ac4-8f5b-31aa4a91d401'
const result: KnowledgeResult = { deterministicResults: [], aiAnswer: { claims: ['Synthetic fact'], conflicting: false }, citations: [], coverage: { boundary: 'bounded', inspectedSources: 1, completed: false, corpusChanged: false, truncated: false, retryRequired: false, classificationUncertain: false }, degraded: null, insufficientEvidence: false }
const now = Date.parse('2026-10-27T12:00:00Z')
const operation: Operation = { operationId: 'v1.synthetic', status: 'running', submittedAt: new Date(now - 1800000).toISOString(), expiresAt: new Date(now + 3600000).toISOString(), totalSources: 1194, inspectedSources: 600, result: null }
afterEach(() => vi.restoreAllMocks())
describe('Retained search workflow and bounded status checks', () => {
  it('honors the exact default and explicit server expiry boundaries without extending either', () => {
    const submitted = Date.parse(operation.submittedAt)
    const implicit = { ...operation, expiresAt: null }
    expect(operationInterval(implicit, submitted + 86400000 - 1, false)).toBe(30000)
    expect(operationInterval(implicit, submitted + 86400000, false)).toBe(false)
    const explicit = { ...operation, expiresAt: new Date(submitted + 60000).toISOString() }
    expect(operationInterval(explicit, submitted + 59999, false)).toBe(3000)
    expect(operationInterval(explicit, submitted + 60000, false)).toBe(false)
    expect(operationInterval({ ...operation, expiresAt: '1970-01-01T00:00:00Z' }, now, false)).toBe(false)
    expect(operationInterval({ ...operation, expiresAt: 'not-an-instant' }, now, false)).toBe(false)
    for (const status of ['completed', 'cancelled', 'obsolete', 'failed'] as const)
      expect(operationInterval({ ...explicit, status }, submitted + 1000, false)).toBe(false)
  })
  it('continues checking a thirty-minute operation, backs off errors, and stops at expiry or terminal state', () => {
    expect(operationInterval(operation, now, false)).toBe(30000)
    expect(operationInterval(operation, now, true)).toBe(30000)
    expect(operationInterval(operation, now + 3600000, false)).toBe(false)
    for (const status of ['completed','failed','cancelled','obsolete'] as const) expect(operationInterval({ ...operation, status }, now, false)).toBe(false)
    expect(operationInterval({ ...operation, submittedAt: 'not-a-date', expiresAt: null }, now, false)).toBe(false)
  })
  it('submits the explicit period and retains the original result boundary when the draft filter changes', async () => {
    const auth = new AuthRuntime(), spy = vi.spyOn(knowledgeApi, 'query').mockResolvedValue({ kind: 'result', result, historyId: id })
    auth.ask.period({ preset: 'custom', from: '2021-01-01', to: '2021-12-31' }); auth.ask.input('Synthetic period question'); await auth.ask.submit()
    const accepted = auth.ask.snapshot().submittedDuring
    expect(accepted).not.toBeNull(); expect(spy.mock.calls[0][3]).toEqual(accepted)
    auth.ask.period({ preset: 'any', from: '', to: '' }); expect(auth.ask.snapshot().submittedDuring).toEqual(accepted); expect(spy).toHaveBeenCalledTimes(1)
    auth.ask.resume({ id, query: 'Scoped saved question', status: 'completed', submittedAt: operation.submittedAt, expiresAt: operation.expiresAt!, result, operation: null, savedDuring: accepted })
    expect(auth.ask.snapshot().submittedDuring).toEqual(accepted); expect(auth.ask.snapshot().period.preset).toBe('custom')
    auth.ask.clear(); expect(auth.ask.snapshot().submittedDuring).toBeNull(); auth.queries.clear()
  })
  it('does not dispatch an invalid date range', async () => {
    const auth = new AuthRuntime(), spy = vi.spyOn(knowledgeApi, 'query'); auth.ask.input('Synthetic question')
    auth.ask.period({ preset: 'custom', from: '2026-03-02', to: '2026-03-01' }); await auth.ask.submit()
    expect(spy).not.toHaveBeenCalled(); expect(auth.ask.snapshot().error).toContain('end date'); auth.queries.clear()
  })
  it('allows a new question while the earlier operation remains retained without cancelling it', async () => {
    const auth = new AuthRuntime(), query = vi.spyOn(knowledgeApi, 'query').mockResolvedValue({ kind: 'operation', operation, historyId: id }), cancel = vi.spyOn(knowledgeApi, 'cancel')
    auth.ask.input('Synthetic first question'); await auth.ask.submit(); expect(auth.ask.snapshot().historyId).toBe(id)
    auth.ask.clear(); auth.ask.input('Different synthetic question'); await auth.ask.submit()
    expect(query).toHaveBeenCalledTimes(2); expect(cancel).not.toHaveBeenCalled(); expect(auth.ask.snapshot().input).toBe('Different synthetic question')
    auth.queries.clear()
  })
  it('removes an obsolete answer from memory and rechecks retained history before redisplay', async () => {
    const auth = new AuthRuntime(), entry: SearchHistory = { id, query: 'Synthetic saved question', status: 'completed', submittedAt: operation.submittedAt, expiresAt: operation.expiresAt!, result, operation: null }
    auth.ask.resume(entry); expect(auth.ask.snapshot().result).toBe(result)
    vi.spyOn(knowledgeApi, 'historyDetail').mockResolvedValue({ ...entry, result: null, status: 'obsolete' })
    await auth.ask.revalidate(new AbortController().signal); expect(auth.ask.snapshot().result).toBeNull()
    auth.ask.resume({ ...entry, operation }); auth.ask.observe({ ...operation, status: 'completed', result }); expect(auth.ask.snapshot().result).toBe(result)
    auth.ask.observe({ ...operation, status: 'obsolete', result: null }); expect(auth.ask.snapshot().result).toBeNull(); auth.queries.clear()
  })
})
