import { useEffect, useState } from 'react'
import { ApiProblemError } from '../api/ProblemDetailsDecoder'

/** Only a bounded safe Retry-After delay is retained; never the response payload. */
export function retryAfterSeconds(error: unknown, now = Date.now()): number {
  if (!(error instanceof ApiProblemError) || error.problem.status !== 429) return 0
  const raw = error.metadata.retryAfter
  if (raw && /^\d{1,5}$/.test(raw)) return Math.min(86400, Math.max(1, Number(raw)))
  const date = raw ? Date.parse(raw) : NaN
  return Number.isFinite(date) ? Math.min(86400, Math.max(1, Math.ceil((date - now) / 1000))) : 30
}
export function useRetryAfter(error: unknown) {
  const [remaining, setRemaining] = useState(0)
  useEffect(() => {
    const seconds = retryAfterSeconds(error); setRemaining(seconds)
    if (!seconds) return
    const until = Date.now() + seconds * 1000
    const timer = window.setInterval(() => setRemaining(Math.max(0, Math.ceil((until - Date.now()) / 1000))), 1000)
    return () => window.clearInterval(timer)
  }, [error])
  return remaining
}
