import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApplicationErrorBoundary } from './ApplicationErrorBoundary'

function Broken(): never { throw new Error('private content must not render') }

describe('ApplicationErrorBoundary', () => {
  it('shows a safe accessible fallback without rendering caught data', () => {
    const prior = vi.spyOn(console, 'error').mockImplementation(() => {})
    const onReset = vi.fn()
    try {
      render(<ApplicationErrorBoundary onReset={onReset}><Broken /></ApplicationErrorBoundary>)
      expect(screen.getByRole('alert')).toBeTruthy()
      expect(screen.getByRole('heading', { name: 'Something went wrong' })).toBeTruthy()
      expect(screen.queryByText(/private content/)).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
      expect(onReset).toHaveBeenCalledTimes(1)
    } finally { prior.mockRestore() }
  })
})
