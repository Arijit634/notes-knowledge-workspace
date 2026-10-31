import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { elapsedLabel, PendingSearch } from './SearchActivity'

afterEach(() => { cleanup(); vi.useRealTimers() })

describe('pending search feedback', () => {
  it('shows elapsed activity without inventing server progress, including after a long wait', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-29T12:00:00Z'))
    const { unmount } = render(<PendingSearch startedAt={Date.now()} />)
    expect(screen.getByRole('status').textContent).toBe('Searching your knowledge…')
    expect(screen.getByRole('timer').textContent).toBe('0:00')
    expect(document.querySelector('.search-spinner')?.getAttribute('aria-hidden')).toBe('true')
    expect(screen.queryByRole('progressbar')).toBeNull()
    act(() => { vi.advanceTimersByTime(45000) })
    expect(screen.getByRole('status').textContent).toBe('Still waiting for a response…')
    expect(screen.getByRole('timer').textContent).toBe('0:45')
    expect(screen.getByRole('timer').getAttribute('aria-live')).toBe('off')
    expect(screen.getByText(/has not been submitted again/)).toBeTruthy()
    act(() => { vi.advanceTimersByTime(75000) })
    expect(screen.getByRole('timer').textContent).toBe('2:00')
    unmount(); expect(vi.getTimerCount()).toBe(0)
  })

  it('uses the original request time after returning to the page', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-29T12:00:00Z'))
    const startedAt = Date.now() - 91000
    render(<PendingSearch startedAt={startedAt} />)
    expect(screen.getByRole('timer').textContent).toBe('1:31')
    act(() => { vi.setSystemTime(Date.now() + 20000); document.dispatchEvent(new Event('visibilitychange')) })
    expect(screen.getByRole('timer').textContent).toBe('1:51')
  })

  it('formats minutes safely without negative or non-finite elapsed values', () => {
    expect(elapsedLabel(3661000)).toBe('61:01')
    for (const value of [-1000, NaN, Infinity]) expect(elapsedLabel(value)).toBe('0:00')
  })
})
