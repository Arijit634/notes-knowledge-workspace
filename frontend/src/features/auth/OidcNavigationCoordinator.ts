export function approvedGoogleNavigation(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.length > 4096) return null
  try {
    const url = new URL(raw)
    if (url.protocol !== 'https:' || url.origin !== 'https://accounts.google.com'
        || url.username || url.password || url.hash
        || url.pathname !== '/o/oauth2/v2/auth') return null
    return url.href
  } catch { return null }
}

export function navigateToGoogle(raw: unknown): void {
  const target = approvedGoogleNavigation(raw)
  if (!target) throw new Error('The sign-in destination is unavailable.')
  window.location.assign(target)
}
