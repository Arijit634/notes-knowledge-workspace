import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { FormDialog } from './FormDialog'
import { restoreFocus } from './ConfirmDialog'

afterEach(() => { cleanup(); vi.restoreAllMocks(); document.body.replaceChildren() })
describe('form modal focus boundaries', () => {
  it('receives an explicit opener even after inert has already removed its focus', () => {
    const opener = document.createElement('button'); opener.textContent = 'Open'; document.body.append(opener)
    opener.focus(); opener.blur()
    const queued: FrameRequestCallback[] = []; vi.spyOn(window, 'requestAnimationFrame').mockImplementation(fn => { queued.push(fn); return 1 })
    const { unmount } = render(<FormDialog title="Synthetic modal" opener={opener} onClose={() => {}}><input aria-label="Synthetic field" /></FormDialog>)
    expect(document.activeElement).toBe(screen.getByLabelText('Synthetic field')); unmount(); queued.forEach(fn => fn(0)); expect(document.activeElement).toBe(opener)
  })
  it('traps native form controls, safely closes on Escape and focuses the root while busy', () => {
    const close = vi.fn(), { rerender } = render(<FormDialog title="Synthetic modal" onClose={close}><input aria-label="Synthetic field" /></FormDialog>)
    const end = screen.getByRole('button', { name: 'Close' }); end.focus(); fireEvent.keyDown(end, { key: 'Tab' }); expect(document.activeElement).toBe(screen.getByLabelText('Synthetic field'))
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' }); expect(close).toHaveBeenCalledOnce()
    rerender(<FormDialog title="Synthetic modal" onClose={close} busy><input aria-label="Synthetic field" disabled /></FormDialog>); expect(document.activeElement).toBe(screen.getByRole('dialog')); fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' }); expect(close).toHaveBeenCalledOnce()
  })
  it.each(['disconnected', 'hidden', 'disabled'])('restores to a safe focusable fallback when opener is %s', kind => {
    const opener = document.createElement('button'), fallback = document.createElement('main'); fallback.tabIndex = -1; document.body.append(fallback)
    if (kind !== 'disconnected') document.body.append(opener)
    if (kind === 'hidden') opener.hidden = true
    if (kind === 'disabled') opener.disabled = true
    restoreFocus(opener, fallback); expect(document.activeElement).toBe(fallback)
  })
  it('does not steal focus from the next dialog in a sequential modal hand-off', () => {
    const previous = document.createElement('button'), fallback = document.createElement('main'); fallback.tabIndex = -1; document.body.append(previous, fallback)
    render(<FormDialog title="Next modal" onClose={() => {}}><input aria-label="Next field" /></FormDialog>)
    restoreFocus(previous, fallback); expect(document.activeElement).toBe(screen.getByLabelText('Next field'))
  })
})
