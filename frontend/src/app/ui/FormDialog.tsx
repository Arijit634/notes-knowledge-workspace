import { useEffect, useId, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { restoreFocus } from './ConfirmDialog'
import { useModalLayer } from './useModalLayer'

/** Form-capable modal: native fields, bounded focus, and safe no-scroll restoration. */
export function FormDialog({ title, children, onClose, busy = false, opener, inactive = false, closeText = 'Close', hideClose = false }: { title: string; children: ReactNode; onClose: () => void; busy?: boolean; opener?: HTMLElement | null; inactive?: boolean; closeText?: string; hideClose?: boolean }) {
  useModalLayer()
  const root = useRef<HTMLDivElement>(null), id = useId()
  useEffect(() => {
    const previous = opener ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null)
    const first = root.current?.querySelector<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled)')
    ;(first ?? root.current)?.focus({ preventScroll: true })
    return () => { requestAnimationFrame(() => restoreFocus(previous, document.getElementById('public-content') ?? document.getElementById('notes-content'))) }
  }, [])
  useEffect(() => { if (busy) root.current?.focus({ preventScroll: true }) }, [busy])
  return createPortal(<div className="dialog-backdrop"><div className="confirm-dialog public-form-dialog" role="dialog" aria-modal="true" aria-labelledby={id} ref={root} tabIndex={-1} inert={inactive} onKeyDown={e => {
    if (e.key === 'Escape' && !busy) { e.preventDefault(); onClose() }
    if (e.key === 'Tab') {
      const controls = Array.from(e.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href]')).filter(el => !el.closest('[hidden],[inert]'))
      const at = controls.indexOf(document.activeElement as HTMLElement)
      if (!controls.length) { e.preventDefault(); root.current?.focus() }
      else if (at === -1 || (!e.shiftKey && at === controls.length - 1) || (e.shiftKey && at === 0)) { e.preventDefault(); controls[e.shiftKey ? controls.length - 1 : 0]?.focus() }
    }
  }}><h2 id={id}>{title}</h2>{children}{!hideClose && <button type="button" className="button-secondary" disabled={busy} onClick={onClose}>{closeText}</button>}</div></div>, document.body)
}
