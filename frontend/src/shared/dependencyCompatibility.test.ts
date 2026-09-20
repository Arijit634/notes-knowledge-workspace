import { QueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { createMemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'

describe('approved frontend dependency compatibility', () => {
  it('resolves the frozen package APIs without creating product behavior', () => {
    expect(typeof QueryClient).toBe('function')
    expect(typeof createMemoryRouter).toBe('function')
    expect(typeof useForm).toBe('function')
  })
})
