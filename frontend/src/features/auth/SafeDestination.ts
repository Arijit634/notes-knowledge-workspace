/** A navigation hint only; never resource authority, persistent state, or a redirect URL. */
export function safeDestination(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 160) return null
  if (['/notes', '/notes/new', '/settings/security', '/settings/security/mfa', '/settings/security/sessions'].includes(value)) return value
  return /^\/notes\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value) ? value : null
}
