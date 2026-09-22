export type CsrfTokenLoader = () => Promise<string>

export class CsrfUnavailableError extends Error {
  constructor() {
    super('Request protection could not be refreshed.')
    this.name = 'CsrfUnavailableError'
  }
}

/** Proof lives in this instance's memory only; Phase 3 supplies the real loader. */
export class CsrfManager {
  private proof: string | null = null
  private refreshing: Promise<string> | null = null
  private generation = 0

  constructor(private readonly loader?: CsrfTokenLoader) {}

  set(proof: string): void {
    if (!proof || proof.length > 512 || /[\r\n\0]/.test(proof)) {
      throw new CsrfUnavailableError()
    }
    this.proof = proof
  }

  clear(): void {
    this.proof = null
    this.generation++
    this.refreshing = null
  }

  async forUnsafeRequest(): Promise<string> {
    return this.proof ?? this.refresh()
  }

  async refresh(): Promise<string> {
    if (this.loader === undefined) throw new CsrfUnavailableError()
    if (this.refreshing !== null) return this.refreshing

    this.proof = null
    const generation = ++this.generation
    const loader = this.loader
    const pending = (async () => {
      try {
        const value = await loader()
        if (generation !== this.generation) throw new CsrfUnavailableError()
        this.set(value)
        return value
      } catch {
        if (generation === this.generation) this.proof = null
        throw new CsrfUnavailableError()
      }
    })()
    this.refreshing = pending
    try {
      return await pending
    } finally {
      if (this.refreshing === pending) this.refreshing = null
    }
  }
}
