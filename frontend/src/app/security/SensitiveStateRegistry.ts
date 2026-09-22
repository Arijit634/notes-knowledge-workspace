export type SensitiveStateCleaner = () => void

/** Registered owners clear memory-only drafts, results and workflow state on authority loss. */
export class SensitiveStateRegistry {
  private readonly cleaners = new Set<SensitiveStateCleaner>()

  register(cleaner: SensitiveStateCleaner): () => void {
    this.cleaners.add(cleaner)
    return () => { this.cleaners.delete(cleaner) }
  }

  clearAll(): void {
    let failed = false
    for (const cleaner of this.cleaners) {
      try { cleaner() } catch { failed = true }
    }
    if (failed) throw new Error('Sensitive state could not be cleared.')
  }
}
