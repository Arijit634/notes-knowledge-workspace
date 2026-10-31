import { decodeSavedPeriod, type SavedDuring } from './SearchPeriod'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { array, flag, integer, record, text, uuid } from '../../app/api/Contract'
import { ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'

export type SourceLocation = { kind: string; start: number | null; end: number | null; page: number | null;
  timeStart: number | null; timeEnd: number | null; x: number | null; y: number | null; width: number | null; height: number | null }
export type Citation = { noteId: string; attachmentId: string | null; location: SourceLocation }
export type DeterministicOccurrence = Citation | { noteId: string; attachmentId: null; location: null }
export type KnowledgeResult = { deterministicResults: { kind: string; value: string; occurrences: DeterministicOccurrence[]; aiEnabled: boolean }[];
  aiAnswer: null | { claims: string[]; conflicting: boolean }; citations: Citation[];
  coverage: { boundary: string; inspectedSources: number; completed: boolean; corpusChanged: boolean; truncated: boolean; retryRequired: boolean; classificationUncertain: boolean };
  degraded: string | null; insufficientEvidence: boolean }
export type Operation = { operationId: string; status: 'queued' | 'running' | 'retry_wait' | 'completed' | 'failed' | 'cancelled' | 'obsolete'; submittedAt: string; result: KnowledgeResult | null; inspectedSources?: number; totalSources?: number | null; expiresAt?: string | null; historyId?: string | null }
export type SearchHistory = { savedDuring?: SavedDuring | null; id: string; query: string; status: Operation['status']; submittedAt: string; expiresAt: string; operation: Operation | null; result: KnowledgeResult | null }
const nullable = (value: unknown) => {
  if (value === null) return null
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) throw new ApiProtocolError()
  return value
}
export function citation(value: unknown): Citation {
  const c = record(value), l = record(c.location)
  const kind = text(l.kind, 40)
  if (!['note_text', 'pdf_text', 'whole_image', 'image_region', 'transcript', 'video_scene'].includes(kind)) throw new ApiProtocolError()
  return { noteId: uuid(c.noteId), attachmentId: c.attachmentId === null ? null : uuid(c.attachmentId), location: {
    kind, start: nullable(l.start), end: nullable(l.end), page: nullable(l.page), timeStart: nullable(l.timeStart), timeEnd: nullable(l.timeEnd),
    x: nullable(l.x), y: nullable(l.y), width: nullable(l.width), height: nullable(l.height) } }
}
function deterministicOccurrence(value: unknown, kind: string): DeterministicOccurrence {
  const c = record(value)
  // A whole-Note lexical match has no invented paragraph/media position.
  // This exception is never used for model-evidence citations.
  if (kind === 'note' && c.attachmentId === null && c.location === null) {
    return { noteId: uuid(c.noteId), attachmentId: null, location: null }
  }
  return citation(value)
}
export function knowledgeResult(value: unknown): KnowledgeResult {
  const r = record(value), c = record(r.coverage), answer = r.aiAnswer === null ? null : record(r.aiAnswer)
  return { deterministicResults: array(r.deterministicResults, value => { const d = record(value); return {
    kind: text(d.kind, 80), value: text(d.value, 16384), occurrences: array(d.occurrences, c => deterministicOccurrence(c, text(d.kind, 80))), aiEnabled: flag(d.aiEnabled) } }),
    aiAnswer: answer && { claims: array(answer.claims, value => text(value, 16384), 256), conflicting: flag(answer.conflicting) },
    citations: array(r.citations, citation), coverage: { boundary: text(c.boundary, 80), inspectedSources: integer(c.inspectedSources), completed: flag(c.completed),
      corpusChanged: flag(c.corpusChanged), truncated: flag(c.truncated), retryRequired: flag(c.retryRequired), classificationUncertain: flag(c.classificationUncertain) },
    degraded: r.degraded === null ? null : text(r.degraded, 80), insufficientEvidence: flag(r.insufficientEvidence) }
}
export function operation(value: unknown): Operation {
  const r = record(value), id = text(r.operationId, 256), status = text(r.status, 20)
  if (!/^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/.test(id) || !['queued','running','retry_wait','completed','failed','cancelled','obsolete'].includes(status)) throw new ApiProtocolError()
  return { operationId: id, status: status as Operation['status'], submittedAt: text(r.submittedAt, 64), result: r.result === null ? null : knowledgeResult(r.result), inspectedSources: r.inspectedSources === undefined ? 0 : integer(r.inspectedSources), totalSources: r.totalSources == null ? null : integer(r.totalSources), expiresAt: r.expiresAt == null ? null : text(r.expiresAt, 64), historyId: r.historyId == null ? null : uuid(r.historyId) }
}
function historyEntry(value: unknown): SearchHistory {
  const r = record(value), status = text(r.status, 20)
  if (!['queued','running','retry_wait','completed','failed','cancelled','obsolete'].includes(status)) throw new ApiProtocolError()
  return { savedDuring: decodeSavedPeriod(r.savedDuring), id: uuid(r.id), query: text(r.query, 2048), status: status as Operation['status'], submittedAt: text(r.submittedAt, 64), expiresAt: text(r.expiresAt, 64), operation: r.operation == null ? null : operation(r.operation), result: r.result == null ? null : knowledgeResult(r.result) }
}
function responseHistory(path: string | null | undefined) {
  if (!path) return null // Compatibility with older responses; never invent a saved record.
  const match = /^\/api\/knowledge\/history\/([0-9a-f-]{36})$/.exec(path)
  if (!match) throw new ApiProtocolError()
  return uuid(match[1])
}
export const activeOperation = (value: Operation) => ['queued', 'running', 'retry_wait'].includes(value.status)
export const operationPath = (id: string) => `/api/knowledge/operations/${encodeURIComponent(id)}`
export const knowledgeApi = {
  query: async (auth: AuthRuntime, query: string, signal: AbortSignal, savedDuring: SavedDuring | null = null): Promise<({ kind: 'result'; result: KnowledgeResult } | { kind: 'operation'; operation: Operation }) & { historyId: string | null }> => {
    const r = await auth.api.request('POST', '/api/knowledge/query', { json: { query, ...(savedDuring ? { scope: { savedDuring } } : {}) }, signal, retryOnCsrfInvalid: false })
    const historyId = responseHistory(r.metadata.contentLocation)
    if (r.metadata.status === 200) return { kind: 'result', result: knowledgeResult(r.body), historyId }
    if (r.metadata.status !== 202) throw new ApiProtocolError()
    const accepted = operation(r.body)
    if (r.metadata.location !== operationPath(accepted.operationId)) throw new ApiProtocolError()
    return { kind: 'operation', operation: accepted, historyId: historyId ?? accepted.historyId ?? null }
  },
  poll: async (auth: AuthRuntime, id: string, signal: AbortSignal) => {
    const value = operation((await auth.api.request('GET', operationPath(id), { signal })).body)
    if (value.operationId !== id) throw new ApiProtocolError()
    return value
  },
  cancel: async (auth: AuthRuntime, id: string) => { await auth.api.request('DELETE', operationPath(id), { retryOnCsrfInvalid: false }) },
  history: async (auth: AuthRuntime, cursor: string | null, signal: AbortSignal) => {
    const r = record((await auth.api.request('GET', `/api/knowledge/history${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`, { signal })).body)
    return { items: array(r.items, historyEntry, 20), nextCursor: r.nextCursor == null ? null : text(r.nextCursor, 4096) }
  },
  historyDetail: async (auth: AuthRuntime, id: string, signal: AbortSignal) => historyEntry((await auth.api.request('GET', `/api/knowledge/history/${uuid(id)}`, { signal })).body),
  removeHistory: async (auth: AuthRuntime, id?: string) => { await auth.api.request('DELETE', `/api/knowledge/history${id ? `/${uuid(id)}` : ''}`, { retryOnCsrfInvalid: false }) },
}
