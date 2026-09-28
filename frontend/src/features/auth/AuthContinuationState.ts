/** Transient browser-flow material. Never persisted, placed in URLs, or used as authority. */
export class AuthContinuationState {
  verificationToken: string | null = null
  resetToken: string | null = null
  challengeId: string | null = null
  returnIntent: '/' | null = null

  clear(): void {
    this.verificationToken = null
    this.resetToken = null
    this.challengeId = null
    this.returnIntent = null
  }
}

export function validChallenge(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value)
}
