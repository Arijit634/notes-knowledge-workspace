import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { App } from './App'

describe('application shell', () => {
  it('renders an accessible bootstrap status without pretending features exist', () => {
    render(<App />)

    expect(screen.getByRole('heading', { level: 1, name: 'Notes & Knowledge Workspace' })).toBeTruthy()
    expect(screen.getByText(/Product features are not part of this bootstrap/i)).toBeTruthy()
  })
})
