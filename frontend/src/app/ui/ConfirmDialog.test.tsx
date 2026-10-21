import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ConfirmDialog, restoreFocus } from './ConfirmDialog'

afterEach(() => { cleanup(); document.body.replaceChildren() })

it.each(['disconnected', 'hidden', 'disabled', 'inert'])('restores to a safe fallback when the opener is %s', state => {
  const opener = document.createElement('button'), fallback = document.createElement('main')
  fallback.tabIndex = -1; document.body.append(opener, fallback)
  if (state === 'disconnected') opener.remove()
  if (state === 'hidden') opener.style.display = 'none'
  if (state === 'disabled') opener.disabled = true
  if (state === 'inert') opener.setAttribute('inert', '')
  restoreFocus(opener, fallback)
  expect(document.activeElement).toBe(fallback)
})

it('keeps keyboard focus inside a busy confirmation and prevents cancellation', async () => {
  const cancel = vi.fn(), confirm = vi.fn()
  const view = render(<ConfirmDialog title="Change?" confirmText="Confirm" onCancel={cancel} onConfirm={confirm}>Review the change.</ConfirmDialog>)
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cancel' }))
  view.rerender(<ConfirmDialog title="Change?" busy confirmText="Confirm" onCancel={cancel} onConfirm={confirm}>Review the change.</ConfirmDialog>)
  const dialog = screen.getByRole('dialog')
  expect(document.activeElement).toBe(dialog)
  fireEvent.keyDown(dialog, { key: 'Tab' }); fireEvent.keyDown(dialog, { key: 'Escape' })
  expect(document.activeElement).toBe(dialog); expect(cancel).not.toHaveBeenCalled(); expect(confirm).not.toHaveBeenCalled()
})

it('returns focus to the explicit opener after inert is removed, without scrolling', async () => {
  const opener = document.createElement('button'); document.body.append(opener)
  const focus = vi.spyOn(opener, 'focus')
  const view = render(<ConfirmDialog title="Change?" opener={opener} confirmText="Confirm" onCancel={() => {}} onConfirm={() => {}}>Review.</ConfirmDialog>)
  opener.setAttribute('inert', '')
  view.unmount(); opener.removeAttribute('inert')
  await waitFor(() => expect(document.activeElement).toBe(opener))
  expect(focus).toHaveBeenCalledWith({ preventScroll: true })
})
