import { anyDate, filterForSavedPeriod, savedPeriod, type DateFilter, type SavedDuring } from './SearchPeriod'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { knowledgeApi, type KnowledgeResult, type Operation, type SearchHistory } from './KnowledgeApi'
import { featureError } from '../../app/ui/featureError'

type Snapshot = { period: DateFilter; submittedDuring: SavedDuring | null; requestStartedAt: number | null; input: string; result: KnowledgeResult | null; operation: Operation | null; historyId: string | null; busy: boolean; error: string; serial: number }
const empty = (): Snapshot => ({ period: anyDate(), submittedDuring: null, requestStartedAt: null, input: '', result: null, operation: null, historyId: null, busy: false, error: '', serial: 0 })

/** One private, memory-only workflow. Route navigation is not cancellation. */
export class AskWorkspace {
  private value = empty()
  private listeners = new Set<() => void>()
  private controller: AbortController | null = null
  constructor(private readonly auth: AuthRuntime) { auth.sensitive.register(() => this.clear()) }
  snapshot = () => this.value
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private update(patch: Partial<Snapshot>) { this.value = { ...this.value, ...patch }; this.listeners.forEach(f => f()) }
  period(value: DateFilter) { this.update({ period: value }) }
  input(value: string) { this.update({ input: value }) }
  clear() { this.controller?.abort(); this.controller = null; this.value = { ...empty(), serial: this.value.serial + 1 }; this.listeners.forEach(f => f()) }
  observe(operation: Operation) {
    if (operation.operationId !== this.value.operation?.operationId || operation === this.value.operation) return
    this.update({ operation, result: operation.status === 'completed' ? operation.result : null })
  }
  resume(entry: SearchHistory) { this.clear(); this.update({ input: entry.query, period: filterForSavedPeriod(entry.savedDuring ?? null), submittedDuring: entry.savedDuring ?? null, historyId: entry.id, operation: entry.operation, result: entry.result }) }
  async revalidate(signal: AbortSignal) {
    const id = this.value.historyId, scope = this.auth.session.viewerScope
    if (!id) return
    this.update({ result: null })
    try { const entry = await knowledgeApi.historyDetail(this.auth, id, signal)
      if (!signal.aborted && this.value.historyId === id && scope === this.auth.session.viewerScope) this.resume(entry)
    } catch (e) { if (!signal.aborted && this.value.historyId === id && scope === this.auth.session.viewerScope) this.update({ result: null, operation: null, error: featureError(e) }) }
  }
  async submit() {
    if (this.value.busy || !this.value.input.trim()) return
    let period: SavedDuring | null
    try { period = savedPeriod(this.value.period) } catch (e) { this.update({ error: e instanceof Error ? e.message : 'Choose valid dates.' }); return }
    const scope = this.auth.session.viewerScope, controller = new AbortController()
    this.controller = controller
    this.update({ busy: true, requestStartedAt: Date.now(), error: '', historyId: null, submittedDuring: period, result: null, operation: null, serial: this.value.serial + 1 })
    const current = () => this.controller === controller && !controller.signal.aborted && this.auth.session.viewerScope === scope
    try {
      const response = await knowledgeApi.query(this.auth, this.value.input.trim(), controller.signal, period)
      if (!current()) return
      this.update({ historyId: response.historyId, ...(response.kind === 'result' ? { result: response.result } : { operation: response.operation }) })
    } catch (e) { if (current()) this.update({ error: featureError(e) }) }
    finally { if (current()) this.update({ busy: false }) }
  }
}
