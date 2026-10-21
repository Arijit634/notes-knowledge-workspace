/** Only implemented first-party routes. No arbitrary URL, query, fragment, or encoded path. */
export function safeDestination(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 180) return null
  if (['/notes', '/notes/new', '/ask', '/settings/profile', '/settings/privacy', '/settings/security', '/settings/security/mfa', '/settings/security/sessions'].includes(value)) return value
  const id = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
  return new RegExp(`^/notes/${id}$`).test(value) ? value : null
}
export class SignInDestination {
  private value: string | null = null
  remember(path: unknown) { this.value = safeDestination(path) }
  consume() { const result = this.value ?? '/notes'; this.value = null; return result }
  clear() { this.value = null }
}
