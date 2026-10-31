import { useEffect, useState } from 'react'
import './SearchActivity.css'

export function elapsedLabel(milliseconds: number) {
  const seconds = Math.max(0, Math.floor(Number.isFinite(milliseconds) ? milliseconds / 1000 : 0))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

export function SearchSpinner() {
  return <span className="search-spinner" aria-hidden="true" />
}

/** This request has no server progress resource yet. Never manufacture a phase,
 * completion percentage, queue position or remaining-time estimate. */
export function PendingSearch({ startedAt }: { startedAt: number }) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    const update = () => setNow(Date.now())
    const timer = window.setInterval(update, 1000)
    document.addEventListener('visibilitychange', update)
    return () => { clearInterval(timer); document.removeEventListener('visibilitychange', update) }
  }, [])
  const elapsed = Math.max(0, now - startedAt)
  return <section className="search-activity" aria-label="AI search activity">
    <SearchSpinner />
    <div><p className="search-activity-title" role="status">{elapsed < 45000 ? 'Searching your knowledge…' : 'Still waiting for a response…'}</p>
      <p className="notes-help">{elapsed < 45000
        ? 'Waiting for the server. You can switch pages and return to this search.'
        : 'This is taking longer. Your question has not been submitted again.'}</p></div>
    <span className="search-elapsed" role="timer" aria-live="off" aria-label="Time waiting for search">{elapsedLabel(elapsed)}</span>
  </section>
}
