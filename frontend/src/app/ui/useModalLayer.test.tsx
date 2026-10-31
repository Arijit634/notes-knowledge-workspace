import { afterEach, expect, test } from 'vitest'
import { cleanup, render } from '@testing-library/react'
import { useModalLayer } from './useModalLayer'

afterEach(() => { cleanup(); document.getElementById('root')?.remove() })
function Layer() { useModalLayer(); return null }
test('nested dialogs keep the app inert and preserve its original state', () => {
  const root = document.createElement('div'); root.id = 'root'; document.body.append(root)
  const outer = render(<Layer />), inner = render(<Layer />)
  expect(root.inert).toBe(true)
  inner.unmount(); expect(root.inert).toBe(true)
  outer.unmount(); expect(root.inert).toBe(false)
  root.inert = true
  const preexisting = render(<Layer />); preexisting.unmount()
  expect(root.inert).toBe(true)
})
