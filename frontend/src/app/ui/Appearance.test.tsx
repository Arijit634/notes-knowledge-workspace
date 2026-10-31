import { afterEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { AppearanceControl, AppearanceRoot } from './Appearance'

afterEach(() => {
  const control = screen.queryByRole('combobox', { name: 'Appearance' })
  if (control) fireEvent.change(control, { target: { value: 'system' } })
  cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks()
})
test('system theme follows the OS; explicit appearance stays stable until changed', () => {
  let dark = true
  const changes = new Set<() => void>()
  vi.stubGlobal('matchMedia', () => ({ get matches() { return dark }, addEventListener: (_: string, listener: () => void) => changes.add(listener), removeEventListener: (_: string, listener: () => void) => changes.delete(listener) }))
  const storage = vi.spyOn(Storage.prototype, 'setItem')
  render(<><AppearanceRoot /><AppearanceControl /></>)
  expect(document.documentElement.dataset.theme).toBe('dark')
  const control = screen.getByRole('combobox', { name: 'Appearance' })
  fireEvent.change(control, { target: { value: 'light' } })
  expect(document.documentElement.dataset.theme).toBe('light')
  dark = false; changes.forEach(listener => listener())
  dark = true; changes.forEach(listener => listener())
  expect(document.documentElement.dataset.theme).toBe('light')
  fireEvent.change(control, { target: { value: 'system' } })
  expect(document.documentElement.dataset.theme).toBe('dark')
  expect(storage.mock.calls).toEqual([['nkw.appearance', 'light']])
  expect(window.localStorage.getItem('nkw.appearance')).toBeNull()
})
test('an unavailable OS theme API does not prevent sign-in or rendering', () => {
  vi.stubGlobal('matchMedia', undefined)
  render(<><AppearanceRoot /><AppearanceControl /></>)
  expect(document.documentElement.dataset.theme).toBe('light')
  fireEvent.change(screen.getByRole('combobox', { name: 'Appearance' }), { target: { value: 'dark' } })
  expect(document.documentElement.style.colorScheme).toBe('dark')
})
test('blocked storage does not block changing appearance', () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Storage unavailable') })
  render(<><AppearanceRoot /><AppearanceControl /></>)
  fireEvent.change(screen.getByRole('combobox', { name: 'Appearance' }), { target: { value: 'dark' } })
  expect(document.documentElement.dataset.theme).toBe('dark')
})
