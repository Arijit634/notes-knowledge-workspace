import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { FileDropSurface } from './FileDropSurface'
afterEach(cleanup)
const file = new File(['synthetic'], 'scene.png', { type: 'image/png' })
const transfer = { types: ['Files'], files: [file], dropEffect: 'none' }
describe('File drop surface', () => {
  it('highlights nested drags without flicker and passes file bytes once', () => {
    const receive = vi.fn(); render(<FileDropSurface label="Files" onFiles={receive}><span>Child</span></FileDropSurface>)
    const zone = screen.getByRole('region', { name: 'Files' }), child = screen.getByText('Child')
    fireEvent.dragEnter(zone, { dataTransfer: transfer }); fireEvent.dragEnter(child, { dataTransfer: transfer }); fireEvent.dragLeave(child, { dataTransfer: transfer })
    expect(screen.getByRole('status').textContent).toBe('Drop files to add them')
    fireEvent.drop(child, { dataTransfer: transfer }); expect(receive).toHaveBeenCalledExactlyOnceWith([file]); expect(screen.queryByRole('status')).toBeNull()
  })
  it('does not fetch or intercept dragged text or URLs', () => {
    const receive = vi.fn(); render(<FileDropSurface label="Files" onFiles={receive}>Editor</FileDropSurface>)
    expect(fireEvent.drop(screen.getByRole('region'), { dataTransfer: { types: ['text/uri-list'], files: [], getData: () => 'https://example.test/image.png' } })).toBe(true)
    expect(receive).not.toHaveBeenCalled()
  })
  it('blocks disabled file drops and outside browser navigation without uploading', () => {
    const receive = vi.fn(); render(<FileDropSurface label="Files" onFiles={receive} disabled>Editor</FileDropSurface>)
    expect(fireEvent.drop(screen.getByRole('region'), { dataTransfer: transfer })).toBe(false); expect(receive).not.toHaveBeenCalled()
    expect(fireEvent.drop(window, { dataTransfer: transfer })).toBe(false)
  })
  it('clears the drag cue when the window loses focus and removes listeners on unmount', () => {
    const view = render(<FileDropSurface label="Files" onFiles={vi.fn()}>Editor</FileDropSurface>)
    fireEvent.dragEnter(screen.getByRole('region'), { dataTransfer: transfer }); fireEvent.blur(window); expect(screen.queryByRole('status')).toBeNull()
    view.unmount(); expect(fireEvent.drop(window, { dataTransfer: transfer })).toBe(true)
  })
})
