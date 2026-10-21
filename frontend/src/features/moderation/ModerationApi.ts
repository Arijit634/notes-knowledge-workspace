import { array, flag, record, text, uuid } from '../../app/api/Contract'
import { ApiProblemError, ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { page } from '../publishing/PublishingApi'

export const reportStates = ['open', 'under_review', 'dismissed', 'actioned', 'closed'] as const
export const reportCategories = ['harmfulContent', 'spam', 'harassment', 'other'] as const
export type Consequence = 'none' | 'removePublication' | 'removePublicationAndSuspendResponsibleAccount'
export type Report = { id: string; publicationId: string; category: string; description: string; status: string; submittedAt: string; reviewedAt: string | null; resolvedAt: string | null }
export function reportView(value: unknown): Report {
  const r = record(value), category = text(r.category, 30), status = text(r.status, 30)
  if (!reportStates.includes(status as typeof reportStates[number]) || !reportCategories.includes(category as typeof reportCategories[number])) throw new ApiProtocolError()
  return { id: uuid(r.id), publicationId: uuid(r.publicationId), category, status, description: text(r.description, 2000), submittedAt: text(r.submittedAt, 64), reviewedAt: r.reviewedAt === null ? null : text(r.reviewedAt, 64), resolvedAt: r.resolvedAt === null ? null : text(r.resolvedAt, 64) }
}
function decisionView(value: unknown) {
  const d = record(value), outcomes = record(d.outcomes), consequence = text(d.consequence, 80)
  if (!['none', 'removePublication', 'removePublicationAndSuspendResponsibleAccount'].includes(consequence)) throw new ApiProtocolError()
  return { id: uuid(d.id), reportId: uuid(d.reportId), consequence, reasonCode: text(d.reasonCode, 80), outcomes: { publication: text(outcomes.publication, 80), account: text(outcomes.account, 80) }, decidedAt: text(d.decidedAt, 64) }
}
export function reportDetail(value: unknown) {
  const d = record(value), e = d.publicEvidence === null ? null : record(d.publicEvidence), report = reportView(d.report)
  if (e && uuid(e.publicationId) !== report.publicationId) throw new ApiProtocolError()
  return { report, targetAvailable: flag(d.targetAvailable), publicEvidence: e && { title: text(e.title, 500), markdown: text(e.markdown) }, decision: d.decision === null ? null : decisionView(d.decision), audit: array(d.audit, v => { const a = record(v); return { action: text(a.action, 100), outcome: text(a.outcome, 100), reason: a.reason === null ? null : text(a.reason, 100), occurredAt: text(a.occurredAt, 64) } }, 100) }
}
const path = (id: string) => `/api/moderation/reports/${uuid(id)}`
export const moderationApi = {
  list: async (auth: AuthRuntime, state: string, category: string, cursor: string | null, signal?: AbortSignal) => page((await auth.api.request('GET', '/api/moderation/reports?' + new URLSearchParams({ limit: '20', sort: 'submittedDesc', ...(state ? { state } : {}), ...(category ? { category } : {}), ...(cursor ? { cursor } : {}) }), { signal })).body, reportView),
  detail: async (auth: AuthRuntime, id: string, signal?: AbortSignal) => { const d = reportDetail((await auth.api.request('GET', path(id), { signal })).body); if (d.report.id !== id) throw new ApiProtocolError(); return d },
  begin: async (auth: AuthRuntime, id: string) => { const d = reportDetail((await auth.api.request('POST', path(id) + '/begin-review', { retryOnCsrfInvalid: false })).body); if (d.report.id !== id) throw new ApiProtocolError(); return d },
  decide: async (auth: AuthRuntime, id: string, consequence: Consequence, reasonCode: string) => {
    const r = await auth.api.request('POST', path(id) + '/decisions', { json: { consequence, reasonCode }, retryOnCsrfInvalid: false })
    if (r.metadata.status !== 201) throw new ApiProtocolError()
    const d = decisionView(r.body); if (d.reportId !== id || d.consequence !== consequence) throw new ApiProtocolError(); return d
  },
}
export const moderationKeys = { root: (auth: AuthRuntime) => ['moderation', auth.session.viewerScope] as const }
// Only an in-memory alias is used in restricted query keys, never report text/IDs.
const aliases = new WeakMap<object, Map<string, number>>()
export function reportAlias(scope: object, id: string) { let m = aliases.get(scope); if (!m) { m = new Map(); aliases.set(scope, m) }; if (!m.has(id)) m.set(id, m.size + 1); return m.get(id)! }
export function moderationDenied(error: unknown) { return error instanceof ApiProblemError && error.problem.status === 403 && error.problem.code === 'access_denied' }
export function moderationRecent(error: unknown) { return error instanceof ApiProblemError && error.problem.status === 403 && error.problem.code === 'recent_authentication_required' }
const boundaries = new WeakMap<object, number>()
export function moderationBoundary(auth: AuthRuntime) { return boundaries.get(auth.session.viewerScope) ?? 0 }
export function clearModeration(auth: AuthRuntime) {
  boundaries.set(auth.session.viewerScope, moderationBoundary(auth) + 1)
  void auth.queries.cancelQueries({ queryKey: moderationKeys.root(auth) }, { revert: false })
  auth.queries.setQueriesData({ queryKey: moderationKeys.root(auth) }, null)
}
