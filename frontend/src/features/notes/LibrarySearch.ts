import type { AuthRuntime } from '../auth/AuthRuntime'
export type LibrarySearchRequest = { query: string; tags: string[]; serial: number }
/** Cross-route intent stays in this viewer's memory, never history or browser storage. */
export class LibrarySearch {
  private value = { input: '', request: null as LibrarySearchRequest | null }
  private serial = 0
  private listeners = new Set<() => void>()
  constructor(auth: AuthRuntime) { auth.sensitive.register(() => this.clear()) }
  snapshot = () => this.value
  subscribe = (f: () => void) => { this.listeners.add(f); return () => { this.listeners.delete(f) } }
  private notify() { this.listeners.forEach(f => f()) }
  input(input: string) { this.value = { ...this.value, input }; this.notify() }
  submit(tags: string[] = []) { this.value = { ...this.value, request: this.value.input.trim() ? { query: this.value.input.trim(), tags, serial: ++this.serial } : null }; this.notify() }
  clear() { this.value = { input: '', request: null }; this.notify() }
}
